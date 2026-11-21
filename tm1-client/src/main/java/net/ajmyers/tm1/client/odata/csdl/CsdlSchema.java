package net.ajmyers.tm1.client.odata.csdl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A CSDL schema: one namespace of types and operations.
 *
 * @param namespace the schema's namespace, e.g. {@code ibm.tm1.api.v1}
 * @param alias its alias, e.g. {@code tm1}, or {@code null}
 * @param entityTypes its entity types
 * @param complexTypes its complex types
 * @param enumTypes its enum types
 * @param operations its actions and functions, overloads included
 * @param entityContainer its entity container, or {@code null}
 * @param annotations annotations on the schema itself, e.g. {@code Core.SchemaVersion}
 * @param externalAnnotations annotations in {@code Annotations} elements, by their
 * {@code Target}
 */
public record CsdlSchema(String namespace, @Nullable String alias, List<CsdlEntityType> entityTypes,
		List<CsdlComplexType> complexTypes, List<CsdlEnumType> enumTypes, List<CsdlOperation> operations,
		@Nullable CsdlEntityContainer entityContainer, List<CsdlAnnotation> annotations,
		Map<String, List<CsdlAnnotation>> externalAnnotations) {

	public CsdlSchema {
		Objects.requireNonNull(namespace, "namespace");
		entityTypes = List.copyOf(entityTypes);
		complexTypes = List.copyOf(complexTypes);
		enumTypes = List.copyOf(enumTypes);
		operations = List.copyOf(operations);
		annotations = List.copyOf(annotations);
		Map<String, List<CsdlAnnotation>> copy = new LinkedHashMap<>();
		externalAnnotations.forEach((target, list) -> copy.put(target, List.copyOf(list)));
		externalAnnotations = Collections.unmodifiableMap(copy);
	}
}
