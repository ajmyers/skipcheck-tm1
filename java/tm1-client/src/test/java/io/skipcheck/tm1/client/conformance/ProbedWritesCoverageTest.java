// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every write the probe found TM1 applies is sent through every client against a real
 * TM1: each property a create or a patch applies, by {@code tm1-probe-<schema>.json}, is
 * in the JSON of a create (or patch) of its type in some live scenario
 * ({@code spec/conformance/live}), which every client runs and {@code compareLiveTraffic}
 * compares. A newly probed type or property fails here until a scenario writes it
 * ({@code live/writes.json} holds those no other scenario needs). Transformed, ignored
 * and rejected writes aren't held to it: what reads back differently, or not at all,
 * can't be checked by reading it back.
 */
class ProbedWritesCoverageTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path REPO = Path.of(System.getProperty("tm1.repo"));

	private static final String BIND = "@odata.bind";

	@Test
	void everyWriteTheProbeFoundAppliedIsInALiveScenario() throws IOException {
		// What the scenarios write: type and Create or Patch, to the property names
		Map<String, Set<String>> written = new HashMap<>();
		try (Stream<Path> files = Files.list(REPO.resolve("spec/conformance/live"))) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
				collect(JSON.readTree(file.toFile()), written);
			}
		}
		List<String> missing = new ArrayList<>();
		List<Path> probes;
		try (Stream<Path> files = Files.list(REPO.resolve("java/tm1-client/src/model/metadata"))) {
			probes = files.filter(f -> f.getFileName().toString().matches("tm1-probe-.*\\.json")).sorted().toList();
		}
		assertThat(probes).isNotEmpty();
		for (Path probe : probes) {
			JsonNode writes = JSON.readTree(probe.toFile()).path("writes");
			for (String id : writes.propertyNames()) {
				// Recorded, but not generated from yet (#338): no client writes it
				if (!writes.path(id).path("generate").asBoolean(true)) {
					continue;
				}
				// "Subset/dynamic" and "Subset/static" are both Subset's
				String type = id.split("/", 2)[0];
				for (String write : List.of("post", "patch")) {
					String suffix = write.equals("post") ? "Create" : "Patch";
					JsonNode properties = writes.path(id).path(write);
					for (String property : properties.propertyNames()) {
						// A nested field (Columns/Subset) is sent in its holder's value
						if (property.contains("/")) {
							continue;
						}
						if (properties.path(property).path("outcome").asString().equals("applied")
								&& !written.getOrDefault(type + suffix, Set.of()).contains(property)) {
							missing.add(probe.getFileName() + ": " + id + " " + suffix + " " + property);
						}
					}
				}
			}
		}
		assertThat(new TreeSet<>(missing)).as("applied writes no live scenario sends (add them to live/writes.json)")
			.isEmpty();
	}

	private static void collect(JsonNode node, Map<String, Set<String>> written) {
		if (node.isArray()) {
			node.forEach(n -> collect(n, written));
		}
		else if (node.isObject()) {
			if (node.path("$patch").isString()) {
				Set<String> names = written.computeIfAbsent(node.path("$patch").asString(), k -> new HashSet<>());
				for (String name : node.path("json").propertyNames()) {
					names.add(name.endsWith(BIND) ? name.substring(0, name.length() - BIND.length()) : name);
				}
				collect(node.path("json"), written);
			}
			else {
				node.forEach(n -> collect(n, written));
			}
		}
	}

}
