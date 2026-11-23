package io.skipcheck.tm1.csdl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * An action or a function. Actions may change data and are invoked with POST; functions
 * don't, and are invoked with GET. A bound operation's first parameter is its binding
 * parameter: the type it's invoked on, such as {@code tm1.Cube} for
 * {@code Cubes('x')/tm1.Reserve}.
 *
 * @param kind action or function
 * @param name the operation's name, unqualified
 * @param bound whether it's bound
 * @param composable whether further path segments may follow a function
 * @param entitySetPath its {@code EntitySetPath}, or {@code null}
 * @param parameters its parameters, binding parameter first when bound
 * @param returnType its return type, or {@code null} if it returns nothing
 * @param returnNullable whether the return value may be null
 * @param annotations its annotations
 */
public record CsdlOperation(Kind kind, String name, boolean bound, boolean composable, @Nullable String entitySetPath,
		List<CsdlParameter> parameters, @Nullable CsdlTypeRef returnType, boolean returnNullable,
		List<CsdlAnnotation> annotations) {

	/** Action or function. */
	public enum Kind {

		/** An action: may change data, invoked with POST. */
		ACTION,

		/** A function: doesn't change data, invoked with GET. */
		FUNCTION

	}

	/**
	 * Validates and copies the components.
	 * @throws IllegalArgumentException if it's bound but has no parameters
	 */
	public CsdlOperation {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(name, "name");
		parameters = List.copyOf(parameters);
		annotations = List.copyOf(annotations);
		if (bound && parameters.isEmpty()) {
			throw new IllegalArgumentException("Bound operation " + name + " has no binding parameter");
		}
	}

	/**
	 * The binding parameter's type, for a bound operation.
	 * @return the first parameter's type, or empty if the operation is unbound
	 */
	public Optional<CsdlTypeRef> bindingType() {
		return bound ? Optional.of(parameters.get(0).type()) : Optional.empty();
	}

	/**
	 * The parameters passed by the caller: all of them, minus the binding parameter.
	 * @return the caller's parameters, in document order
	 */
	public List<CsdlParameter> callerParameters() {
		return bound ? parameters.subList(1, parameters.size()) : parameters;
	}
}
