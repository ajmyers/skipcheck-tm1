// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlAnnotation;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlSchema;
import io.skipcheck.tm1.csdl.CsdlTypeRef;
import io.skipcheck.tm1.csdl.CsdlValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The properties TM1 sends that its {@code $metadata} doesn't declare, from the probe's
 * file beside the snapshot ({@code tm1-probe-<schema>.json}, written by
 * {@code ./gradlew probeTm1}), typed for the generated models.
 *
 * <p>
 * A property's type comes from the overlay's {@code undeclared} section when it gives
 * one, else from the kind of value the probe read ({@code string}, {@code boolean},
 * {@code number}). A property still untyped, such as one the probe read as {@code null},
 * is read as JSON, the way the overlay's {@code departures} are. Navigation properties
 * are left out: they'd add endpoints to the coverage inventory.
 *
 * @param schemaVersion the schema version the probe ran on
 * @param properties by {@code Type/Property}, sorted
 */
public record Undeclared(String schemaVersion, Map<String, Property> properties) {

	/**
	 * One undeclared property.
	 *
	 * @param type the entity type's simple name
	 * @param name the property's name
	 * @param edmType its type, such as {@code Edm.String}, or null when it isn't known
	 * and it's read as JSON
	 * @param note what's known of it, in Markdown
	 * @param observedIn the schema versions whose probes found it undeclared
	 */
	public record Property(String type, String name, @Nullable String edmType, String note, List<String> observedIn) {

		/** Copies the list. */
		public Property {
			observedIn = List.copyOf(observedIn);
		}

	}

	/**
	 * One probe file paired with the {@code $metadata} snapshot of its own schema version
	 * (used to catch stale probes whose properties are already declared in that version).
	 *
	 * @param json the probe file's JSON content
	 * @param snapshot the probe's own {@code $metadata} snapshot, or {@code null}
	 */
	public record Probe(String json, @Nullable CsdlDocument snapshot) {
	}

	/** Copies the map, sorted. */
	public Undeclared {
		properties = Collections.unmodifiableMap(new TreeMap<>(properties));
	}

	/** What an untyped undeclared property's departure says. */
	static final String READ_AS_JSON = "Its type isn't known, so it's read as JSON.";

	/** None. */
	public static final Undeclared NONE = new Undeclared("", Map.of());

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Reads the undeclared properties from a single probe file.
	 * @param probeJson the probe file's content
	 * @param overlay the overlay, for types
	 * @param metadata the {@code $metadata} they're undeclared in
	 * @return the properties
	 * @throws IllegalArgumentException if the overlay types a property the probe didn't
	 * find, or the probe found one {@code $metadata} declares
	 */
	public static Undeclared read(String probeJson, Overlay overlay, CsdlDocument metadata) {
		return read(List.of(new Probe(probeJson, metadata)), overlay, metadata);
	}

	/**
	 * Reads and merges undeclared properties across multiple probe files against a
	 * (possibly multi-version merged) {@code $metadata} document.
	 *
	 * <p>
	 * Each probe is checked against its own snapshot so stale probes still fail. A
	 * property that was undeclared in an older version's probe but is declared in
	 * {@code mergedMetadata} (by a newer merged snapshot) is skipped in favor of the
	 * newer snapshot's CSDL declaration.
	 * @param probes the probe files, oldest-first
	 * @param overlay the overlay, for types
	 * @param mergedMetadata the merged {@code $metadata} document
	 * @return the merged undeclared properties
	 * @throws IllegalArgumentException if the overlay types a property no probe found (or
	 * that is already declared), or a probe names a property its own snapshot declares
	 */
	public static Undeclared read(List<Probe> probes, Overlay overlay, CsdlDocument mergedMetadata) {
		if (probes.isEmpty()) {
			return NONE;
		}
		record Acc(String type, String name, @Nullable String edmType, @Nullable String overlayNote,
				List<String> versions) {
		}
		Map<String, Acc> acc = new LinkedHashMap<>();
		Set<String> typed = new TreeSet<>();
		String latestVersion = "";
		for (Probe probe : probes) {
			JsonNode root = JSON.readTree(probe.json());
			String version = root.path("server").path("schemaVersion").asString("");
			if (!version.isEmpty()) {
				latestVersion = version;
			}
			for (Map.Entry<String, JsonNode> type : root.path("undeclared").properties()) {
				for (Map.Entry<String, JsonNode> property : type.getValue().properties()) {
					String key = type.getKey() + "/" + property.getKey();
					String kind = property.getValue().path("json").asString("");
					if (kind.equals("navigation")) {
						continue;
					}
					if (probe.snapshot() != null && declared(probe.snapshot(), type.getKey(), property.getKey())) {
						throw new IllegalArgumentException(
								key + " is in the probe file but $metadata declares it: run ./gradlew probeTm1 again");
					}
					if (declared(mergedMetadata, type.getKey(), property.getKey())) {
						// Declared by another merged snapshot (e.g. a newer TM1 version):
						// use the real CSDL declaration instead of an undeclared
						// departure
						continue;
					}
					Overlay.UndeclaredType given = overlay.undeclared().get(key);
					String edm = given != null ? given.type() : switch (kind) {
						case "string" -> "Edm.String";
						case "boolean" -> "Edm.Boolean";
						case "number" -> "Edm.Double";
						default -> null;
					};
					if (given != null) {
						typed.add(key);
					}
					Acc existing = acc.get(key);
					if (existing == null) {
						List<String> versions = new ArrayList<>();
						if (!version.isEmpty()) {
							versions.add(version);
						}
						acc.put(key, new Acc(type.getKey(), property.getKey(), edm, given != null ? given.note() : null,
								versions));
					}
					else {
						if (!version.isEmpty() && !existing.versions().contains(version)) {
							existing.versions().add(version);
						}
						if (existing.edmType() == null && edm != null) {
							acc.put(key, new Acc(existing.type(), existing.name(), edm, existing.overlayNote(),
									existing.versions()));
						}
					}
				}
			}
		}
		Set<String> unknown = new TreeSet<>(overlay.undeclared().keySet());
		unknown.removeAll(typed);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException(
					"The overlay's undeclared section names properties the probe didn't find: " + unknown);
		}
		Map<String, Property> properties = new LinkedHashMap<>();
		for (Map.Entry<String, Acc> entry : acc.entrySet()) {
			Acc a = entry.getValue();
			StringBuilder note = new StringBuilder(
					"TM1 sends it, though $metadata doesn't declare it: found by probing TM1 schema ")
				.append(String.join(", ", a.versions()))
				.append('.');
			if (a.overlayNote() != null) {
				note.append(' ').append(a.overlayNote());
			}
			properties.put(entry.getKey(),
					new Property(a.type(), a.name(), a.edmType(), note.toString(), a.versions()));
		}
		return new Undeclared(latestVersion, properties);
	}

	private static boolean declared(CsdlDocument metadata, String type, String property) {
		return metadata.structuredTypes()
			.filter(t -> t.name().equals(type))
			.flatMap(t -> metadata.typeHierarchy(metadata.qualifiedName(t).orElse(t.name())).stream())
			.flatMap(t -> t.properties().stream().map(CsdlProperty::name))
			.anyMatch(property::equals);
	}

	/**
	 * The document with the undeclared properties added to their types, after the
	 * declared ones, by name, each described by its note ({@code Core.Description}). One
	 * typed as JSON is declared {@code Edm.String}: the overlay {@link #applyTo(Overlay)
	 * departure} makes it JSON.
	 * @param metadata the document
	 * @return a new document
	 */
	public CsdlDocument applyTo(CsdlDocument metadata) {
		if (properties.isEmpty()) {
			return metadata;
		}
		List<CsdlSchema> schemas = new ArrayList<>();
		for (CsdlSchema s : metadata.schemas()) {
			List<CsdlEntityType> types = new ArrayList<>();
			for (CsdlEntityType t : s.entityTypes()) {
				List<CsdlProperty> added = new ArrayList<>(t.properties());
				properties.values()
					.stream()
					.filter(p -> p.type().equals(t.name()))
					.forEach(p -> added.add(new CsdlProperty(p.name(),
							CsdlTypeRef.parse(p.edmType() == null ? "Edm.String" : p.edmType()), true, null,
							List.of(new CsdlAnnotation("Core.Description", null,
									new CsdlValue.Constant("String", p.note()))))));
				types.add(added.size() == t.properties().size() ? t
						: new CsdlEntityType(t.name(), t.baseType(), t.isAbstract(), t.openType(), t.hasStream(),
								t.key(), added, t.navigationProperties(), t.annotations()));
			}
			schemas.add(new CsdlSchema(s.namespace(), s.alias(), types, s.complexTypes(), s.enumTypes(), s.operations(),
					s.entityContainer(), s.annotations(), s.externalAnnotations()));
		}
		return new CsdlDocument(metadata.version(), schemas, metadata.references());
	}

	/**
	 * The overlay with the untyped undeclared properties added to its departures, so
	 * they're read as JSON.
	 * @param overlay the overlay
	 * @return a new overlay
	 */
	public Overlay applyTo(Overlay overlay) {
		Map<String, String> departures = new LinkedHashMap<>(overlay.departures());
		properties.forEach((key, p) -> {
			if (p.edmType() == null) {
				departures.put(key, READ_AS_JSON);
			}
		});
		return new Overlay(departures, overlay.lenientDates(), overlay.endpoints(), overlay.undeclared(),
				overlay.writeOnly(), overlay.writes());
	}

	/**
	 * One of these properties, if it is one.
	 * @param type the type's simple name
	 * @param property the property's name
	 * @return the property, or null
	 */
	public @Nullable Property get(String type, String property) {
		return properties.get(type + "/" + property);
	}

}
