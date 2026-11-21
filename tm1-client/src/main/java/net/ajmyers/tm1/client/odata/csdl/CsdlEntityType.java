package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

/**
 * An entity type: a structured type with a key, such as {@code Cube}.
 *
 * @param name the type's name, unqualified
 * @param baseType the qualified name of its base type, or {@code null}
 * @param isAbstract whether it's abstract
 * @param openType whether it's open
 * @param hasStream whether it's a media entity
 * @param key the names of its key properties; empty for a derived type, which inherits
 * its key
 * @param properties its own structural properties
 * @param navigationProperties its own navigation properties
 * @param annotations its annotations
 */
public record CsdlEntityType(String name, String baseType, boolean isAbstract, boolean openType, boolean hasStream,
		List<String> key, List<CsdlProperty> properties, List<CsdlNavigationProperty> navigationProperties,
		List<CsdlAnnotation> annotations) implements CsdlStructuredType {

	public CsdlEntityType {
		Objects.requireNonNull(name, "name");
		key = List.copyOf(key);
		properties = List.copyOf(properties);
		navigationProperties = List.copyOf(navigationProperties);
		annotations = List.copyOf(annotations);
	}
}
