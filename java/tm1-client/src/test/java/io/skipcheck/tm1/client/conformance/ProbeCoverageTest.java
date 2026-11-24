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
 * Every setter of every create and patch type is one the write probe verifies, or one the
 * overlay keeps with why (#338): the builder manifest ({@code builders.json}) is exactly
 * the spec's {@code offers}, and each offer the spec calls verified is, by this test's
 * own reading of the probe file. So a client can't offer a write TM1 wasn't seen to make,
 * and a new TM1 version's probe, or a change to the rule, shows up here.
 *
 * <p>
 * Verified means the probe found TM1 to act on it: an outcome of {@code applied},
 * {@code replaces}, {@code appends} or {@code mixed} for the setter's property (or
 * navigation, bound or nested), or its collection, in a create or a PATCH of its type or
 * a type derived from it; a nested field, by path ({@code Administration/Language}),
 * verifies each part's type along the way; the keys a least create sent, and what it
 * requires, verify a create's; an applied rename, a patch's {@code Name}.
 */
class ProbeCoverageTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path REPO = Path.of(System.getProperty("tm1.repo"));

	private static final String BIND = "@odata.bind";

	private static final Set<String> DOES = Set.of("applied", "replaces", "appends", "mixed");

	@Test
	void everySetterIsVerifiedOrKeptWithWhy() throws IOException {
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
			JsonNode root = JSON.readTree(probe.toFile());
			JsonNode writes = root.path("writes");
			for (String id : writes.propertyNames()) {
				collect(writes.path(id), id, types, verified);
			}
			for (String id : root.path("collections").propertyNames()) {
				String[] parts = id.split("/", 2);
				if (parts.length == 2 && DOES.contains(root.path("collections").path(id).path("outcome").asString())) {
					verified.add(parts[0] + "|patch|" + parts[1]);
				}
			}
		}
		JsonNode offers = spec.path("offers");
		JsonNode builders = JSON.readTree(REPO.resolve("spec/conformance/builders.json").toFile()).path("builders");
		assertThat(new TreeSet<>(builders.propertyNames())).as("the write types the clients generate")
			.isEqualTo(new TreeSet<>(offers.propertyNames()));
		Set<String> unverified = new TreeSet<>();
		for (String builder : offers.propertyNames()) {
			Set<String> offered = new TreeSet<>(offers.path(builder).propertyNames());
			Set<String> built = new TreeSet<>();
			builders.path(builder).path("setters").forEach(s -> built.add(s.asString()));
			assertThat(built).as(builder + "'s setters, as the spec offers them").isEqualTo(offered);
			String type = builder.replaceAll("(Patch|Create)$", "");
			boolean complex = !types.containsKey(type) || !types.get(type).path("kind").asString().equals("entity");
			for (String setter : offered) {
				JsonNode why = offers.path(builder).path(setter);
				if (why.has("kept") || why.has("unverified")) {
					// The overlay's, with its reason: the export refuses one the probe
					// verifies
					assertThat(why.path(why.has("kept") ? "kept" : "unverified").asString()).as(builder + "." + setter)
						.isNotBlank();
					continue;
				}
				String write = builder.endsWith("Create") ? "post" : "patch";
				boolean ok = isVerified(type, write, setter, verified, types) || (complex
						&& isVerified(type, write.equals("post") ? "patch" : "post", setter, verified, types));
				if (!ok) {
					unverified.add(builder + "." + setter);
				}
			}
		}
		assertThat(unverified).as("offered as verified, but the probe file doesn't verify them").isEmpty();
	}

	private static void collect(JsonNode w, String id, Map<String, JsonNode> types, Set<String> verified) {
		String type = w.path("type").asString(id.split("/", 2)[0]);
		for (String write : List.of("post", "patch")) {
			for (String property : w.path(write).propertyNames()) {
				if (!DOES.contains(w.path(write).path(property).path("outcome").asString())) {
					continue;
				}
				// A setting or a nested value's field, by path: each part's type is
				// verified
				// along the way. A nested type has one write type, used in its holder's
				// create and PATCH alike, so a nested part counts for both
				String at = type;
				boolean nested = false;
				for (String part : property.split("/", -1)) {
					if (at == null) {
						break;
					}
					verified.add(at + "|" + write + "|" + part);
					if (nested) {
						verified.add(at + "|" + (write.equals("post") ? "patch" : "post") + "|" + part);
					}
					at = typeOf(at, part.replace(BIND, ""), types);
					nested = true;
				}
			}
			for (String nav : w.path("navigations").propertyNames()) {
				JsonNode n = w.path("navigations").path(nav).path(write);
				if (DOES.contains(n.path("bind").path("outcome").asString())) {
					verified.add(type + "|" + write + "|" + nav + BIND);
				}
				if (DOES.contains(n.path("nested").path("outcome").asString())) {
					verified.add(type + "|" + write + "|" + nav);
				}
			}
		}
		if (w.path("minimal").path("outcome").asString().equals("accepted")) {
			if (w.has("keys")) {
				w.path("keys").forEach(k -> verified.add(type + "|post|" + k.asString()));
			}
			else {
				verified.add(type + "|post|Name");
			}
			w.path("required").forEach(r -> verified.add(type + "|post|" + r.asString()));
		}
		if (w.path("rename").path("outcome").asString().equals("applied")) {
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
