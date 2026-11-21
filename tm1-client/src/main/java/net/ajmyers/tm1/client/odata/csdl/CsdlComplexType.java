package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

/**
 * A complex type: a structured type without a key, such as {@code ProcessParameter}.
 *
 * @param name the type's name, unqualified
 * @param baseType the qualified name of its base type, or {@code null}
 * @param isAbstract whether it's abstract
 * @param openType whether it's open
 * @param properties its own structural properties
 * @param navigationProperties its own navigation properties
 * @param annotations its annotations
 */
public record CsdlComplexType(String name, String baseType, boolean isAbstract, boolean openType,
		List<CsdlProperty> properties, List<CsdlNavigationProperty> navigationProperties,
		List<CsdlAnnotation> annotations) implements CsdlStructuredType {

	public CsdlComplexType {
		Objects.requireNonNull(name, "name");
		properties = List.copyOf(properties);
		navigationProperties = List.copyOf(navigationProperties);
		annotations = List.copyOf(annotations);
	}
}
