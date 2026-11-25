// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import io.skipcheck.tm1.csdl.CsdlTypeRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write types the clients generate, and the setters each offers: only what the write
 * probe verifies, or what the overlay's {@code writes} keeps with a reason (#338).
 *
 * <p>
 * A write type is a {@code Create} (an entity type's POST) or a {@code Patch} (an entity
 * type's PATCH, or a complex type's value in either). It offers a setter the probe found
 * TM1 to act on:
 * <ul>
 * <li>a property or nested field it applied: a nested field, by path
 * ({@code Access/SSL/Enable}), verifies each part along the way, and a nested type's
 * write type serves its holder's create and PATCH alike;</li>
 * <li>a navigation, nested or bound ({@code @odata.bind}), it applied, or a collection it
 * replaced or appended to;</li>
 * <li>the keys it sent ({@code Name}, a breakpoint's {@code ID}, an edge's parent and
 * component) and the required properties, when the least create was accepted;
 * {@code Name} in a patch, when a rename applied.</li>
 * </ul>
 * A patch doesn't offer a binding a PATCH appends with (a subset's {@code Elements}):
 * binding reads as "set to", so a service's own add does that. Nor does one offer a
 * setter the overlay withholds, which the write's only service would refuse: a user
 * patch's {@code Groups}, which {@code setGroups} sets (#361). A setter a subtype
 * verifies counts for its root. A write type with nothing to offer isn't generated,
 * unless a subtype's or an offered setter's value needs it (an open type, a root). An
 * offered nested setter whose value type offers nothing is dropped.
 *
 * @param builders per write type ({@code CubeCreate}), its setters, each with why
 */
public record Offers(Map<String, Map<String, Offer>> builders) {

	/** What a probe outcome needs to be, for a setter to count as verified. */
	static final Set<String> DOES = Set.of("applied", "replaces", "appends", "mixed");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BIND = "@odata.bind";

	/**
	 * Why a write type offers a setter.
	 *
	 * @param kind {@code verified} by the probe, or the overlay's {@code kept} or
	 * {@code unverified}
	 * @param why the probe's outcome or the way it verified it ({@code minimal create}),
	 * or the overlay's note
	 */
	public record Offer(String kind, String why) {
	}

	/** Copies the maps, sorted. */
	public Offers {
		Map<String, Map<String, Offer>> copy = new TreeMap<>();
		builders.forEach((b, s) -> copy.put(b, Collections.unmodifiableMap(new TreeMap<>(s))));
		builders = Collections.unmodifiableMap(copy);
	}

	/** None: no probe, so no write types. */
	public static final Offers NONE = new Offers(Map.of());

	/**
	 * What a write type offers.
	 * @param builder the write type's name, such as {@code CubeCreate}
	 * @return its setters, each with why, or null if it isn't generated
	 */
	public @Nullable Map<String, Offer> get(String builder) {
		return builders.get(builder);
	}

	/**
	 * Works out the write types from a probe file.
	 * @param probeJson the probe file's content
	 * @param metadata the {@code $metadata} document, with the undeclared properties
	 * added
	 * @param overlay the overlay, for its {@code writes}
	 * @return the write types
	 * @throws IllegalArgumentException if the overlay keeps a setter the probe verifies,
	 * or names one no write type has
	 * @throws IllegalStateException if a root's write type would offer a setter a
	 * subtype's doesn't, which a generated subtype can't take away
	 */
	public static Offers of(String probeJson, CsdlDocument metadata, Overlay overlay) {
		return new Builder(JSON.readTree(probeJson), metadata, overlay).offers();
	}

	private static final class Builder {

		private final Map<String, CsdlStructuredType> types = new LinkedHashMap<>();

		private final Map<String, Offer> verified = new LinkedHashMap<>();

		private final Map<String, Overlay.WriteException> exceptions;

		private final Set<String> exceptionsUsed = new TreeSet<>();

		/**
		 * Bindings a PATCH appends with, by {@code Type|Setter}: not offered in a patch.
		 */
		private final Set<String> appendingBinds = new TreeSet<>();

		Builder(JsonNode probe, CsdlDocument metadata, Overlay overlay) {
			metadata.structuredTypes().forEach(t -> types.putIfAbsent(t.name(), t));
			this.exceptions = overlay.writes();
			collect(probe);
		}

		private void collect(JsonNode probe) {
			for (Map.Entry<String, JsonNode> c : probe.path("writes").properties()) {
				JsonNode w = c.getValue();
				String type = w.path("type").asString(c.getKey().split("/", -1)[0]);
				for (String write : List.of("post", "patch")) {
					for (Map.Entry<String, JsonNode> p : w.path(write).properties()) {
						String outcome = p.getValue().path("outcome").asString("");
						if (!DOES.contains(outcome)) {
							continue;
						}
						String at = type;
						boolean nested = false;
						for (String part : p.getKey().split("/", -1)) {
							if (at == null) {
								break;
							}
							verify(at, write, part, outcome);
							if (nested) {
								verify(at, write.equals("post") ? "patch" : "post", part, outcome);
							}
							at = typeOf(at, part.replace(BIND, "")).orElse(null);
							nested = true;
						}
					}
					for (Map.Entry<String, JsonNode> n : w.path("navigations").properties()) {
						JsonNode forms = n.getValue().path(write);
						String bind = forms.path("bind").path("outcome").asString("");
						if (DOES.contains(bind)) {
							verify(type, write, n.getKey() + BIND, bind);
						}
						String nested = forms.path("nested").path("outcome").asString("");
						if (DOES.contains(nested)) {
							verify(type, write, n.getKey(), nested);
						}
					}
				}
				if (w.path("minimal").path("outcome").asString("").equals("accepted")) {
					// The keys it sent (a probe file from before it recorded them: Name)
					if (w.has("keys")) {
						w.path("keys").forEach(k -> verify(type, "post", k.asString(), "minimal create"));
					}
					else {
						verify(type, "post", "Name", "minimal create");
					}
					w.path("required").forEach(r -> verify(type, "post", r.asString(), "minimal create"));
				}
				if (w.path("rename").path("outcome").asString("").equals("applied")) {
					verify(type, "patch", "Name", "rename");
				}
			}
			for (Map.Entry<String, JsonNode> c : probe.path("collections").properties()) {
				String outcome = c.getValue().path("outcome").asString("");
				String[] id = c.getKey().split("/", 2);
				if (DOES.contains(outcome) && id.length == 2) {
					verify(id[0], "patch", id[1], outcome);
				}
				if (outcome.equals("appends") && id.length == 2 && id[1].endsWith(BIND)) {
					appendingBinds.add(id[0] + "|" + id[1]);
				}
			}
		}

		private void verify(String type, String write, String setter, String why) {
			verified.putIfAbsent(type + "|" + write + "|" + setter, new Offer("verified", why));
		}

		Offers offers() {
			Map<String, Map<String, Offer>> out = new TreeMap<>();
			for (CsdlStructuredType t : types.values()) {
				boolean entity = t instanceof CsdlEntityType;
				for (String suffix : entity ? List.of("Create", "Patch") : List.of("Patch")) {
					String builder = t.name() + suffix;
					Map<String, Offer> setters = new TreeMap<>();
					for (String setter : candidates(t.name())) {
						if (suffix.equals("Patch") && appendingBinds.contains(t.name() + "|" + setter)) {
							// Binding reads as "set to": one a PATCH appends with is left
							// to the
							// service's own add (a subset's addElements)
							continue;
						}
						Offer why = entity ? verifiedFor(t.name(), suffix.equals("Create") ? "post" : "patch", setter)
								: Optional.ofNullable(verifiedFor(t.name(), "post", setter))
									.orElse(verifiedFor(t.name(), "patch", setter));
						Overlay.WriteException one = exceptions.get(builder + "." + setter);
						if (one != null && one.kind().equals("withheld")) {
							// Verified, but left out: the write's only service refuses it
							if (why == null) {
								throw new IllegalArgumentException("The overlay's writes withholds " + builder + "."
										+ setter + ", which the probe doesn't verify: take it out");
							}
							exceptionsUsed.add(builder + "." + setter);
							continue;
						}
						if (why != null && one != null) {
							throw new IllegalArgumentException("The overlay's writes keeps " + builder + "." + setter
									+ ", which the probe verifies (" + why.why() + "): take it out");
						}
						Overlay.WriteException exception = one != null ? one : exceptions.get(builder);
						if (one != null) {
							exceptionsUsed.add(builder + "." + setter);
						}
						if (why == null && exception != null) {
							why = new Offer(exception.kind(), exception.note());
						}
						if (why != null) {
							setters.put(setter, why);
						}
					}
					if (exceptions.containsKey(builder)) {
						exceptionsUsed.add(builder);
					}
					if (!setters.isEmpty() || exceptions.containsKey(builder)) {
						out.put(builder, setters);
					}
				}
			}
			exceptions.forEach((key, e) -> {
				if (e.kind().equals("withheld") && !key.contains(".")) {
					throw new IllegalArgumentException(
							"The overlay's writes withholds " + key + ": withhold a setter, Builder.Setter");
				}
			});
			Set<String> unused = new TreeSet<>(exceptions.keySet());
			unused.removeAll(exceptionsUsed);
			if (!unused.isEmpty()) {
				throw new IllegalArgumentException(
						"The overlay's writes names " + unused + ", which no write type has");
			}
			close(out);
			guard(out);
			return new Offers(out);
		}

		/**
		 * Adds the roots and value types the offered setters need, drops a nested setter
		 * whose value type offers nothing, and a write type nothing needs, until nothing
		 * changes.
		 */
		private void close(Map<String, Map<String, Offer>> out) {
			boolean changed = true;
			while (changed) {
				changed = false;
				for (String builder : new ArrayList<>(out.keySet())) {
					String type = typeName(builder);
					String suffix = builder.substring(type.length());
					for (String b = base(type); b != null; b = base(b)) {
						changed |= out.putIfAbsent(b + suffix, new TreeMap<>()) == null;
					}
					Map<String, Offer> setters = out.getOrDefault(builder, new TreeMap<>());
					for (String setter : new ArrayList<>(setters.keySet())) {
						String value = valueType(builder, setter, out);
						if (value == null) {
							continue;
						}
						if (!offersSomething(value, out) && !open(typeName(value)) && !exceptions.containsKey(value)) {
							setters.remove(setter);
							changed = true;
						}
						else {
							changed |= out.putIfAbsent(value, new TreeMap<>()) == null;
						}
					}
				}
				Set<String> needed = new TreeSet<>(exceptions.keySet());
				for (String builder : out.keySet()) {
					String type = typeName(builder);
					String suffix = builder.substring(type.length());
					for (String b = base(type); b != null; b = base(b)) {
						needed.add(b + suffix);
					}
					for (String setter : out.getOrDefault(builder, Map.of()).keySet()) {
						String value = valueType(builder, setter, out);
						if (value != null) {
							needed.add(value);
						}
					}
				}
				changed |= out.entrySet().removeIf(e -> e.getValue().isEmpty() && !needed.contains(e.getKey()));
			}
		}

		/** Whether a write type, or one of its subtypes', offers a setter. */
		private boolean offersSomething(String builder, Map<String, Map<String, Offer>> out) {
			String type = typeName(builder);
			String suffix = builder.substring(type.length());
			for (String t : types.keySet()) {
				if (derives(t, type) && !out.getOrDefault(t + suffix, Map.of()).isEmpty()) {
					return true;
				}
			}
			return false;
		}

		private void guard(Map<String, Map<String, Offer>> out) {
			for (Map.Entry<String, Map<String, Offer>> e : out.entrySet()) {
				String type = typeName(e.getKey());
				String suffix = e.getKey().substring(type.length());
				for (String b = base(type); b != null; b = base(b)) {
					for (String setter : out.getOrDefault(b + suffix, Map.of()).keySet()) {
						if (!e.getValue().containsKey(setter)) {
							throw new IllegalStateException(
									e.getKey() + " doesn't offer " + setter + ", which its root " + b + suffix
											+ " does: a generated subtype can't take it away");
						}
					}
				}
			}
		}

		/**
		 * The write type a setter's value is written with, or null for a bound navigation
		 * or a primitive: a complex type's {@code Patch}; for an entity, its
		 * {@code Create} where it has one (a nested entity is new: in a deep insert, or a
		 * collection a PATCH replaces or appends to), else its {@code Patch}.
		 */
		private @Nullable String valueType(String builder, String setter, Map<String, Map<String, Offer>> out) {
			if (setter.endsWith(BIND)) {
				return null;
			}
			String type = typeName(builder);
			Optional<CsdlTypeRef> ref = declared(type, setter);
			if (ref.isEmpty()) {
				return null;
			}
			String value = simple(ref.get().name());
			CsdlStructuredType t = types.get(value);
			if (t == null) {
				return null;
			}
			if (!(t instanceof CsdlEntityType)) {
				return value + "Patch";
			}
			return out.containsKey(value + "Create") ? value + "Create" : value + "Patch";
		}

		private @Nullable Offer verifiedFor(String type, String write, String setter) {
			for (Map.Entry<String, Offer> v : verified.entrySet()) {
				String[] parts = v.getKey().split("\\|", 3);
				if (parts[1].equals(write) && parts[2].equals(setter) && derives(parts[0], type)) {
					return v.getValue();
				}
			}
			return null;
		}

		/** Its properties and navigations, nested and bound, its base types' included. */
		private Set<String> candidates(String type) {
			Set<String> out = new TreeSet<>();
			for (String t = type; t != null && types.containsKey(t); t = base(t)) {
				CsdlStructuredType level = types.get(t);
				level.properties().forEach(p -> out.add(p.name()));
				for (CsdlNavigationProperty n : level.navigationProperties()) {
					out.add(n.name());
					out.add(n.name() + BIND);
				}
			}
			return out;
		}

		private Optional<CsdlTypeRef> declared(String type, String property) {
			for (String t = type; t != null && types.containsKey(t); t = base(t)) {
				CsdlStructuredType level = types.get(t);
				for (CsdlProperty p : level.properties()) {
					if (p.name().equals(property)) {
						return Optional.of(p.type());
					}
				}
				for (CsdlNavigationProperty n : level.navigationProperties()) {
					if (n.name().equals(property)) {
						return Optional.of(n.type());
					}
				}
			}
			return Optional.empty();
		}

		private Optional<String> typeOf(String type, String property) {
			return declared(type, property).map(r -> simple(r.name()));
		}

		private boolean derives(String type, String root) {
			for (String t = type; t != null; t = base(t)) {
				if (t.equals(root)) {
					return true;
				}
			}
			return false;
		}

		private @Nullable String base(String type) {
			CsdlStructuredType t = types.get(type);
			return t == null || t.baseType() == null ? null : simple(t.baseType());
		}

		private boolean open(String type) {
			CsdlStructuredType t = types.get(type);
			return t != null && t.openType();
		}

		private static String typeName(String builder) {
			return builder.replaceAll("(Create|Patch)$", "");
		}

		private static String simple(String name) {
			return name.substring(name.lastIndexOf('.') + 1);
		}

	}

}
