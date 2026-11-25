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
 * ({@code live/writes.json} holds those no other scenario needs). That's each property a
 * create or a patch applies, nested ones by their path in their holder's value
 * ({@code Access/SSL/Enable}), each navigation bound or nested as it applies, each
 * collection a PATCH replaces or appends to, and a rename: whatever of it a client's
 * builders can send ({@link #sendable}). Transformed, ignored and rejected writes aren't
 * held to it: what reads back differently, or not at all, can't be checked by reading it
 * back. The setters the overlay keeps though the probe doesn't verify them are held to
 * the live scenarios by {@code LiveBuildersCoverageTest}, with every other setter.
 */
class ProbedWritesCoverageTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path REPO = Path.of(System.getProperty("tm1.repo"));

	private static final String BIND = "@odata.bind";

	private static final Set<String> DOES = Set.of("applied", "replaces", "appends", "mixed");

	@Test
	void everyWriteTheProbeFoundAppliedIsInALiveScenario() throws IOException {
		// What the scenarios write, in steps TM1 is expected to accept: per
		// write type, every JSON path its writes hold (Access/SSL/Enable,
		// Columns/Subset@odata.bind), through lists' members
		Map<String, Set<String>> written = new HashMap<>();
		try (Stream<Path> files = Files.list(REPO.resolve("spec/conformance/live"))) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
				for (JsonNode step : JSON.readTree(file.toFile()).path("steps")) {
					if (!step.path("expect").has("error")) {
						collect(step.path("args"), written);
					}
				}
			}
		}
		JsonNode plan = JSON.readTree(REPO.resolve("spec/tm1-api.json").toFile()).path("builders");
		assertThat(plan.size()).as("the spec's write plan").isPositive();
		List<String> missing = new ArrayList<>();
		Set<String> unsent = new TreeSet<>();
		List<Path> probes;
		try (Stream<Path> files = Files.list(REPO.resolve("java/tm1-client/src/model/metadata"))) {
			probes = files.filter(f -> f.getFileName().toString().matches("tm1-probe-.*\\.json")).sorted().toList();
		}
		assertThat(probes).isNotEmpty();
		for (Path probe : probes) {
			JsonNode root = JSON.readTree(probe.toFile());
			JsonNode writes = root.path("writes");
			String file = probe.getFileName().toString();
			for (String id : writes.propertyNames()) {
				// "Subset/dynamic" and "Subset/static" are both Subset's, as each says
				JsonNode probed = writes.path(id);
				String type = probed.path("type").asString(id.split("/", 2)[0]);
				for (String write : List.of("post", "patch")) {
					String builder = type + (write.equals("post") ? "Create" : "Patch");
					JsonNode properties = probed.path(write);
					for (String property : properties.propertyNames()) {
						// A nested field (Access/SSL/Enable) by its path
						// in its holder's value
						if (applied(properties.path(property))) {
							hold(plan, written, builder, property, true, file + ": " + id, missing, unsent);
						}
					}
					for (String navigation : probed.path("navigations").propertyNames()) {
						JsonNode outcome = probed.path("navigations").path(navigation).path(write);
						if (applied(outcome.path("bind"))) {
							hold(plan, written, builder, navigation + BIND, false, file + ": " + id, missing, unsent);
						}
						if (applied(outcome.path("nested"))) {
							hold(plan, written, builder, navigation, false, file + ": " + id, missing, unsent);
						}
					}
				}
				// A rename TM1 applies is a patch's Name
				if (applied(probed.path("rename"))) {
					hold(plan, written, type + "Patch", "Name", false, file + ": " + id, missing, unsent);
				}
			}
			// A collection a PATCH replaces, appends to or mixes: a patch
			// sending it, as the probe's case does (Hierarchy/Elements,
			// Subset/Elements@odata.bind)
			for (String id : root.path("collections").propertyNames()) {
				String[] parts = id.split("/", 2);
				if (parts.length == 2 && applied(root.path("collections").path(id))) {
					hold(plan, written, parts[0] + "Patch", parts[1], false, file + ": " + id, missing, unsent);
				}
			}
		}
		assertThat(new TreeSet<>(missing)).as("applied writes no live scenario sends (add them to live/writes.json)")
			.isEmpty();
		Set<String> listed = new TreeSet<>();
		for (String line : Files.readAllLines(REPO.resolve("spec/conformance/probed-writes-unsent.txt"))) {
			if (!line.isBlank() && !line.startsWith("#")) {
				int colon = line.indexOf(": ");
				assertThat(colon).as("a line of probed-writes-unsent.txt gives why after ': ': " + line).isPositive();
				listed.add(line.substring(0, colon).strip());
			}
		}
		assertThat(unsent)
			.as("applied writes no client's builders can send: list each, with why, in probed-writes-unsent.txt")
			.isEqualTo(listed);
	}

	/**
	 * Holds one probed write to the live scenarios: one a client's builders can send must
	 * be sent; one they can't joins {@code unsent}, to be listed with why. A top-level
	 * property counts as sent in its binding too (a create binding {@code Dimensions}).
	 */
	private static void hold(JsonNode plan, Map<String, Set<String>> written, String builder, String path,
			boolean undeclared, String where, List<String> missing, Set<String> unsent) {
		if (!sendable(plan, builder, path, undeclared)) {
			unsent.add(builder + " " + path);
			return;
		}
		Set<String> sent = written.getOrDefault(builder, Set.of());
		if (!sent.contains(path) && (path.contains("/") || !sent.contains(path + BIND))) {
			missing.add(where + " " + builder + " " + path);
		}
	}

	/**
	 * Whether a client's builders can send {@code path} in a write of {@code builder}, by
	 * the spec's write plan: each segment but the last through the setter whose value
	 * type holds the next, the last by a setter for it (or its bind, for a
	 * {@code @odata.bind}), or as an undeclared key of an open type. What none can send
	 * is listed in {@code probed-writes-unsent.txt}, with why: a setter the overlay
	 * withholds, as a PATCH would replace it and another method sends it (a user's
	 * groups, a group's users, a hierarchy's elements and edges), a binding a PATCH
	 * appends with, which the clients leave to the service's own add (a subset's
	 * elements), or one with no setter.
	 */
	static boolean sendable(JsonNode plan, String builder, String path) {
		return sendable(plan, builder, path, true);
	}

	/**
	 * As {@link #sendable(JsonNode, String, String)}; a navigation is sent by its setter
	 * only, not as an open type's undeclared key.
	 */
	static boolean sendable(JsonNode plan, String builder, String path, boolean undeclared) {
		String[] segments = path.split("/", -1);
		JsonNode write = plan.path(builder);
		for (int i = 0; i < segments.length; i++) {
			if (write.isMissingNode()) {
				return false;
			}
			boolean bind = segments[i].endsWith(BIND);
			String json = bind ? segments[i].substring(0, segments[i].length() - BIND.length()) : segments[i];
			JsonNode setter = null;
			for (JsonNode s : write.path("setters")) {
				if (s.path("json").asString().equals(json)) {
					setter = s;
				}
			}
			if (i == segments.length - 1) {
				return setter != null ? setter.path(bind ? "bind" : "set").asBoolean()
						: undeclared && !bind && write.path("open").asBoolean();
			}
			if (setter == null || !setter.path("set").asBoolean() || setter.path("value").isNull()) {
				return false;
			}
			write = plan.path(setter.path("value").asString());
		}
		return false;
	}

	/**
	 * Whether TM1 applied the write: set it, or replaced, appended to or mixed a
	 * collection.
	 */
	private static boolean applied(JsonNode outcome) {
		return DOES.contains(outcome.path("outcome").asString());
	}

	private static void collect(JsonNode node, Map<String, Set<String>> written) {
		if (node.isArray()) {
			node.forEach(n -> collect(n, written));
		}
		else if (node.isObject()) {
			if (node.path("$patch").isString() || node.path("$fromRead").isString()) {
				String type = node.path("$patch").isString() ? node.path("$patch").asString()
						: node.path("$fromRead").asString();
				paths(node.path("json"), "", written.computeIfAbsent(type, k -> new HashSet<>()));
				collect(node.path("json"), written);
			}
			else {
				node.forEach(n -> collect(n, written));
			}
		}
	}

	/** Every path {@code value} holds, through lists' members: A, A/B, A/B/C. */
	private static void paths(JsonNode value, String prefix, Set<String> out) {
		if (value.isArray()) {
			value.forEach(member -> paths(member, prefix, out));
		}
		else if (value.isObject()) {
			for (var e : value.properties()) {
				String path = prefix + e.getKey();
				out.add(path);
				paths(e.getValue(), path + "/", out);
			}
		}
	}

}
