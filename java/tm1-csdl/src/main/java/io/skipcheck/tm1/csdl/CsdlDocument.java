// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A parsed CSDL document ({@code $metadata}): its schemas, and lookups across them.
 *
 * <p>
 * Lookups take a qualified name using either the namespace or the alias, so
 * {@code ibm.tm1.api.v1.Cube} and {@code tm1.Cube} find the same type.
 *
 * @param version the {@code edmx:Edmx} {@code Version}, e.g. {@code 4.0}
 * @param schemas the schemas, in document order
 * @param references the {@code edmx:Reference} elements, in document order
 */
public record CsdlDocument(String version, List<CsdlSchema> schemas, List<CsdlReference> references) {

	/** The term holding the service's schema version. */
	public static final String SCHEMA_VERSION_TERM = "Core.SchemaVersion";

	/** Validates and copies the components. */
	public CsdlDocument {
		Objects.requireNonNull(version, "version");
		schemas = List.copyOf(schemas);
		references = List.copyOf(references);
	}

	/**
	 * Creates a document with no {@code edmx:Reference} elements.
	 * @param version the {@code edmx:Edmx} {@code Version}, e.g. {@code 4.0}
	 * @param schemas the schemas, in document order
	 */
	public CsdlDocument(String version, List<CsdlSchema> schemas) {
		this(version, schemas, List.of());
	}

	/**
	 * Rewrites a qualified name to use its schema's namespace rather than its alias:
	 * {@code tm1.Cube} becomes {@code ibm.tm1.api.v1.Cube}. Also resolves aliases from
	 * {@code edmx:Reference}/{@code edmx:Include} elements. Names in other namespaces,
	 * such as {@code Edm.String}, are returned unchanged.
	 * @param qualifiedName a name qualified by namespace or alias
	 * @return the name qualified by namespace
	 */
	public String qualify(String qualifiedName) {
		Objects.requireNonNull(qualifiedName, "qualifiedName");
		int dot = qualifiedName.lastIndexOf('.');
		if (dot < 0) {
			return qualifiedName;
		}
		String prefix = qualifiedName.substring(0, dot);
		for (CsdlSchema schema : schemas) {
			if (prefix.equals(schema.alias())) {
				return schema.namespace() + qualifiedName.substring(dot);
			}
		}
		for (CsdlReference reference : references) {
			for (CsdlReference.Include include : reference.includes()) {
				if (prefix.equals(include.alias())) {
					return include.namespace() + qualifiedName.substring(dot);
				}
			}
		}
		return qualifiedName;
	}

	/**
	 * The schema version from {@code Core.SchemaVersion}, e.g. {@code 11.8.35}, if
	 * declared.
	 * @return the schema version, or empty if no schema declares one
	 */
	public Optional<String> schemaVersion() {
		return schemas.stream()
			.flatMap(s -> CsdlAnnotation.find(s.annotations(), SCHEMA_VERSION_TERM).stream())
			.flatMap(a -> a.value().asText().stream())
			.findFirst();
	}

	/**
	 * The document's entity container, if any.
	 * @return the first schema's entity container, or empty if none has one
	 */
	public Optional<CsdlEntityContainer> entityContainer() {
		return schemas.stream().map(CsdlSchema::entityContainer).filter(Objects::nonNull).findFirst();
	}

	/**
	 * The entity type with this qualified name.
	 * @param qualifiedName the type's name, qualified by namespace or alias
	 * @return the entity type, or empty if the document doesn't define it
	 */
	public Optional<CsdlEntityType> entityType(String qualifiedName) {
		return find(qualifiedName, CsdlSchema::entityTypes, CsdlEntityType::name);
	}

	/**
	 * The complex type with this qualified name.
	 * @param qualifiedName the type's name, qualified by namespace or alias
	 * @return the complex type, or empty if the document doesn't define it
	 */
	public Optional<CsdlComplexType> complexType(String qualifiedName) {
		return find(qualifiedName, CsdlSchema::complexTypes, CsdlComplexType::name);
	}

	/**
	 * The entity or complex type with this qualified name.
	 * @param qualifiedName the type's name, qualified by namespace or alias
	 * @return the type, or empty if the document doesn't define it
	 */
	public Optional<CsdlStructuredType> structuredType(String qualifiedName) {
		Optional<CsdlStructuredType> entity = entityType(qualifiedName).map(CsdlStructuredType.class::cast);
		return entity.isPresent() ? entity : complexType(qualifiedName).map(CsdlStructuredType.class::cast);
	}

	/**
	 * The enum type with this qualified name.
	 * @param qualifiedName the type's name, qualified by namespace or alias
	 * @return the enum type, or empty if the document doesn't define it
	 */
	public Optional<CsdlEnumType> enumType(String qualifiedName) {
		return find(qualifiedName, CsdlSchema::enumTypes, CsdlEnumType::name);
	}

	/**
	 * Every overload of the action or function with this qualified name.
	 * @param qualifiedName the operation's name, qualified by namespace or alias
	 * @return the overloads in document order, or an empty list if there are none
	 */
	public List<CsdlOperation> operations(String qualifiedName) {
		String name = qualify(qualifiedName);
		List<CsdlOperation> result = new ArrayList<>();
		for (CsdlSchema schema : schemas) {
			for (CsdlOperation op : schema.operations()) {
				if ((schema.namespace() + "." + op.name()).equals(name)) {
					result.add(op);
				}
			}
		}
		return result;
	}

	/**
	 * The operations bound to this type (not collections of it), e.g. every
	 * {@code tm1.Cube} action.
	 * @param qualifiedTypeName the binding type's name, qualified by namespace or alias
	 * @return the bound operations in document order, or an empty list if there are none
	 */
	public List<CsdlOperation> boundTo(String qualifiedTypeName) {
		String type = qualify(qualifiedTypeName);
		return schemas.stream()
			.flatMap(s -> s.operations().stream())
			.filter(op -> op.bindingType().map(t -> !t.collection() && qualify(t.name()).equals(type)).orElse(false))
			.toList();
	}

	/**
	 * A structured type followed by its base types, nearest first. Stops at a base type
	 * the document doesn't define, and at a cycle (which valid CSDL never has).
	 * @param qualifiedName the type's name, qualified by namespace or alias
	 * @return the type and its base types, or an empty list if the document doesn't
	 * define the type
	 */
	public List<CsdlStructuredType> typeHierarchy(String qualifiedName) {
		List<CsdlStructuredType> chain = new ArrayList<>();
		Optional<CsdlStructuredType> current = structuredType(qualifiedName);
		while (current.isPresent() && !chain.contains(current.get())) {
			chain.add(current.get());
			String base = current.get().baseType();
			current = base == null ? Optional.empty() : structuredType(base);
		}
		return chain;
	}

	/**
	 * The qualified name, by namespace, of a type defined in one of this document's
	 * schemas.
	 * @param type the type to name
	 * @return its name qualified by namespace, or empty if it isn't in this document
	 */
	public Optional<String> qualifiedName(CsdlStructuredType type) {
		return schemas.stream()
			.filter(s -> s.entityTypes().contains(type) || s.complexTypes().contains(type))
			.map(s -> s.namespace() + "." + type.name())
			.findFirst();
	}

	private <T> Optional<T> find(String qualifiedName, Function<CsdlSchema, List<T>> members,
			Function<T, String> nameOf) {
		String name = qualify(qualifiedName);
		return schemas.stream()
			.flatMap(s -> members.apply(s).stream().filter(t -> (s.namespace() + "." + nameOf.apply(t)).equals(name)))
			.findFirst();
	}

	/**
	 * Every structured type in the document.
	 * @return the entity and complex types, schema by schema, entity types first
	 */
	public Stream<CsdlStructuredType> structuredTypes() {
		return schemas.stream().flatMap(s -> Stream.concat(s.entityTypes().stream(), s.complexTypes().stream()));
	}
}
