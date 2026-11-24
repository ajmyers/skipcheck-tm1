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
	 */
	public record Property(String type, String name, @Nullable String edmType, String note) {
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
	 * Reads the undeclared properties from a probe file.
	 * @param probeJson the probe file's content
	 * @param overlay the overlay, for types
	 * @param metadata the {@code $metadata} they're undeclared in
	 * @return the properties
	 * @throws IllegalArgumentException if the overlay types a property the probe didn't
	 * find, or the probe found one {@code $metadata} declares
	 */
	public static Undeclared read(String probeJson, Overlay overlay, CsdlDocument metadata) {
		JsonNode root = JSON.readTree(probeJson);
		String version = root.path("server").path("schemaVersion").asString("");
		Map<String, Property> properties = new LinkedHashMap<>();
		Set<String> typed = new TreeSet<>();
		for (Map.Entry<String, JsonNode> type : root.path("undeclared").properties()) {
			for (Map.Entry<String, JsonNode> property : type.getValue().properties()) {
				String key = type.getKey() + "/" + property.getKey();
				String kind = property.getValue().path("json").asString("");
				if (kind.equals("navigation")) {
					continue;
				}
				if (declared(metadata, type.getKey(), property.getKey())) {
					throw new IllegalArgumentException(
							key + " is in the probe file but $metadata declares it: run ./gradlew probeTm1 again");
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
				StringBuilder note = new StringBuilder(
						"TM1 sends it, though $metadata doesn't declare it: found by probing TM1 schema ")
					.append(version)
					.append('.');
				if (given != null && given.note() != null) {
					note.append(' ').append(given.note());
				}
				properties.put(key, new Property(type.getKey(), property.getKey(), edm, note.toString()));
			}
		}
		Set<String> unknown = new TreeSet<>(overlay.undeclared().keySet());
		unknown.removeAll(typed);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException(
					"The overlay's undeclared section names properties the probe didn't find: " + unknown);
		}
		return new Undeclared(version, properties);
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
		return new CsdlDocument(metadata.version(), schemas);
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
		return new Overlay(departures, overlay.lenientDates(), overlay.endpoints(), overlay.undeclared());
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
