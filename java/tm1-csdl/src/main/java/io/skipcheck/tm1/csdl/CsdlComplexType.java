// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
public record CsdlComplexType(String name, @Nullable String baseType, boolean isAbstract, boolean openType,
		List<CsdlProperty> properties, List<CsdlNavigationProperty> navigationProperties,
		List<CsdlAnnotation> annotations) implements CsdlStructuredType {

	/** Validates and copies the components. */
	public CsdlComplexType {
		Objects.requireNonNull(name, "name");
		properties = List.copyOf(properties);
		navigationProperties = List.copyOf(navigationProperties);
		annotations = List.copyOf(annotations);
	}
}
