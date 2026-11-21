package net.ajmyers.tm1.csdl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The value of a CSDL annotation: a constant, a collection or a record. Expression kinds
 * the client doesn't interpret (paths, {@code If}, {@code Apply} and so on) are kept as
 * {@link Other}, with their element name.
 */
public sealed interface CsdlValue {

	/**
	 * A {@code String}, {@code Bool}, {@code Int}, {@code Decimal}, {@code EnumMember} or
	 * other constant, as written.
	 *
	 * @param kind the constant's kind, e.g. {@code String} or {@code Bool}
	 * @param text its value as written
	 */
	record Constant(String kind, String text) implements CsdlValue {
	}

	/**
	 * A {@code Collection} of values, in document order.
	 *
	 * @param items the values, in document order
	 */
	record Collection(List<CsdlValue> items) implements CsdlValue {
		/** Copies the items. */
		public Collection {
			items = List.copyOf(items);
		}
	}

	/**
	 * A {@code Record}: property values by name, in document order.
	 *
	 * @param properties the property values by name, in document order
	 */
	// Named after the CSDL Record expression; inside CsdlValue it shadows
	// java.lang.Record, which is fine
	@SuppressWarnings("AvoidCommonTypeNames")
	record Record(Map<String, CsdlValue> properties) implements CsdlValue {
		/** Copies the properties, keeping their order. */
		public Record {
			properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
		}
	}

	/**
	 * An annotation with no value, such as
	 * {@code <Annotation Term="Core.ConventionalIDs"/>}, which means true for a Boolean
	 * term.
	 */
	record Empty() implements CsdlValue {
	}

	/**
	 * An expression the client doesn't interpret, kept by element name.
	 *
	 * @param kind the expression's element name, e.g. {@code If}
	 */
	record Other(String kind) implements CsdlValue {
	}

	/**
	 * This value's text if it's a constant.
	 * @return the constant's text, or empty if this isn't a {@link Constant}
	 */
	default Optional<String> asText() {
		return this instanceof Constant constant ? Optional.of(constant.text()) : Optional.empty();
	}

}
