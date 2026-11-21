package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;

/** An entity type or a complex type: a named type with properties. */
public sealed interface CsdlStructuredType permits CsdlEntityType, CsdlComplexType {

	/** The type's name, unqualified. */
	String name();

	/** The qualified name of the type it derives from, as written, or {@code null}. */
	String baseType();

	/** Whether it's abstract. */
	boolean isAbstract();

	/** Whether it allows properties beyond those declared. */
	boolean openType();

	/** Its own structural properties, excluding inherited ones, in document order. */
	List<CsdlProperty> properties();

	/** Its own navigation properties, excluding inherited ones, in document order. */
	List<CsdlNavigationProperty> navigationProperties();

	/** Its annotations, in document order. */
	List<CsdlAnnotation> annotations();
}
