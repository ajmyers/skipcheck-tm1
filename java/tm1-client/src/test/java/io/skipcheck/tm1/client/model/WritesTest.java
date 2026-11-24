// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.odata.ODataPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The create types and {@code builder(read)}: a write made from a read entity holds only
 * what TM1 applies, by the probe ({@code tm1-probe-<schema>.json}'s writes). Every client
 * has these tests.
 */
class WritesTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static JsonNode json(Object write) {
		return JSON.readTree(JSON.writeValueAsString(write));
	}

	@Test
	void createsFromAReadEntityWhatAPostApplies() {
		Element read = JSON.readValue("""
				{"Name": "North", "Type": "Consolidated", "Index": 4, "Level": 1,
				 "Attributes": {"Caption": "North"}}""", Element.class);
		JsonNode create = json(ElementCreate.builder(read).build());
		// A create ignores Index, Level and Attributes: they aren't offered, nor copied
		assertThat(create.propertyNames()).containsExactlyInAnyOrder("Name", "Type");
		assertThat(create.path("Type").asString()).isEqualTo("Consolidated");
		// A builder, to change what's copied
		assertThat(json(ElementCreate.builder(read).name("South").build()).path("Name").asString()).isEqualTo("South");
	}

	@Test
	void patchesFromAReadEntityLeavingOutWhatAPatchRejectsOrAppends() {
		Chore read = JSON.readValue("""
				{"Name": "Nightly", "Active": true, "Frequency": "P1DT00H00M00S",
				 "Attributes": {"Caption": "Nightly"},
				 "Tasks": [{"Step": 0, "Parameters": [{"Name": "pValue", "Value": "1"}]}]}""", Chore.class);
		JsonNode patch = json(ChorePatch.builder(read).build());
		// A PATCH refuses Attributes and a rename, and appends Tasks: none is copied
		assertThat(patch.propertyNames()).containsExactlyInAnyOrder("Active", "Frequency");
		// A create ignores Attributes too: they're written through the attribute cubes.
		// Tasks is a navigation, so none is copied: a task's Process would need binding.
		assertThat(json(ChoreCreate.builder(read).build()).propertyNames()).contains("Frequency")
			.doesNotContain("Attributes", "Tasks");
	}

	@Test
	void copiesAValueWhoseTypeHasSubtypesAsItsOwnType() {
		ServerSettings read = JSON.readValue("""
				{"ServerName": "tm1", "EncryptionAtRest": {"@odata.type": "#ibm.tm1.api.v1.VaultSettings",
				 "KeyRef": "key", "ServiceEndpoint": "https://vault"}}""", ServerSettings.class);
		JsonNode encryption = json(ServerSettingsPatch.builder(read).build()).path("EncryptionAtRest");
		assertThat(encryption.path("@odata.type").asString()).isEqualTo("#ibm.tm1.api.v1.VaultSettings");
		assertThat(encryption.path("KeyRef").asString()).isEqualTo("key");
		assertThat(encryption.path("ServiceEndpoint").asString()).isEqualTo("https://vault");
	}

	@Test
	void refusesACreateWithoutWhatTm1Needs() {
		assertThatThrownBy(() -> CubeCreate.builder().name("Sales").build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Dimensions");
		ODataPath dimensions = ODataPath.of("Dimensions");
		assertThat(json(CubeCreate.builder()
			.name("Sales")
			.bindDimensions(dimensions.key("Region"), dimensions.key("Month"))
			.build()).path("Dimensions@odata.bind").size()).isEqualTo(2);
	}

}
