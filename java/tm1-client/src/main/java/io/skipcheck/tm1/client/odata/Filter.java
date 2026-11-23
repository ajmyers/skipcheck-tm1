// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.odata;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * A {@code $filter} expression, built from {@link ODataProperty} comparisons and the
 * canonical functions TM1 supports:
 *
 * <pre>{@code
 * Filter.and(Filter.startsWith(Element.NAME, "Q"), Element.TYPE.eq(ODataLiterals.enumMember("ibm.tm1.api.v1.ElementType", "Numeric")))
 *     // (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
 * Filter.any("Hierarchies", "h", h -> h.nav("Name").eq("Region"))
 *     // Hierarchies/any(h:h/Name eq 'Region')
 * }</pre>
 *
 * <p>
 * Every binary expression is parenthesized, so precedence never depends on how a filter
 * was composed. Values are written as OData literals ({@link ODataLiterals#of}); the
 * whole expression is percent-encoded once, when the query is rendered. {@link #raw}
 * takes any expression as is, and is parenthesized when it's combined with others.
 *
 * <p>
 * Note that TM1 compares names in {@code $filter} case- and space-sensitively, while
 * keyed access ignores both. To find one object by name, use its key rather than a
 * filter.
 */
public final class Filter {

	private final String expression;

	/**
	 * Whether the expression is {@link #raw}: text that may need parentheses around it.
	 */
	private final boolean raw;

	private Filter(String expression) {
		this(expression, false);
	}

	private Filter(String expression, boolean raw) {
		this.expression = expression;
		this.raw = raw;
	}

	/**
	 * An expression written by hand, used as is. Combined with others by {@link #and} or
	 * {@link #or}, it's put in parentheses, so it keeps its meaning whatever operators it
	 * uses.
	 * @param expression the expression, unencoded
	 * @return the filter
	 */
	public static Filter raw(String expression) {
		Objects.requireNonNull(expression, "expression");
		return new Filter(expression, true);
	}

	/**
	 * All of the given filters.
	 * @param filters the filters, at least one
	 * @return the filters joined with {@code and}, or the filter itself if there's only
	 * one
	 */
	public static Filter and(Filter... filters) {
		return join("and", filters);
	}

	/**
	 * Any of the given filters.
	 * @param filters the filters, at least one
	 * @return the filters joined with {@code or}, or the filter itself if there's only
	 * one
	 */
	public static Filter or(Filter... filters) {
		return join("or", filters);
	}

	/**
	 * {@code not filter}.
	 * @param filter the filter to negate
	 * @return the negated filter
	 */
	public static Filter not(Filter filter) {
		return new Filter("not(" + filter.expression + ")");
	}

	/**
	 * {@code startswith(property,'prefix')}.
	 * @param property the string property to test
	 * @param prefix the text it must start with
	 * @return the filter
	 */
	public static Filter startsWith(ODataProperty property, String prefix) {
		return function("startswith", property, prefix);
	}

	/**
	 * {@code endswith(property,'suffix')}.
	 * @param property the string property to test
	 * @param suffix the text it must end with
	 * @return the filter
	 */
	public static Filter endsWith(ODataProperty property, String suffix) {
		return function("endswith", property, suffix);
	}

	/**
	 * {@code contains(property,'text')}.
	 * @param property the string property to test
	 * @param text the text it must contain
	 * @return the filter
	 */
	public static Filter contains(ODataProperty property, String text) {
		return function("contains", property, text);
	}

	/**
	 * {@code collection/any(v:condition)}: true if any member of a collection matches.
	 * The lambda receives the range variable, from which to build paths
	 * ({@code v.nav("Name")}).
	 * @param collectionPath the path to the collection, e.g. {@code Hierarchies}, used as
	 * is
	 * @param variable the range variable's name, an OData identifier
	 * @param condition builds the condition from the range variable
	 * @return the filter
	 */
	public static Filter any(String collectionPath, String variable, Function<Variable, Filter> condition) {
		return lambda("any", collectionPath, variable, condition);
	}

	/**
	 * {@code collection/all(v:condition)}: true if every member of a collection matches.
	 * @param collectionPath the path to the collection, e.g. {@code Hierarchies}, used as
	 * is
	 * @param variable the range variable's name, an OData identifier
	 * @param condition builds the condition from the range variable
	 * @return the filter
	 */
	public static Filter all(String collectionPath, String variable, Function<Variable, Filter> condition) {
		return lambda("all", collectionPath, variable, condition);
	}

	/**
	 * {@code property in (a,b,c)}. Not every TM1 version supports {@code in}; {@link #or}
	 * of {@code eq}s always works.
	 * @param property the property to test
	 * @param values the values, at least one, written as OData literals
	 * ({@link ODataLiterals#of})
	 * @return the filter
	 */
	public static Filter in(ODataProperty property, Object... values) {
		if (values.length == 0) {
			throw new IllegalArgumentException("in() needs at least one value: an empty set matches nothing");
		}
		return new Filter("(" + property.path() + " in ("
				+ Arrays.stream(values).map(ODataLiterals::of).collect(Collectors.joining(",")) + "))");
	}

	static Filter compare(ODataProperty property, String operator, @Nullable Object value) {
		return new Filter("(" + property.path() + " " + operator + " " + ODataLiterals.of(value) + ")");
	}

	/** The expression, unencoded, e.g. {@code (Name eq 'Sales')}. */
	@Override
	public String toString() {
		return expression;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Filter other && expression.equals(other.expression);
	}

	@Override
	public int hashCode() {
		return expression.hashCode();
	}

	/** A lambda's range variable, the root of paths inside it. */
	public static final class Variable {

		private final String name;

		private Variable(String name) {
			this.name = name;
		}

		/**
		 * A path through the variable: {@code v/Name}.
		 * @param path the path from the variable, with {@code /} between segments
		 * @return the property path
		 */
		public ODataProperty nav(String path) {
			return ODataProperty.of(name + "/" + path);
		}

		/**
		 * The variable itself, for collections of primitive values: {@code v eq 'x'}.
		 * @return the variable as a property path
		 */
		public ODataProperty self() {
			return ODataProperty.of(name);
		}

	}

	private static Filter join(String operator, Filter... filters) {
		if (filters.length == 0) {
			throw new IllegalArgumentException(operator + "() needs at least one filter");
		}
		if (filters.length == 1) {
			return filters[0];
		}
		return new Filter("("
				+ Arrays.stream(filters).map(Filter::operand).collect(Collectors.joining(" " + operator + " ")) + ")");
	}

	/**
	 * The expression as an operand of {@code and} or {@code or}. Every expression this
	 * class builds stands on its own; a raw one might not, so it gets parentheses.
	 */
	private String operand() {
		return raw ? "(" + expression + ")" : expression;
	}

	private static Filter function(String name, ODataProperty property, String argument) {
		return new Filter(name + "(" + property.path() + "," + ODataLiterals.string(argument) + ")");
	}

	private static Filter lambda(String kind, String collectionPath, String variable,
			Function<Variable, Filter> condition) {
		if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Not a valid range variable: " + variable);
		}
		return new Filter(collectionPath + "/" + kind + "(" + variable + ":"
				+ condition.apply(new Variable(variable)).expression + ")");
	}

}
