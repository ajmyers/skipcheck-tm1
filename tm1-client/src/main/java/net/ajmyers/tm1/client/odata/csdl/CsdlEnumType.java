package net.ajmyers.tm1.client.odata.csdl;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * An enumeration type, such as {@code ElementType}.
 *
 * @param name the type's name, unqualified
 * @param underlyingType its underlying integer type (default {@code Edm.Int32})
 * @param flags whether members combine as bit flags
 * @param members its members, in document order (which must be kept)
 * @param annotations its annotations
 */
public record CsdlEnumType(String name, String underlyingType, boolean flags, List<Member> members,
		List<CsdlAnnotation> annotations) {

	/** Validates and copies the components. */
	public CsdlEnumType {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(underlyingType, "underlyingType");
		members = List.copyOf(members);
		annotations = List.copyOf(annotations);
	}

	/**
	 * A member of an enumeration.
	 *
	 * @param name the member's name
	 * @param value its value, or {@code null} if not given (then its position, for
	 * non-flags enums)
	 * @param annotations its annotations
	 */
	public record Member(String name, @Nullable Long value, List<CsdlAnnotation> annotations) {
		/** Validates and copies the components. */
		public Member {
			Objects.requireNonNull(name, "name");
			annotations = List.copyOf(annotations);
		}
	}
}
