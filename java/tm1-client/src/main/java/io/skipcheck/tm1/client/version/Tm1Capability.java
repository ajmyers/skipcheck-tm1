// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Something a request needs the server to define in its {@code $metadata}: an entity set,
 * a singleton, an operation or a property. A request can declare one, and the session
 * checks it against the connected server before sending anything.
 *
 * {@snippet class = examples.Tm1CapabilitiesExamples region = supports}
 *
 * <p>
 * Names are qualified by namespace or alias ({@code tm1.Cube} or
 * {@code ibm.tm1.api.v1.Cube}), as in {@code $metadata}. {@link #since} records the
 * schema version that added it, from {@code Core.Revisions}, so a server that lacks it
 * can be told why; the generated model fills it in.
 *
 * @param kind what kind of element this is
 * @param type the qualified type it belongs to (a bound operation's binding type, a
 * property's structured type), or {@code null}
 * @param name the element's name: an entity set or singleton name, a qualified operation
 * name, or a property name
 * @param addedIn the schema version that added it, or {@code null} if not known
 */
public record Tm1Capability(Kind kind, @Nullable String type, String name, @Nullable String addedIn) {

	/** The kinds of element a request can need. */
	public enum Kind {

		/** An entity set in the entity container, such as {@code GitPlans}. */
		ENTITY_SET,

		/** A singleton in the entity container, such as {@code ActiveSession}. */
		SINGLETON,

		/**
		 * An action or function bound to a type, such as {@code tm1.Cube/tm1.Reserve}.
		 */
		BOUND_OPERATION,

		/**
		 * An action or function import, invoked from the service root, such as
		 * {@code ExecuteMDX}.
		 */
		UNBOUND_OPERATION,

		/**
		 * A structural or navigation property of a type, such as {@code tm1.Cube/Rules}.
		 */
		PROPERTY

	}

	/**
	 * Checks the components fit together.
	 * @throws IllegalArgumentException if a bound operation or property has no type
	 */
	public Tm1Capability {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(name, "name");
		if ((kind == Kind.BOUND_OPERATION || kind == Kind.PROPERTY) && type == null) {
			throw new IllegalArgumentException(kind + " needs the type it belongs to");
		}
	}

	/**
	 * An entity set.
	 * @param name its name, such as {@code GitPlans}
	 * @return the capability
	 */
	public static Tm1Capability entitySet(String name) {
		return new Tm1Capability(Kind.ENTITY_SET, null, name, null);
	}

	/**
	 * A singleton.
	 * @param name its name, such as {@code ActiveSession}
	 * @return the capability
	 */
	public static Tm1Capability singleton(String name) {
		return new Tm1Capability(Kind.SINGLETON, null, name, null);
	}

	/**
	 * An action or function bound to a type, or to one of its base types.
	 * @param bindingType the qualified type, such as {@code tm1.Cube}
	 * @param operation the qualified operation name, such as {@code tm1.Reserve}
	 * @return the capability
	 */
	public static Tm1Capability boundOperation(String bindingType, String operation) {
		return new Tm1Capability(Kind.BOUND_OPERATION, Objects.requireNonNull(bindingType, "bindingType"), operation,
				null);
	}

	/**
	 * An action or function import, invoked from the service root.
	 * @param name the import's name, such as {@code ExecuteMDX}
	 * @return the capability
	 */
	public static Tm1Capability unboundOperation(String name) {
		return new Tm1Capability(Kind.UNBOUND_OPERATION, null, name, null);
	}

	/**
	 * A structural or navigation property of a type, or of one of its base types.
	 * @param type the qualified type, such as {@code tm1.Cube}
	 * @param property the property's name, such as {@code Rules}
	 * @return the capability
	 */
	public static Tm1Capability property(String type, String property) {
		return new Tm1Capability(Kind.PROPERTY, Objects.requireNonNull(type, "type"), property, null);
	}

	/**
	 * A copy recording the schema version that added it.
	 * @param schemaVersion the version from {@code Core.Revisions}, such as
	 * {@code 11.8.34}
	 * @return the copy
	 */
	public Tm1Capability since(String schemaVersion) {
		return new Tm1Capability(kind, type, name, Objects.requireNonNull(schemaVersion, "schemaVersion"));
	}

	/**
	 * The schema version that added it.
	 * @return the version, or empty if not known
	 */
	public Optional<String> addedInVersion() {
		return Optional.ofNullable(addedIn);
	}

	/**
	 * How it reads in messages, with aliases and namespaces dropped:
	 * {@code Cube.Reserve}, {@code entity set GitPlans}.
	 * @return the description
	 */
	public String describe() {
		return switch (kind) {
			case ENTITY_SET -> "entity set " + name;
			case SINGLETON -> "singleton " + name;
			case UNBOUND_OPERATION -> "operation " + name;
			case BOUND_OPERATION, PROPERTY -> simple(Objects.requireNonNull(type)) + "." + simple(name);
		};
	}

	private static String simple(String qualified) {
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

}
