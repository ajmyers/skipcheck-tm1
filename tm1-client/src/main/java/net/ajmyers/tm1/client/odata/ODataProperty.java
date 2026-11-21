package net.ajmyers.tm1.client.odata;

import java.util.Objects;

/**
 * A property path for queries, such as {@code Name} or {@code Hierarchy/Name}. The
 * generated model (phase 1) provides constants implementing this, so queries are checked
 * by the compiler; {@link #of} works for any path.
 */
public interface ODataProperty {

	/** The path, with {@code /} between segments. */
	String path();

	/** A property path from text. */
	static ODataProperty of(String path) {
		Objects.requireNonNull(path, "path");
		return () -> path;
	}

	/** {@code this eq value}. */
	default Filter eq(Object value) {
		return Filter.compare(this, "eq", value);
	}

	/** {@code this ne value}. */
	default Filter ne(Object value) {
		return Filter.compare(this, "ne", value);
	}

	/** {@code this gt value}. */
	default Filter gt(Object value) {
		return Filter.compare(this, "gt", value);
	}

	/** {@code this ge value}. */
	default Filter ge(Object value) {
		return Filter.compare(this, "ge", value);
	}

	/** {@code this lt value}. */
	default Filter lt(Object value) {
		return Filter.compare(this, "lt", value);
	}

	/** {@code this le value}. */
	default Filter le(Object value) {
		return Filter.compare(this, "le", value);
	}

	/** {@code this eq null}. */
	default Filter isNull() {
		return Filter.compare(this, "eq", null);
	}

}
