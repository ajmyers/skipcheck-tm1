// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import java.util.Objects;

/**
 * A reference to a type, as written in a {@code Type} attribute: {@code Edm.String},
 * {@code tm1.Element}, or {@code Collection(tm1.Element)}.
 *
 * @param name the type's qualified name, without {@code Collection(...)}
 * @param collection whether it's a collection of that type
 */
public record CsdlTypeRef(String name, boolean collection) {

	private static final String COLLECTION = "Collection(";

	/** Validates the components. */
	public CsdlTypeRef {
		Objects.requireNonNull(name, "name");
	}

	/**
	 * Parses a {@code Type} attribute.
	 * @param text the attribute's value, e.g. {@code Collection(tm1.Element)};
	 * surrounding whitespace is ignored
	 * @return the type reference
	 */
	public static CsdlTypeRef parse(String text) {
		Objects.requireNonNull(text, "text");
		String t = text.strip();
		if (t.startsWith(COLLECTION) && t.endsWith(")")) {
			return new CsdlTypeRef(t.substring(COLLECTION.length(), t.length() - 1).strip(), true);
		}
		return new CsdlTypeRef(t, false);
	}

	/**
	 * True for the built-in {@code Edm.*} types.
	 * @return true if the type's name starts with {@code Edm.}
	 */
	public boolean isPrimitive() {
		return name.startsWith("Edm.");
	}

	/** The type as written in CSDL, e.g. {@code Collection(tm1.Element)}. */
	@Override
	public String toString() {
		return collection ? COLLECTION + name + ")" : name;
	}
}
