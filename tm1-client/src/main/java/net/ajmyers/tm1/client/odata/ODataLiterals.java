package net.ajmyers.tm1.client.odata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * OData literals, as they appear in keys, function parameters and {@code $filter}, before
 * percent-encoding.
 */
public final class ODataLiterals {

	private ODataLiterals() {
	}

	/**
	 * A string literal: single-quoted, with single quotes doubled ({@code 'O''Neil'}).
	 */
	public static String string(String value) {
		Objects.requireNonNull(value, "value");
		return "'" + value.replace("'", "''") + "'";
	}

	/**
	 * An enumeration literal in OData 4.0 form, with its type:
	 * {@code ibm.tm1.api.v1.ElementType'Numeric'}.
	 */
	public static String enumMember(String qualifiedType, String member) {
		Objects.requireNonNull(qualifiedType, "qualifiedType");
		return qualifiedType + string(member);
	}

	/**
	 * A literal for a Java value: strings are quoted, numbers and booleans written as is,
	 * {@code null} as {@code null}. Use {@link Raw} for an already-formed literal, such
	 * as an enum member or a date.
	 * @throws IllegalArgumentException for types with no unambiguous literal form
	 */
	public static String of(@Nullable Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Raw raw) {
			return raw.literal();
		}
		if (value instanceof CharSequence text) {
			return string(text.toString());
		}
		if (value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof BigInteger) {
			return value.toString();
		}
		if (value instanceof Double || value instanceof Float) {
			double d = ((Number) value).doubleValue();
			if (Double.isNaN(d)) {
				return "NaN";
			}
			if (Double.isInfinite(d)) {
				return d > 0 ? "INF" : "-INF";
			}
			return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
		}
		throw new IllegalArgumentException("No OData literal for " + value.getClass().getName()
				+ "; pass ODataLiterals.Raw for a preformed literal");
	}

	/**
	 * A literal written exactly as given, such as
	 * {@code ibm.tm1.api.v1.ElementType'Numeric'} or {@code 2026-11-21}.
	 */
	public record Raw(String literal) {
		public Raw {
			Objects.requireNonNull(literal, "literal");
		}
	}

}
