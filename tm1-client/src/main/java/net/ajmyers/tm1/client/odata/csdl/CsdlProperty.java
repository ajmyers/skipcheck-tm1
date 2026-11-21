package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A structural property of an entity or complex type.
 *
 * @param name the property's name
 * @param type its type
 * @param nullable whether it may be null ({@code Nullable}, default true)
 * @param defaultValue its {@code DefaultValue}, or {@code null}
 * @param annotations its annotations, in document order
 */
public record CsdlProperty(String name, CsdlTypeRef type, boolean nullable, @Nullable String defaultValue,
		List<CsdlAnnotation> annotations) {

	/** Validates and copies the components. */
	public CsdlProperty {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		annotations = List.copyOf(annotations);
	}
}
