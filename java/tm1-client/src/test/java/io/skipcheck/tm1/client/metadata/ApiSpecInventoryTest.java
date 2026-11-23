// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The language-neutral API spec ({@code spec/tm1-api.json}), which clients in other
 * languages are generated from and count their coverage against, lists the same endpoints
 * as this client's coverage gate: every implementation is held to one inventory.
 */
class ApiSpecInventoryTest {

	@Test
	void specListsTheEndpointsTheCoverageGateCounts() {
		JsonNode spec = JsonMapper.builder().build().readTree(Path.of(System.getProperty("tm1.spec")).toFile());
		List<String> ids = new ArrayList<>();
		spec.path("endpoints").forEach(e -> ids.add(e.path("id").asString()));
		assertThat(ids).containsExactlyElementsOf(Endpoints.of(MetadataSnapshot.load()));
		assertThat(spec.path("source").path("schemaVersion").asString()).isEqualTo(MetadataSnapshot.VERSION);
	}

}
