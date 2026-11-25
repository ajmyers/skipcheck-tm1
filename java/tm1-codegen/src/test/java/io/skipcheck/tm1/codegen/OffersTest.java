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

class OffersTest {

	private static final CsdlDocument METADATA = CsdlParser.parse("""
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			  <edmx:DataServices>
			    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="Rules" Type="Edm.String"/>
			        <Property Name="LastDataUpdate" Type="Edm.DateTimeOffset"/>
			        <NavigationProperty Name="Dimensions" Type="Collection(tm1.Dimension)"/>
			      </EntityType>
			      <EntityType Name="Dimension">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <EntityType Name="Hierarchy">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <NavigationProperty Name="Elements" Type="Collection(tm1.Element)"/>
			      </EntityType>
			      <EntityType Name="Element">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <ComplexType Name="CubeUpdate">
			        <Property Name="Value" Type="Edm.String"/>
			        <NavigationProperty Name="Tuple" Type="Collection(tm1.Element)"/>
			      </ComplexType>
			      <ComplexType Name="Settings">
			        <Property Name="Admin" Type="tm1.AdminSettings"/>
			        <Property Name="Fixed" Type="Edm.Boolean"/>
			      </ComplexType>
			      <ComplexType Name="AdminSettings">
			        <Property Name="Port" Type="Edm.Int32"/>
			        <Property Name="Language" Type="Edm.String"/>
			      </ComplexType>
			      <EntityType Name="View" Abstract="true">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="Shared" Type="Edm.Boolean"/>
			      </EntityType>
			      <EntityType Name="MDXView" BaseType="tm1.View">
			        <Property Name="MDX" Type="Edm.String"/>
			      </EntityType>
			      <EntityType Name="NativeView" BaseType="tm1.View">
			        <Property Name="Titles" Type="Edm.String"/>
			      </EntityType>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""");

	private static final String PROBE = """
			{"writes": {
			  "Cube": {"type": "Cube", "minimal": {"outcome": "accepted"}, "required": [],
			   "post": {"Rules": {"outcome": "applied"}, "LastDataUpdate": {"outcome": "transformed"}},
			   "patch": {"Rules": {"outcome": "applied"}, "LastDataUpdate": {"outcome": "ignored"}},
			   "navigations": {"Dimensions": {"post": {"bind": {"outcome": "applied"}, "nested": {"outcome": "ignored"}},
			    "patch": {"bind": {"outcome": "ignored"}, "nested": {"outcome": "rejected"}}}},
			   "rename": {"outcome": "rejected"}},
			  "Hierarchy": {"type": "Hierarchy", "minimal": {"outcome": "accepted"}, "required": [], "post": {}, "patch": {},
			   "navigations": {"Elements": {"patch": {"nested": {"outcome": "applied"}}}}},
			  "Element": {"type": "Element", "minimal": {"outcome": "accepted"}, "required": [], "post": {}, "patch": {}},
			  "Settings": {"type": "Settings", "patch": {"Admin/Port": {"outcome": "applied"},
			   "Admin/Language": {"outcome": "rejected"}, "Fixed": {"outcome": "ignored"}}},
			  "MDXView": {"type": "MDXView", "post": {}, "patch": {"MDX": {"outcome": "applied"}}}},
			 "collections": {"Hierarchy/Elements": {"outcome": "replaces"}}}""";

	private static Overlay overlay(String writes) {
		return Overlay
			.read(new ByteArrayInputStream(("{\"writes\": " + writes + "}").getBytes(StandardCharsets.UTF_8)));
	}

	private static final Overlay NONE = overlay("{}");

	@Test
	void offersOnlyWhatTheProbeVerifies() {
		Offers offers = Offers.of(PROBE, METADATA, NONE);
		// Name by the least create, Rules applied, Dimensions bound; LastDataUpdate only
		// transformed, the nested Dimensions ignored
		assertThat(offers.get("CubeCreate")).containsOnlyKeys("Name", "Rules", "Dimensions@odata.bind");
		assertThat(offers.get("CubeCreate")).containsEntry("Name", new Offers.Offer("verified", "minimal create"));
		// No rename, and nothing of the navigation
		assertThat(offers.get("CubePatch")).containsOnlyKeys("Rules");
		// Nothing writes a dimension: no write types
		assertThat(offers.get("DimensionCreate")).isNull();
		assertThat(offers.get("DimensionPatch")).isNull();
	}

	@Test
	void offersANestedFieldByItsPathAndEachPartOnTheWay() {
		Offers offers = Offers.of(PROBE, METADATA, NONE);
		assertThat(offers.get("SettingsPatch")).containsOnlyKeys("Admin");
		assertThat(offers.get("AdminSettingsPatch")).containsOnlyKeys("Port");
	}

	@Test
	void writesAPatchsCollectionWithItsMembersCreate() {
		Offers offers = Offers.of(PROBE, METADATA, NONE);
		assertThat(offers.get("HierarchyPatch")).containsOnlyKeys("Elements");
		// Each element is written whole, so with its create: Name included
		assertThat(offers.get("ElementCreate")).containsOnlyKeys("Name");

		// A collection whose members offer nothing is dropped, and its holder with it
		String noElement = PROBE.replace(
				"""
						"Element": {"type": "Element", "minimal": {"outcome": "accepted"}, "required": [], "post": {}, "patch": {}},
						"""
					.strip(),
				"");
		Offers without = Offers.of(noElement, METADATA, NONE);
		assertThat(without.get("HierarchyPatch")).isNull();
	}

	@Test
	void keepsARootForItsSubtypesAndStopsAtOneTheyCantMatch() {
		Offers offers = Offers.of(PROBE, METADATA, NONE);
		assertThat(offers.get("MDXViewPatch")).containsOnlyKeys("MDX");
		assertThat(offers.get("ViewPatch")).isEmpty();

		// The root would offer Shared, which a native view's patch doesn't
		String shared = PROBE.replace("\"patch\": {\"MDX\": {\"outcome\": \"applied\"}}",
				"\"patch\": {\"MDX\": {\"outcome\": \"applied\"}, \"Shared\": {\"outcome\": \"applied\"}}");
		String both = shared.replace("\"MDXView\": {",
				"\"NativeView\": {\"type\": \"NativeView\", \"post\": {}, \"patch\": {\"Titles\": {\"outcome\": \"applied\"}}}, \"MDXView\": {");
		assertThatThrownBy(() -> Offers.of(both, METADATA, NONE)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("NativeViewPatch doesn't offer Shared");
	}

	@Test
	void keepsWhatTheOverlayKeepsWithWhy() {
		Offers offers = Offers.of(PROBE, METADATA, overlay("""
				{"CubeCreate.LastDataUpdate": {"kept": "TM1 stamps its own time"},
				 "DimensionPatch": {"unverified": "not probed"}}"""));
		assertThat(offers.get("CubeCreate")).containsEntry("LastDataUpdate",
				new Offers.Offer("kept", "TM1 stamps its own time"));
		assertThat(offers.get("DimensionPatch"))
			.isEqualTo(Map.of("Name", new Offers.Offer("unverified", "not probed")));
	}

	@Test
	void refusesAnExceptionTheProbeVerifiesOrNoWriteTypeHas() {
		assertThatThrownBy(() -> Offers.of(PROBE, METADATA, overlay("{\"CubeCreate.Rules\": {\"kept\": \"x\"}}")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("which the probe verifies");
		assertThatThrownBy(() -> Offers.of(PROBE, METADATA, overlay("{\"CubeCreate.Nope\": {\"kept\": \"x\"}}")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("which no write type has");
		assertThatThrownBy(() -> overlay("{\"CubeCreate.Rules\": {\"maybe\": \"x\"}}"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void leavesOutASetterTheOverlayWithholds() {
		// Hierarchy/Elements: a PATCH replaces them, so the patch would offer it
		Offers offers = Offers.of(PROBE, METADATA,
				overlay("{\"HierarchyPatch.Elements\": {\"withheld\": \"replaceStructure does it\"}}"));
		assertThat(offers.get("HierarchyPatch")).as("with nothing else to offer, it has no patch").isNull();
		assertThat(Offers.of(PROBE, METADATA, NONE).get("HierarchyPatch")).containsKey("Elements");
	}

	@Test
	void leavesOutASetterOfAWriteTypeTheOverlayKeepsWhole() {
		// CubeUpdate: an operation's parameters, kept whole; TM1 takes its tuple only
		// bound
		String kept = "\"CubeUpdatePatch\": {\"kept\": \"an operation's parameters\"}";
		Offers offers = Offers.of(PROBE, METADATA,
				overlay("{" + kept + ", \"CubeUpdatePatch.Tuple\": {\"withheld\": \"bind it\"}}"));
		assertThat(offers.get("CubeUpdatePatch")).doesNotContainKey("Tuple").containsKey("Tuple@odata.bind");
		assertThat(Offers.of(PROBE, METADATA, overlay("{" + kept + "}")).get("CubeUpdatePatch")).containsKey("Tuple");
	}

	@Test
	void refusesToWithholdWhatTheProbeDoesntVerifyOrAWholeWriteType() {
		assertThatThrownBy(
				() -> Offers.of(PROBE, METADATA, overlay("{\"CubePatch.LastDataUpdate\": {\"withheld\": \"x\"}}")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("withholds CubePatch.LastDataUpdate, which the probe doesn't verify");
		assertThatThrownBy(() -> Offers.of(PROBE, METADATA, overlay("{\"CubePatch\": {\"withheld\": \"x\"}}")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("withhold a setter");
	}

}
