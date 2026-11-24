// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every setter of every create and patch type is verified by the write probe, or listed,
 * with why, in {@code spec/conformance/probe-coverage.json}: {@code excluded} for what
 * isn't probed for a reason, {@code pending} for what isn't probed yet. A setter the
 * clients offer (the builder manifest, {@code builders.json}) that neither verifies nor
 * lists fails, so a new TM1 version's writable property or type can't go unprobed
 * unnoticed; so does a listing the probe now verifies, so the pending list only shrinks.
 *
 * <p>
 * Verified means a probed outcome, of any kind but {@code untested}, for the setter's
 * property (or navigation, bound or nested) in a create or a PATCH of its type or a type
 * derived from it; a singleton's settings, probed by path
 * ({@code Administration/Language}), verify each part's type along the way; {@code Name}
 * and what a create requires are verified by the probe's least create, and a patch's
 * {@code Name} by its rename.
 */
class ProbeCoverageTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path REPO = Path.of(System.getProperty("tm1.repo"));

	private static final String BIND = "@odata.bind";

	@Test
	void everySetterIsProbedOrListedWithWhy() throws IOException {
		JsonNode spec = JSON.readTree(REPO.resolve("spec/tm1-api.json").toFile());
		Map<String, JsonNode> types = new HashMap<>();
		spec.path("types").forEach(t -> types.put(t.path("name").asString(), t));
		Set<String> verified = new HashSet<>();
		List<Path> probes;
		try (Stream<Path> files = Files.list(REPO.resolve("java/tm1-client/src/model/metadata"))) {
			probes = files.filter(f -> f.getFileName().toString().matches("tm1-probe-.*\\.json")).sorted().toList();
		}
		assertThat(probes).isNotEmpty();
		for (Path probe : probes) {
			JsonNode writes = JSON.readTree(probe.toFile()).path("writes");
			for (String id : writes.propertyNames()) {
				collect(writes.path(id), id, types, verified);
			}
		}
		JsonNode coverage = JSON.readTree(REPO.resolve("spec/conformance/probe-coverage.json").toFile());
		Set<String> listed = new TreeSet<>();
		coverage.path("excluded").propertyNames().forEach(listed::add);
		coverage.path("pending").propertyNames().forEach(listed::add);

		JsonNode builders = JSON.readTree(REPO.resolve("spec/conformance/builders.json").toFile()).path("builders");
		Set<String> unlisted = new TreeSet<>();
		Set<String> stale = new TreeSet<>();
		for (String builder : builders.propertyNames()) {
			String type = builder.replaceAll("(Patch|Create)$", "");
			String write = builder.endsWith("Create") ? "post" : "patch";
			boolean wholeListed = listed.contains(builder);
			boolean anyUnverified = false;
			for (JsonNode s : builders.path(builder).path("setters")) {
				String setter = s.asString();
				boolean ok = isVerified(type, write, setter, verified, types);
				anyUnverified |= !ok;
				String key = builder + "." + setter;
				if (ok && listed.contains(key)) {
					stale.add(key);
				}
				if (!ok && !wholeListed && !listed.contains(key)) {
					unlisted.add(key);
				}
			}
			if (wholeListed && !anyUnverified && builders.path(builder).path("setters").size() > 0) {
				stale.add(builder);
			}
		}
		assertThat(unlisted).as("setters the probe doesn't verify, not listed in probe-coverage.json").isEmpty();
		assertThat(stale).as("listed in probe-coverage.json, but the probe verifies them now: take them out").isEmpty();
		for (String entry : listed) {
			String builder = entry.contains(".") ? entry.substring(0, entry.indexOf('.')) : entry;
			assertThat(builders.has(builder)).as("probe-coverage.json names " + entry + ", which no client has")
				.isTrue();
		}
	}

	private static void collect(JsonNode w, String id, Map<String, JsonNode> types, Set<String> verified) {
		String type = w.path("type").asString(id.split("/", 2)[0]);
		for (String write : List.of("post", "patch")) {
			for (String property : w.path(write).propertyNames()) {
				if (w.path(write).path(property).path("outcome").asString().equals("untested")) {
					continue;
				}
				// A singleton's setting, by path: each part's type is verified along the
				// way
				String at = type;
				for (String part : property.split("/", -1)) {
					if (at == null) {
						break;
					}
					verified.add(at + "|" + write + "|" + part);
					at = typeOf(at, part, types);
				}
			}
			for (String nav : w.path("navigations").propertyNames()) {
				JsonNode n = w.path("navigations").path(nav).path(write);
				if (!n.path("bind").path("outcome").asString().equals("untested")) {
					verified.add(type + "|" + write + "|" + nav + BIND);
				}
				if (!n.path("nested").path("outcome").asString().equals("untested")) {
					verified.add(type + "|" + write + "|" + nav);
				}
			}
		}
		if (w.path("minimal").path("outcome").asString().equals("accepted")) {
			verified.add(type + "|post|Name");
			w.path("required").forEach(r -> verified.add(type + "|post|" + r.asString()));
		}
		if (w.has("rename")) {
			verified.add(type + "|patch|Name");
		}
	}

	private static boolean isVerified(String type, String write, String setter, Set<String> verified,
			Map<String, JsonNode> types) {
		for (String v : verified) {
			String[] parts = v.split("\\|", 3);
			if (parts[1].equals(write) && parts[2].equals(setter) && derives(parts[0], type, types)) {
				return true;
			}
		}
		return false;
	}

	/** Whether a type is {@code root} or derived from it. */
	private static boolean derives(String type, String root, Map<String, JsonNode> types) {
		for (String t = type; t != null; t = base(t, types)) {
			if (t.equals(root)) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable String base(String type, Map<String, JsonNode> types) {
		JsonNode t = types.get(type);
		if (t == null || !t.path("baseType").isString()) {
			return null;
		}
		String base = t.path("baseType").asString();
		return base.substring(base.lastIndexOf('.') + 1);
	}

	/** The type of a type's property or navigation, by its simple name, or null. */
	private static @Nullable String typeOf(String type, String property, Map<String, JsonNode> types) {
		for (String t = type; t != null; t = base(t, types)) {
			JsonNode node = types.get(t);
			if (node == null) {
				return null;
			}
			for (String list : List.of("properties", "navigationProperties")) {
				for (JsonNode p : node.path(list)) {
					if (p.path("name").asString().equals(property)) {
						String name = p.path("type").asString();
						return name.substring(name.lastIndexOf('.') + 1);
					}
				}
			}
		}
		return null;
	}

}
