// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A member of the generated model that departs from TM1's {@code $metadata}: a property
 * {@code $metadata} doesn't declare, one TM1 sends in another type than declared, or a
 * date TM1 sometimes garbles. The model carries it so every departure can be found, and
 * checked again on a new TM1 version.
 *
 * <p>
 * Undeclared properties come from probing a running TM1 ({@code ./gradlew probeTm1}); the
 * others from {@code spec/tm1-overlay.json}. Every client tags the same members.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER })
public @interface Tm1Departure {

	/**
	 * How the member departs.
	 * @return the kind of departure
	 */
	Kind value();

	/**
	 * The TM1 schema versions it was seen on, such as {@code 11.8.35}; empty when it
	 * wasn't found by a probe.
	 * @return the versions
	 */
	String[] observedIn() default {};

	/**
	 * What TM1 does, in words.
	 * @return the note
	 */
	String note() default "";

	/** How a member departs from {@code $metadata}. */
	enum Kind {

		/** TM1 sends it, but {@code $metadata} doesn't declare it. */
		UNDECLARED,

		/**
		 * TM1 sends it in another type than {@code $metadata} declares: it's read as
		 * JSON.
		 */
		TYPE,

		/** A date-time TM1 sometimes sends something else in: that reads as null. */
		LENIENT_DATE

	}

}
