// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.odata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * OData query options: {@code $select}, {@code $expand} (with nested options),
 * {@code $filter}, {@code $orderby}, {@code $top}, {@code $skip}, {@code $count}, custom
 * options such as TM1's {@code !sandbox}, and parameter aliases. Immutable: each method
 * returns a new query.
 *
 * {@snippet class = examples.ODataQueryExamples region = build}
 *
 * <p>
 * Options are written in a fixed order, lowercase with {@code $} as OData 4.0 requires,
 * and each value is percent-encoded once by {@link #toQueryString()}. Nested options
 * inside {@code $expand} are separated by {@code ;}.
 */
public final class ODataQuery {

	private static final ODataQuery EMPTY = new ODataQuery(List.of(), new LinkedHashMap<>(), null, List.of(), null,
			null, null, new LinkedHashMap<>(), new LinkedHashMap<>());

	private static final Pattern CUSTOM = Pattern.compile("[A-Za-z!][A-Za-z0-9_\\-.!]*");

	private final List<String> select;

	private final Map<String, ODataQuery> expand;

	private final @Nullable Filter filter;

	private final List<String> orderBy;

	private final @Nullable Integer top;

	private final @Nullable Integer skip;

	private final @Nullable Boolean count;

	private final Map<String, String> custom;

	private final Map<String, String> aliases;

	private ODataQuery(List<String> select, Map<String, ODataQuery> expand, @Nullable Filter filter,
			List<String> orderBy, @Nullable Integer top, @Nullable Integer skip, @Nullable Boolean count,
			Map<String, String> custom, Map<String, String> aliases) {
		this.select = select;
		this.expand = Collections.unmodifiableMap(expand);
		this.filter = filter;
		this.orderBy = orderBy;
		this.top = top;
		this.skip = skip;
		this.count = count;
		this.custom = Collections.unmodifiableMap(custom);
		this.aliases = Collections.unmodifiableMap(aliases);
	}

	/**
	 * No options: the server's default response.
	 * @return the empty query
	 */
	public static ODataQuery none() {
		return EMPTY;
	}

	/**
	 * Adds properties to {@code $select}. Like every option here it's an instance method,
	 * so it works the same at the top level and inside
	 * {@link #expand(String, UnaryOperator)}.
	 * @param properties the property paths, added after any already selected
	 * @return a new query
	 */
	public ODataQuery select(String... properties) {
		List<String> next = new ArrayList<>(select);
		for (String p : properties) {
			next.add(Objects.requireNonNull(p, "property"));
		}
		return with(List.copyOf(next), expand, filter, orderBy, top, skip, count, custom, aliases);
	}

	/**
	 * Adds properties to {@code $select}.
	 * @param properties the properties, added after any already selected
	 * @return a new query
	 */
	public ODataQuery select(ODataProperty... properties) {
		String[] paths = new String[properties.length];
		for (int i = 0; i < properties.length; i++) {
			paths[i] = properties[i].path();
		}
		return select(paths);
	}

	/**
	 * Expands a navigation property with no nested options.
	 * @param navigation the navigation property's name
	 * @return a new query
	 */
	public ODataQuery expand(String navigation) {
		return expand(navigation, q -> q);
	}

	/**
	 * Expands a navigation property, with nested options:
	 * {@code expand("Hierarchies", h -> h.select("Name"))}.
	 * @param navigation the navigation property's name
	 * @param options adds the nested options to the query it's given: the empty query, or
	 * the nested options so far if {@code navigation} is already expanded
	 * @return a new query
	 */
	public ODataQuery expand(String navigation, UnaryOperator<ODataQuery> options) {
		Objects.requireNonNull(navigation, "navigation");
		Map<String, ODataQuery> next = new LinkedHashMap<>(expand);
		next.put(navigation, options.apply(next.getOrDefault(navigation, EMPTY)));
		return with(select, next, filter, orderBy, top, skip, count, custom, aliases);
	}

	/**
	 * Expands a navigation property with no nested options:
	 * {@code expand(Cube.DIMENSIONS)}.
	 * @param navigation the navigation property
	 * @return a new query
	 */
	public ODataQuery expand(ODataProperty navigation) {
		return expand(navigation.path());
	}

	/**
	 * Expands a navigation property, with nested options:
	 * {@code expand(Dimension.HIERARCHIES, h -> h.select(Hierarchy.NAME))}.
	 * @param navigation the navigation property
	 * @param options adds the nested options to the query it's given, as for
	 * {@link #expand(String, UnaryOperator)}
	 * @return a new query
	 */
	public ODataQuery expand(ODataProperty navigation, UnaryOperator<ODataQuery> options) {
		return expand(navigation.path(), options);
	}

	/**
	 * Sets {@code $filter}.
	 * @param filter the filter, replacing any set before
	 * @return a new query
	 */
	public ODataQuery filter(Filter filter) {
		return with(select, expand, Objects.requireNonNull(filter, "filter"), orderBy, top, skip, count, custom,
				aliases);
	}

	/**
	 * Adds an ascending {@code $orderby} key.
	 * @param property the property path, after any keys already added
	 * @return a new query
	 */
	public ODataQuery orderBy(String property) {
		return order(property, "");
	}

	/**
	 * Adds a descending {@code $orderby} key.
	 * @param property the property path, after any keys already added
	 * @return a new query
	 */
	public ODataQuery orderByDescending(String property) {
		return order(property, " desc");
	}

	/**
	 * Adds an ascending {@code $orderby} key: {@code orderBy(Element.NAME)}.
	 * @param property the property, after any keys already added
	 * @return a new query
	 */
	public ODataQuery orderBy(ODataProperty property) {
		return orderBy(property.path());
	}

	/**
	 * Adds a descending {@code $orderby} key.
	 * @param property the property, after any keys already added
	 * @return a new query
	 */
	public ODataQuery orderByDescending(ODataProperty property) {
		return orderByDescending(property.path());
	}

	/**
	 * Sets {@code $top}: at most this many items.
	 * @param n the maximum number of items, zero or more
	 * @return a new query
	 * @throws IllegalArgumentException if {@code n} is negative
	 */
	public ODataQuery top(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("$top can't be negative: " + n);
		}
		return with(select, expand, filter, orderBy, n, skip, count, custom, aliases);
	}

	/**
	 * Sets {@code $skip}: skip this many items first.
	 * @param n the number of items to skip, zero or more
	 * @return a new query
	 * @throws IllegalArgumentException if {@code n} is negative
	 */
	public ODataQuery skip(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("$skip can't be negative: " + n);
		}
		return with(select, expand, filter, orderBy, top, n, count, custom, aliases);
	}

	/**
	 * Sets {@code $count=true}: include the total number of matching items.
	 * @return a new query
	 */
	public ODataQuery withCount() {
		return with(select, expand, filter, orderBy, top, skip, true, custom, aliases);
	}

	/**
	 * Sets a custom query option, such as TM1's {@code !sandbox}. Custom options must not
	 * start with {@code $} or {@code @}. Setting one again replaces it.
	 * @param name the option's name, e.g. {@code !sandbox}
	 * @param value the option's value, unencoded
	 * @return a new query
	 * @throws IllegalArgumentException if {@code name} isn't a valid custom option name
	 */
	public ODataQuery option(String name, String value) {
		if (name == null || !CUSTOM.matcher(name).matches()) {
			throw new IllegalArgumentException("Not a valid custom query option name: " + name);
		}
		Map<String, String> next = new LinkedHashMap<>(custom);
		next.put(name, Objects.requireNonNull(value, "value"));
		return with(select, expand, filter, orderBy, top, skip, count, next, aliases);
	}

	/**
	 * Defines a parameter alias for a function call: {@code @p=<literal or JSON>},
	 * written as given.
	 * @param alias the alias, as used in the function call
	 * @param literalOrJson the alias's value, an OData literal or JSON, unencoded
	 * @return a new query
	 */
	public ODataQuery alias(ODataPath.Alias alias, String literalOrJson) {
		Map<String, String> next = new LinkedHashMap<>(aliases);
		next.put(alias.name(), Objects.requireNonNull(literalOrJson, "literalOrJson"));
		return with(select, expand, filter, orderBy, top, skip, count, custom, next);
	}

	/**
	 * True if this query has no options.
	 * @return {@code true} if there are no options
	 */
	public boolean isEmpty() {
		return toQueryString().isEmpty();
	}

	/**
	 * The encoded query string, without a leading {@code ?}; empty if there are no
	 * options.
	 * @return the encoded query string, or an empty string
	 */
	public String toQueryString() {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> option : systemOptions().entrySet()) {
			parts.add(option.getKey() + "=" + ODataEncoding.queryValue(option.getValue()));
		}
		custom.forEach((name, value) -> parts.add(name + "=" + ODataEncoding.queryValue(value)));
		aliases.forEach((name, value) -> parts.add(name + "=" + ODataEncoding.queryValue(value)));
		return String.join("&", parts);
	}

	/** The query unencoded, for logs and messages. */
	@Override
	public String toString() {
		List<String> parts = new ArrayList<>();
		systemOptions().forEach((name, value) -> parts.add(name + "=" + value));
		custom.forEach((name, value) -> parts.add(name + "=" + value));
		aliases.forEach((name, value) -> parts.add(name + "=" + value));
		return String.join("&", parts);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ODataQuery other && toQueryString().equals(other.toQueryString());
	}

	@Override
	public int hashCode() {
		return toQueryString().hashCode();
	}

	/**
	 * The custom option's value, e.g. the current {@code !sandbox}, if set.
	 * @param name the option's name
	 * @return the option's value, unencoded, or {@code null} if it isn't set
	 */
	public @Nullable String option(String name) {
		return custom.get(name);
	}

	private Map<String, String> systemOptions() {
		Map<String, String> options = new LinkedHashMap<>();
		if (!select.isEmpty()) {
			options.put("$select", String.join(",", select));
		}
		if (!expand.isEmpty()) {
			options.put("$expand", expandValue());
		}
		if (filter != null) {
			options.put("$filter", filter.toString());
		}
		if (!orderBy.isEmpty()) {
			options.put("$orderby", String.join(",", orderBy));
		}
		if (top != null) {
			options.put("$top", top.toString());
		}
		if (skip != null) {
			options.put("$skip", skip.toString());
		}
		if (count != null) {
			options.put("$count", count.toString());
		}
		return options;
	}

	private String expandValue() {
		List<String> items = new ArrayList<>();
		expand.forEach((navigation, nested) -> {
			Map<String, String> inner = nested.systemOptions();
			if (inner.isEmpty()) {
				items.add(navigation);
			}
			else {
				List<String> options = new ArrayList<>();
				inner.forEach((name, value) -> options.add(name + "=" + value));
				items.add(navigation + "(" + String.join(";", options) + ")");
			}
		});
		return String.join(",", items);
	}

	private ODataQuery order(String property, String direction) {
		List<String> next = new ArrayList<>(orderBy);
		next.add(Objects.requireNonNull(property, "property") + direction);
		return with(select, expand, filter, List.copyOf(next), top, skip, count, custom, aliases);
	}

	private static ODataQuery with(List<String> select, Map<String, ODataQuery> expand, @Nullable Filter filter,
			List<String> orderBy, @Nullable Integer top, @Nullable Integer skip, @Nullable Boolean count,
			Map<String, String> custom, Map<String, String> aliases) {
		return new ODataQuery(select, new LinkedHashMap<>(expand), filter, orderBy, top, skip, count,
				new LinkedHashMap<>(custom), new LinkedHashMap<>(aliases));
	}

}
