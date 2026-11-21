package net.ajmyers.tm1.client.odata;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * takes any expression as is.
 *
 * <p>
 * Note that TM1 compares names in {@code $filter} case- and space-sensitively, while
 * keyed access ignores both. To find one object by name, use its key rather than a
 * filter.
 */
public final class Filter {

	private final String expression;

	private Filter(String expression) {
		this.expression = expression;
	}

	/** An expression written by hand, used as is. */
	public static Filter raw(String expression) {
		Objects.requireNonNull(expression, "expression");
		return new Filter(expression);
	}

	/** All of the given filters. */
	public static Filter and(Filter... filters) {
		return join("and", filters);
	}

	/** Any of the given filters. */
	public static Filter or(Filter... filters) {
		return join("or", filters);
	}

	/** {@code not filter}. */
	public static Filter not(Filter filter) {
		return new Filter("not(" + filter.expression + ")");
	}

	/** {@code startswith(property,'prefix')}. */
	public static Filter startsWith(ODataProperty property, String prefix) {
		return function("startswith", property, prefix);
	}

	/** {@code endswith(property,'suffix')}. */
	public static Filter endsWith(ODataProperty property, String suffix) {
		return function("endswith", property, suffix);
	}

	/** {@code contains(property,'text')}. */
	public static Filter contains(ODataProperty property, String text) {
		return function("contains", property, text);
	}

	/**
	 * {@code collection/any(v:condition)}: true if any member of a collection matches.
	 * The lambda receives the range variable, from which to build paths
	 * ({@code v.nav("Name")}).
	 */
	public static Filter any(String collectionPath, String variable, Function<Variable, Filter> condition) {
		return lambda("any", collectionPath, variable, condition);
	}

	/**
	 * {@code collection/all(v:condition)}: true if every member of a collection matches.
	 */
	public static Filter all(String collectionPath, String variable, Function<Variable, Filter> condition) {
		return lambda("all", collectionPath, variable, condition);
	}

	/**
	 * {@code property in (a,b,c)}. Not every TM1 version supports {@code in}; {@link #or}
	 * of {@code eq}s always works.
	 */
	public static Filter in(ODataProperty property, Object... values) {
		if (values.length == 0) {
			throw new IllegalArgumentException("in() needs at least one value: an empty set matches nothing");
		}
		return new Filter("(" + property.path() + " in ("
				+ Arrays.stream(values).map(ODataLiterals::of).collect(Collectors.joining(",")) + "))");
	}

	static Filter compare(ODataProperty property, String operator, Object value) {
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

		/** A path through the variable: {@code v/Name}. */
		public ODataProperty nav(String path) {
			return ODataProperty.of(name + "/" + path);
		}

		/** The variable itself, for collections of primitive values: {@code v eq 'x'}. */
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
		return new Filter(
				"(" + Arrays.stream(filters).map(f -> f.expression).collect(Collectors.joining(" " + operator + " "))
						+ ")");
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
