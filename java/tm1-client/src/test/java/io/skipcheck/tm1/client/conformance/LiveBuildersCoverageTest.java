// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every generated create and patch builder, and each of its setters, is validated against
 * a real TM1: written by a step of some live scenario ({@code spec/conformance/live})
 * that TM1 is expected to accept, which every client runs through its builders' setters
 * ({@code $patch}) and {@code compareLiveTraffic} compares (#354). A setter is written
 * when such a step's write of its builder has its key ({@code Name}, or
 * {@code Dimensions@odata.bind} for a binding), nested values counted for their own write
 * types, a root's subtype's write for the root's setters too. A step expecting an error,
 * or a {@code finally} step, doesn't count: a refusal TM1 is expected to give can be
 * checked, and the builder still listed as excluded. Each counted write is built here as
 * the runners build it, so CI checks it offline.
 *
 * <p>
 * What no step writes yet is in {@code conformance/live-builders-pending.txt}; what can't
 * be written in a TM1 container, in {@code live-builders-excluded.txt}, each with why. A
 * newly generated builder or setter fails here until a step writes it, or it's listed; a
 * listed one a step now writes fails too, to be taken off the list.
 */
class LiveBuildersCoverageTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path REPO = Path.of(System.getProperty("tm1.repo"));

	private static final String BIND = "@odata.bind";

	/** A scenario's name placeholder, ${name}. */
	private static final String NAME = "\\$\\{([A-Za-z0-9_]+)\\}";

	@Test
	void everyBuilderAndSetterIsWrittenLiveOrListed() throws IOException {
		JsonNode plan = JSON.readTree(REPO.resolve("spec/tm1-api.json").toFile()).path("builders");
		Set<String> built = new HashSet<>();
		Map<String, Set<String>> written = new HashMap<>();
		List<Path> scenarios;
		try (Stream<Path> files = Files.list(REPO.resolve("spec/conformance/live"))) {
			scenarios = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		assertThat(scenarios).as("the live scenarios").isNotEmpty();
		assertThat(plan.size()).as("the spec's write plan").isPositive();
		for (Path file : scenarios) {
			// Only a step that succeeds validates a write: not one TM1 is expected to
			// refuse,
			// nor a finally step, whose failures are ignored
			// A name a step saves from TM1's answer (an ID, a logger's level) has no
			// value to
			// make up: a write holding one is counted, not built here
			Set<String> saved = new HashSet<>();
			JsonNode scenario = JSON.readTree(file.toFile());
			scenario.path("steps").forEach(step -> saved.addAll(step.path("save").propertyNames()));
			for (JsonNode step : scenario.path("steps")) {
				if (!step.path("expect").has("error")) {
					collect(step.path("args"), plan, built, written, file.getFileName().toString(), saved);
				}
			}
		}
		// What each builder offers, its own level's setters: a subtype's inherited ones
		// are
		// its root's
		Set<String> unwritten = new TreeSet<>();
		for (var b : plan.properties()) {
			String builder = b.getKey();
			JsonNode p = b.getValue();
			if (!built.contains(builder)) {
				unwritten.add(builder);
				continue;
			}
			for (JsonNode s : p.path("setters")) {
				if (!p.path("base").isNull() && !s.path("declaredOn").asString().equals(p.path("type").asString())) {
					continue;
				}
				List<String> keys = new ArrayList<>();
				if (s.path("set").asBoolean()) {
					keys.add(s.path("json").asString());
				}
				if (s.path("bind").asBoolean()) {
					keys.add(s.path("json").asString() + BIND);
				}
				for (String key : keys) {
					if (!written.getOrDefault(builder, Set.of()).contains(key)) {
						unwritten.add(builder + "." + key);
					}
				}
			}
		}
		Map<String, String> pending = list("live-builders-pending.txt");
		Map<String, String> excluded = list("live-builders-excluded.txt");
		Set<String> both = new TreeSet<>(pending.keySet());
		both.retainAll(excluded.keySet());
		assertThat(both).as("in both lists").isEmpty();
		assertThat(excluded.entrySet()).as("live-builders-excluded.txt: each entry says why")
			.allMatch(e -> !e.getValue().isBlank());
		Set<String> listed = new TreeSet<>(pending.keySet());
		listed.addAll(excluded.keySet());
		Set<String> missing = new TreeSet<>(unwritten);
		missing.removeAll(listed);
		assertThat(missing).as("builders and setters no live scenario writes: write them, or list them").isEmpty();
		Set<String> stale = new TreeSet<>(listed);
		stale.removeAll(unwritten);
		assertThat(stale).as("listed, but a live scenario writes them, or they're gone: take them off the list")
			.isEmpty();
	}

	/**
	 * Every {@code $patch} in a step's arguments, nested values through their own write
	 * types. Each is built first, as every runner builds it (its names made up), so
	 * what's counted is a write a caller could make: CI checks that offline, live or not.
	 */
	private static void collect(JsonNode node, JsonNode plan, Set<String> built, Map<String, Set<String>> written,
			String scenario, Set<String> saved) {
		if (node.isArray()) {
			node.forEach(n -> collect(n, plan, built, written, scenario, saved));
		}
		else if (node.isObject()) {
			if (node.path("$patch").isString() && node.path("json").isObject()) {
				String patch = node.path("$patch").asString();
				String text = node.path("json").toString();
				boolean fromTm1 = saved.stream().anyMatch(name -> text.contains("${" + name + "}"));
				try {
					if (fromTm1) {
						throw new SkipBuild();
					}
					JsonNode json = JSON.readTree(text.replaceAll(NAME, "tm1conf_$1"));
					PatchBuilders.build(Class.forName("io.skipcheck.tm1.client.model." + patch), json);
				}
				catch (SkipBuild e) {
					// Counted, as the runners build it with TM1's value
				}
				catch (ClassNotFoundException | RuntimeException e) {
					throw new AssertionError(scenario + ": " + patch + " doesn't build: " + e.getMessage(), e);
				}
				write(patch, node.path("json"), plan, built, written);
			}
			else {
				node.forEach(n -> collect(n, plan, built, written, scenario, saved));
			}
		}
	}

	private static void write(String builder, JsonNode json, JsonNode plan, Set<String> built,
			Map<String, Set<String>> written) {
		// A root's write of a subtype is that subtype's
		JsonNode id = json.path("@odata.type");
		if (id.isString()) {
			String sub = id.asString().substring(id.asString().lastIndexOf('.') + 1)
					+ (builder.endsWith("Create") ? "Create" : "Patch");
			if (plan.has(sub)) {
				builder = sub;
			}
		}
		JsonNode p = plan.path(builder);
		built.add(builder);
		String base = p.path("base").isString() ? p.path("base").asString() : null;
		if (base != null) {
			built.add(base);
		}
		Map<String, JsonNode> setters = new LinkedHashMap<>();
		p.path("setters").forEach(s -> setters.put(s.path("json").asString(), s));
		for (var e : json.properties()) {
			String key = e.getKey();
			JsonNode s = setters.get(key.endsWith(BIND) ? key.substring(0, key.length() - BIND.length()) : key);
			if (s == null) {
				continue;
			}
			// A setter its root declares is the root's
			boolean inherited = base != null && !s.path("declaredOn").asString().equals(p.path("type").asString());
			written.computeIfAbsent(inherited ? base : builder, k -> new HashSet<>()).add(key);
			if (!key.endsWith(BIND) && s.path("value").isString()) {
				for (JsonNode v : e.getValue().isArray() ? e.getValue() : List.of(e.getValue())) {
					if (v.isObject()) {
						write(s.path("value").asString(), v, plan, built, written);
					}
				}
			}
		}
	}

	/**
	 * A list's entries ({@code Builder} or {@code Builder.Setter}), each with what
	 * follows its {@code :}; {@code #} starts a comment.
	 */
	private static Map<String, String> list(String name) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		Set<String> twice = new TreeSet<>();
		for (String line : Files.readAllLines(REPO.resolve("spec/conformance").resolve(name), StandardCharsets.UTF_8)) {
			String entry = line.strip();
			if (entry.isEmpty() || entry.startsWith("#")) {
				continue;
			}
			int colon = entry.indexOf(':');
			String key = (colon < 0 ? entry : entry.substring(0, colon)).strip();
			if (out.put(key, colon < 0 ? "" : entry.substring(colon + 1).strip()) != null) {
				twice.add(key);
			}
		}
		assertThat(twice).as(name + ": listed twice").isEmpty();
		return out;
	}

	/** A write that holds a name saved from TM1's answer: not built here. */
	private static final class SkipBuild extends Exception {

		private static final long serialVersionUID = 1L;

	}

}
