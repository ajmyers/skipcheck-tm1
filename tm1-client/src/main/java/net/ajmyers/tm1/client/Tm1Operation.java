package net.ajmyers.tm1.client;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.version.Tm1Capability;

/**
 * An action or function of the TM1 REST API, as {@code $metadata} declares it. The
 * generated {@code Tm1Operations} catalog has one per operation; services refer to them,
 * so a request can check that the server has the operation ({@link #capability()}) and
 * the coverage gate can tell which operations the client implements.
 *
 * @param kind whether it's an action or a function
 * @param name the operation's name, qualified as in {@code $metadata}, such as
 * {@code tm1.Activate}
 * @param bindingType the qualified type it's bound to, such as {@code tm1.Chore}, or
 * {@code null} for an operation invoked through an import from the service root
 * @param importName the import's name, such as {@code ExecuteMDX}, for an unbound
 * operation; {@code null} for a bound one
 * @param parameters the parameters a caller passes, without the binding parameter
 * @param returnType the return type as {@code $metadata} writes it, such as
 * {@code Collection(tm1.Cube)}, or {@code null} if it returns nothing
 * @param addedIn the schema version that added it, or {@code null} if not known
 * @param deprecatedIn the schema version that deprecated it, or {@code null}
 */
public record Tm1Operation(Kind kind, String name, @Nullable String bindingType, @Nullable String importName,
		List<Parameter> parameters, @Nullable String returnType, @Nullable String addedIn,
		@Nullable String deprecatedIn) {

	/** Whether an operation is an action or a function. */
	public enum Kind {

		/** An action, invoked with {@code POST}; it may change the server's state. */
		ACTION,

		/** A function, invoked with {@code GET}; it doesn't change anything. */
		FUNCTION

	}

	/**
	 * A parameter.
	 *
	 * @param name its name
	 * @param type its type as {@code $metadata} writes it, such as {@code Edm.String} or
	 * {@code Collection(tm1.Element)}
	 * @param nullable whether it may be null or left out
	 */
	public record Parameter(String name, String type, boolean nullable) {

		/** Checks the name and type aren't null. */
		public Parameter {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(type, "type");
		}

	}

	/**
	 * Checks that a bound operation has no import name and an unbound one has one.
	 * @throws IllegalArgumentException if it has both or neither
	 */
	public Tm1Operation {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(name, "name");
		parameters = List.copyOf(parameters);
		if ((bindingType == null) == (importName == null)) {
			throw new IllegalArgumentException(name + " needs exactly one of a binding type and an import name");
		}
	}

	/**
	 * Whether it's bound to a type, rather than invoked from the service root.
	 * @return true if it's bound
	 */
	public boolean bound() {
		return bindingType != null;
	}

	/**
	 * Whether {@code $metadata} marks it deprecated.
	 * @return true if it's deprecated
	 */
	public boolean deprecated() {
		return deprecatedIn != null;
	}

	/**
	 * The endpoint as the coverage gate names it: {@code Action Chore/Activate},
	 * {@code Function Cube/DimensionsStorageOrder}, {@code ActionImport ExecuteMDX}.
	 * @return the endpoint's name
	 */
	public String endpoint() {
		String kindName = kind == Kind.ACTION ? "Action" : "Function";
		return bindingType != null ? kindName + " " + unqualified(bindingType) + "/" + unqualified(name)
				: kindName + "Import " + importName;
	}

	private static String unqualified(String name) {
		return name.substring(name.lastIndexOf('.') + 1);
	}

	/**
	 * What a request using it needs the server to have, with the version that added it,
	 * for {@link Tm1Request#requires}.
	 * @return the capability
	 */
	public Tm1Capability capability() {
		Tm1Capability capability = bindingType != null ? Tm1Capability.boundOperation(bindingType, name)
				: Tm1Capability.unboundOperation(Objects.requireNonNull(importName));
		return addedIn == null ? capability : capability.since(addedIn);
	}

}
