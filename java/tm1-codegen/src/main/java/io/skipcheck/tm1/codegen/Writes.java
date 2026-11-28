// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What TM1 does with each property in a POST and a PATCH, per entity type, from the
 * probe's file beside the snapshot ({@code tm1-probe-<schema>.json}, its {@code writes}
 * and {@code collections}).
 *
 * <p>
 * A probe case can be a variant of a type ({@code Process/view source}); variants merge
 * into their type, each property taking the most a variant saw it do: {@code applied},
 * then {@code transformed}, {@code ignored}, {@code rejected}, {@code untested}. So a
 * property rejected only because one fixture's value didn't suit (code without regions)
 * counts as writable.
 *
 * @param schemaVersion the newest schema version the probe ran on
 * @param types by entity type's simple name
 * @param observedIn all schema versions whose probe files contributed
 */
public record Writes(String schemaVersion, Map<String, TypeWrites> types, List<String> observedIn) {

	/** Outcomes, from the most a write does to the least. */
	static final List<String> ORDER = List.of("applied", "transformed", "accepted", "ignored", "rejected", "untested");

	/** Collection outcomes, from the most a PATCH does to the least. */
	static final List<String> COLLECTION_ORDER = List.of("replaces", "appends", "ignored", "rejected", "untested");

	/**
	 * What a type's writes do.
	 *
	 * @param create per property, what a POST does with it
	 * @param required properties a POST needs besides {@code Name}
	 * @param patch per property, what a PATCH does with it
	 * @param nullRefused properties a PATCH refuses {@code null} for
	 * @param rename what a PATCH of {@code Name} does, or null if not probed
	 * @param collections per collection or navigation property, what a PATCH of it does:
	 * {@code replaces}, {@code appends}, {@code ignored}, {@code rejected}...
	 * @param observedIn the schema versions whose probe files observed writes for this
	 * type
	 */
	public record TypeWrites(Map<String, String> create, List<String> required, Map<String, String> patch,
			List<String> nullRefused, @Nullable String rename, Map<String, String> collections,
			List<String> observedIn) {

		/** Copies the collections, sorted. */
		public TypeWrites {
			create = Collections.unmodifiableMap(new TreeMap<>(create));
			required = List.copyOf(new TreeSet<>(required));
			patch = Collections.unmodifiableMap(new TreeMap<>(patch));
			nullRefused = List.copyOf(new TreeSet<>(nullRefused));
			collections = Collections.unmodifiableMap(new TreeMap<>(collections));
			observedIn = List.copyOf(observedIn);
		}

		/** Convenience constructor without explicit {@code observedIn}. */
		public TypeWrites(Map<String, String> create, List<String> required, Map<String, String> patch,
				List<String> nullRefused, @Nullable String rename, Map<String, String> collections) {
			this(create, required, patch, nullRefused, rename, collections, List.of());
		}

		/**
		 * Whether {@code builder(read)} copies a property into a patch: a PATCH sets it
		 * (it applies or transforms it, it's write-only, its outcome is untested, or it
		 * wasn't probed), and isn't a collection a PATCH appends to (copying one would
		 * add its members again). A collection a PATCH replaces is copied: the read holds
		 * all of it.
		 * <p>
		 * An {@code untested} outcome counts, so a setter offered only because the
		 * overlay keeps it is copied too: {@code ServerSettingsPatch}'s
		 * {@code EncryptionAtRest}, which the probe can't PATCH without a key store. It's
		 * the only copy through a root patch's {@code of(read)}, the dispatch
		 * {@code writes.json} checks every client against; leaving it out would leave
		 * that untested (#358). Revisit when the probe can verify it. A nested value's
		 * type has no writes here yet, so its {@code builder(read)} copies every setter
		 * it offers (#360).
		 * @param property the property's name
		 * @return whether a patch made from a read copies it
		 */
		public boolean patches(String property) {
			return writes(patch.get(property)) && !"appends".equals(collections.get(property))
					&& !refused(collections.get(property));
		}

		private static boolean refused(@Nullable String outcome) {
			return outcome != null && (outcome.equals("rejected") || outcome.equals("ignored"));
		}

		private static boolean writes(@Nullable String outcome) {
			return outcome == null || outcome.equals("applied") || outcome.equals("writeOnly")
					|| outcome.equals("transformed") || outcome.equals("untested");
		}

	}

	/** Copies the map, sorted. */
	public Writes {
		types = Collections.unmodifiableMap(new TreeMap<>(types));
		observedIn = List.copyOf(observedIn);
	}

	/** Creates a single-version {@code Writes}. */
	public Writes(String schemaVersion, Map<String, TypeWrites> types) {
		this(schemaVersion, types, schemaVersion.isEmpty() ? List.of() : List.of(schemaVersion));
	}

	/** None: every type writes every property. */
	public static final Writes NONE = new Writes("", Map.of(), List.of());

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Reads the writes from a probe file. A file without them (probe version 1) has none.
	 * @param probeJson the probe file's content
	 * @return the writes
	 */
	public static Writes read(String probeJson) {
		return read(probeJson, Overlay.NONE);
	}

	/**
	 * Reads the writes from a probe file, with the overlay's {@code writeOnly} properties
	 * counted as written: the probe reads them as ignored, as TM1 never sends them back.
	 * @param probeJson the probe file's content
	 * @param overlay the overlay
	 * @return the writes
	 * @throws IllegalArgumentException if the overlay names a write-only property the
	 * probe has no writes for
	 */
	public static Writes read(String probeJson, Overlay overlay) {
		return read(List.of(probeJson), overlay);
	}

	/**
	 * Reads and merges writes across multiple probe files, with the overlay's
	 * {@code writeOnly} properties counted as written.
	 * @param probeJsons the probe files' JSON contents, oldest-first
	 * @param overlay the overlay
	 * @return the merged writes
	 * @throws IllegalArgumentException if the overlay names a write-only property no
	 * probe has writes for
	 */
	public static Writes read(List<String> probeJsons, Overlay overlay) {
		if (probeJsons.isEmpty()) {
			return NONE;
		}
		Map<String, Map<String, String>> create = new TreeMap<>();
		Map<String, Map<String, String>> patch = new TreeMap<>();
		Map<String, TreeSet<String>> required = new TreeMap<>();
		Map<String, TreeSet<String>> nullRefused = new TreeMap<>();
		Map<String, String> rename = new TreeMap<>();
		Map<String, Map<String, String>> collections = new TreeMap<>();
		Map<String, List<String>> typeObservedIn = new TreeMap<>();
		List<String> observedIn = new ArrayList<>();
		String latestVersion = "";
		for (String probeJson : probeJsons) {
			JsonNode root = JSON.readTree(probeJson);
			String version = root.path("server").path("schemaVersion").asString("");
			if (!version.isEmpty()) {
				latestVersion = version;
				if (!observedIn.contains(version)) {
					observedIn.add(version);
				}
			}
			for (Map.Entry<String, JsonNode> c : root.path("writes").properties()) {
				JsonNode w = c.getValue();
				String type = w.path("type").asString(c.getKey().split("/", -1)[0]);
				if (!version.isEmpty()) {
					List<String> seen = typeObservedIn.computeIfAbsent(type, k -> new ArrayList<>());
					if (!seen.contains(version)) {
						seen.add(version);
					}
				}
				merge(create.computeIfAbsent(type, k -> new TreeMap<>()), w.path("post"));
				merge(patch.computeIfAbsent(type, k -> new TreeMap<>()), w.path("patch"));
				TreeSet<String> req = required.computeIfAbsent(type, k -> new TreeSet<>());
				w.path("required").forEach(r -> req.add(r.asString()));
				TreeSet<String> refused = nullRefused.computeIfAbsent(type, k -> new TreeSet<>());
				for (Map.Entry<String, JsonNode> p : w.path("patchNull").properties()) {
					if (p.getValue().path("outcome").asString("").equals("rejected")) {
						refused.add(p.getKey());
					}
				}
				String r = w.path("rename").path("outcome").asString(null);
				if (r != null) {
					rename.merge(type, r, Writes::most);
				}
			}
			for (Map.Entry<String, JsonNode> c : root.path("collections").properties()) {
				String[] id = c.getKey().split("/", -1);
				String type = id[0];
				String property = id[1].replace("@odata.bind", "");
				if (!version.isEmpty()) {
					List<String> seen = typeObservedIn.computeIfAbsent(type, k -> new ArrayList<>());
					if (!seen.contains(version)) {
						seen.add(version);
					}
				}
				collections.computeIfAbsent(type, k -> new TreeMap<>())
					.merge(property, c.getValue().path("outcome").asString(""), Writes::mostCollection);
			}
		}
		for (String key : overlay.writeOnly().keySet()) {
			String[] parts = key.split("/", -1);
			Map<String, String> c = create.get(parts[0]);
			Map<String, String> p = patch.get(parts[0]);
			if (c == null || p == null || !(c.containsKey(parts[1]) || p.containsKey(parts[1]))) {
				throw new IllegalArgumentException(
						"The overlay's writeOnly names " + key + ", which the probe has no writes for");
			}
			c.put(parts[1], "writeOnly");
			p.put(parts[1], "writeOnly");
		}
		Map<String, TypeWrites> types = new TreeMap<>();
		for (Map.Entry<String, Map<String, String>> e : create.entrySet()) {
			String type = e.getKey();
			types.put(type,
					new TypeWrites(e.getValue(), List.copyOf(required.getOrDefault(type, new TreeSet<>())),
							patch.getOrDefault(type, Map.of()),
							List.copyOf(nullRefused.getOrDefault(type, new TreeSet<>())), rename.get(type),
							collections.getOrDefault(type, Map.of()), typeObservedIn.getOrDefault(type, List.of())));
		}
		collections.forEach((type, c) -> types.computeIfAbsent(type, t -> new TypeWrites(Map.of(), List.of(), Map.of(),
				List.of(), null, c, typeObservedIn.getOrDefault(type, List.of()))));
		return new Writes(latestVersion, types, observedIn);
	}

	/**
	 * What a type's writes do, if the probe has them.
	 * @param type the type's simple name
	 * @return its writes, or null
	 */
	public @Nullable TypeWrites get(String type) {
		return types.get(type);
	}

	private static void merge(Map<String, String> into, JsonNode outcomes) {
		for (Map.Entry<String, JsonNode> p : outcomes.properties()) {
			// A nested value's field, by path (Parameters/Prompt): the generators don't
			// act on
			// those yet (#338)
			if (p.getKey().contains("/")) {
				continue;
			}
			into.merge(p.getKey(), p.getValue().path("outcome").asString("untested"), Writes::most);
		}
	}

	/** The outcome that writes more. */
	static String most(String a, String b) {
		int ia = ORDER.indexOf(a);
		int ib = ORDER.indexOf(b);
		return (ia < 0 ? ORDER.size() : ia) <= (ib < 0 ? ORDER.size() : ib) ? a : b;
	}

	/** The collection outcome that writes more. */
	static String mostCollection(String a, String b) {
		int ia = COLLECTION_ORDER.indexOf(a);
		int ib = COLLECTION_ORDER.indexOf(b);
		return (ia < 0 ? COLLECTION_ORDER.size() : ia) <= (ib < 0 ? COLLECTION_ORDER.size() : ib) ? a : b;
	}

}
