// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.odata;

import java.util.Objects;

/**
 * A property path for queries, such as {@code Name} or {@code Hierarchy/Name}. The
 * generated model provides constants implementing this, so queries are checked by the
 * compiler; {@link #of} works for any path.
 */
public interface ODataProperty {

	/**
	 * The path, with {@code /} between segments.
	 * @return the path, unencoded
	 */
	String path();

	/**
	 * A property path from text.
	 * @param path the path, with {@code /} between segments
	 * @return the property
	 */
	static ODataProperty of(String path) {
		Objects.requireNonNull(path, "path");
		return () -> path;
	}

	/**
	 * {@code this eq value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter eq(Object value) {
		return Filter.compare(this, "eq", value);
	}

	/**
	 * {@code this ne value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter ne(Object value) {
		return Filter.compare(this, "ne", value);
	}

	/**
	 * {@code this gt value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter gt(Object value) {
		return Filter.compare(this, "gt", value);
	}

	/**
	 * {@code this ge value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter ge(Object value) {
		return Filter.compare(this, "ge", value);
	}

	/**
	 * {@code this lt value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter lt(Object value) {
		return Filter.compare(this, "lt", value);
	}

	/**
	 * {@code this le value}.
	 * @param value the value to compare with, written as an OData literal
	 * ({@link ODataLiterals#of}); {@code null} isn't allowed, use {@link #isNull()}
	 * @return the comparison
	 */
	default Filter le(Object value) {
		return Filter.compare(this, "le", value);
	}

	/**
	 * {@code this eq null}.
	 * @return the comparison
	 */
	default Filter isNull() {
		return Filter.compare(this, "eq", null);
	}

}
