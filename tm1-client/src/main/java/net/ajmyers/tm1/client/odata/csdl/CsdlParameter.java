package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

/**
 * A parameter of an action or function.
 *
 * @param name the parameter's name
 * @param type its type
 * @param nullable whether it may be null (default true)
 * @param annotations its annotations, e.g. {@code Core.OptionalParameter}
 */
public record CsdlParameter(String name, CsdlTypeRef type, boolean nullable, List<CsdlAnnotation> annotations) {

	public CsdlParameter {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		annotations = List.copyOf(annotations);
	}
}
