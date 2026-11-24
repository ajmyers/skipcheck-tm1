// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;
import io.skipcheck.tm1.csdl.CsdlProperty;

class UndeclaredTest {

	private static final CsdlDocument METADATA = CsdlParser.parse("""
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <EntityType Name="Process"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			   </EntityType>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""");

	private static final String PROBE = """
			{"probeVersion": 2, "server": {"schemaVersion": "11.8.35"},
			 "undeclared": {"Process": {
			   "UIData": {"json": "string"}, "Locked": {"json": "boolean"}, "Count": {"json": "number"},
			   "VariablesUIData": {"json": "array"}, "Shown": {"json": "null"}, "Owner": {"json": "navigation"}}}}""";

	private static Overlay overlay(String json) {
		return Overlay.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void typesEachPropertyFromTheOverlayOrTheValueTheProbeRead() {
		Undeclared u = Undeclared.read(PROBE,
				overlay("{\"undeclared\": {\"Process/VariablesUIData\": {\"type\": \"Collection(Edm.String)\"}}}"),
				METADATA);
		assertThat(u.schemaVersion()).isEqualTo("11.8.35");
		assertThat(u.properties()).containsOnlyKeys("Process/Count", "Process/Locked", "Process/Shown",
				"Process/UIData", "Process/VariablesUIData");
		assertThat(u.get("Process", "UIData").edmType()).isEqualTo("Edm.String");
		assertThat(u.get("Process", "Locked").edmType()).isEqualTo("Edm.Boolean");
		assertThat(u.get("Process", "Count").edmType()).isEqualTo("Edm.Double");
		assertThat(u.get("Process", "VariablesUIData").edmType()).isEqualTo("Collection(Edm.String)");
		assertThat(u.get("Process", "Shown").edmType()).isNull();
		assertThat(u.get("Process", "UIData").note()).contains("found by probing TM1 schema 11.8.35");
	}

	@Test
	void addsThePropertiesToTheDocumentAndTheUntypedOnesToTheDepartures() {
		Undeclared u = Undeclared.read(PROBE, Overlay.NONE, METADATA);
		CsdlDocument augmented = u.applyTo(METADATA);
		assertThat(augmented.entityType("tm1.Process").orElseThrow().properties()).extracting(CsdlProperty::name)
			.containsExactly("Name", "Count", "Locked", "Shown", "UIData", "VariablesUIData");
		assertThat(augmented.entityType("tm1.Process").orElseThrow().properties().get(4).annotations())
			.extracting(a -> a.value().asText().orElse(""))
			.containsExactly(u.get("Process", "UIData").note());
		Overlay overlay = u.applyTo(Overlay.NONE);
		assertThat(overlay.departures().get("Process/Shown")).isEqualTo(Undeclared.READ_AS_JSON);
		// No overlay type and an array: not enough to type it
		assertThat(overlay.departures()).containsOnlyKeys("Process/Shown", "Process/VariablesUIData");
		assertThat(METADATA.entityType("tm1.Process").orElseThrow().properties()).hasSize(1);
	}

	@Test
	void refusesAnOverlayTypingWhatTheProbeDidNotFind() {
		assertThatThrownBy(() -> Undeclared.read(PROBE,
				overlay("{\"undeclared\": {\"Process/Nope\": {\"type\": \"Edm.String\"}}}"), METADATA))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Process/Nope");
	}

	@Test
	void refusesAProbeFileThatNamesADeclaredProperty() {
		String stale = "{\"server\": {\"schemaVersion\": \"11.8.35\"}, \"undeclared\": {\"Process\": {\"Name\": {\"json\": \"string\"}}}}";
		assertThatThrownBy(() -> Undeclared.read(stale, Overlay.NONE, METADATA))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Process/Name");
	}

	@Test
	void noneChangesNothing() {
		assertThat(Undeclared.NONE.applyTo(METADATA)).isSameAs(METADATA);
		assertThat(Undeclared.NONE.applyTo(Overlay.NONE).departures()).isEqualTo(Map.of());
	}

}
