// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.List;

/** An entity type or a complex type: a named type with properties. */
public sealed interface CsdlStructuredType permits CsdlEntityType, CsdlComplexType {

	/**
	 * The type's name, unqualified.
	 * @return the unqualified name
	 */
	String name();

	/**
	 * The qualified name of the type it derives from, as written, or {@code null}.
	 * @return the base type's qualified name, or {@code null} if it has none
	 */
	String baseType();

	/**
	 * Whether it's abstract.
	 * @return true if it's abstract
	 */
	boolean isAbstract();

	/**
	 * Whether it allows properties beyond those declared.
	 * @return true if it's an open type
	 */
	boolean openType();

	/**
	 * Its own structural properties, excluding inherited ones, in document order.
	 * @return the declared structural properties
	 */
	List<CsdlProperty> properties();

	/**
	 * Its own navigation properties, excluding inherited ones, in document order.
	 * @return the declared navigation properties
	 */
	List<CsdlNavigationProperty> navigationProperties();

	/**
	 * Its annotations, in document order.
	 * @return the annotations
	 */
	List<CsdlAnnotation> annotations();

}
