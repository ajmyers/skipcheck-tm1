// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class ProbeReportTest {

	private static ProbeReport report() {
		return new ProbeReport("11.8.03500.6", "11.8.35", "ab12", 3,
				new TreeMap<>(Map.of("Subset", new TreeMap<>(Map.of("ExpandAbove", "boolean", "Alias", "string")),
						"Process", new TreeMap<>(Map.of("UIData", "string")))),
				new TreeMap<>(), new TreeSet<>(Set.of("View", "Annotation")), new TreeSet<>(Set.of("GitPlan")),
				new TreeMap<>(), new TreeMap<>());
	}

	@Test
	void writesSortedJsonThatDependsOnlyOnWhatWasFound() {
		String json = report().toJson();
		assertThat(json).isEqualTo(report().toJson()).endsWith("}\n");
		JsonNode root = ProbeClient.JSON.readTree(json);
		assertThat(root.path("probeVersion").asInt()).isEqualTo(ProbeReport.PROBE_VERSION);
		assertThat(root.path("server").path("schemaVersion").asString()).isEqualTo("11.8.35");
		assertThat(root.path("undeclared").propertyNames()).containsExactly("Process", "Subset");
		assertThat(root.path("undeclared").path("Subset").propertyNames()).containsExactly("Alias", "ExpandAbove");
		assertThat(root.path("undeclared").path("Subset").path("Alias").path("json").asString()).isEqualTo("string");
		assertThat(root.path("unverifiable").values()).extracting(JsonNode::asString)
			.containsExactly("Annotation", "View");
	}

	@Test
	void readsBackTheNamesForTheNextProbe() {
		assertThat(Tm1Probe.earlierNames(List.of(report().toJson()))).containsExactly("Alias", "ExpandAbove", "UIData");
	}

	@Test
	void refusesAFormatItDoesNotKnow() {
		assertThatThrownBy(() -> ProbeReport.undeclaredNames("{\"probeVersion\": 99}"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("99");
	}

	@Test
	void namesTheFileByTheSchemaVersion() {
		assertThat(ProbeReport.fileName("11.8.35")).isEqualTo("tm1-probe-11.8.35.json");
	}

}
