package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A navigation property: a link from one entity (or complex) type to an entity or a
 * collection of them, such as {@code Cube/Views}.
 *
 * @param name the property's name
 * @param type the target type
 * @param nullable whether a single-valued navigation may be null (default true)
 * @param partner the name of the navigation property going back, or {@code null}
 * @param containsTarget whether the targets are contained, so addressed through this
 * property
 * @param annotations its annotations, in document order
 */
public record CsdlNavigationProperty(String name, CsdlTypeRef type, boolean nullable, @Nullable String partner,
		boolean containsTarget, List<CsdlAnnotation> annotations) {

	/** Validates and copies the components. */
	public CsdlNavigationProperty {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		annotations = List.copyOf(annotations);
	}
}
