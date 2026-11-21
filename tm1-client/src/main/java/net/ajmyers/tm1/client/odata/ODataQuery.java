package net.ajmyers.tm1.client.odata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * OData query options: {@code $select}, {@code $expand} (with nested options),
 * {@code $filter}, {@code $orderby}, {@code $top}, {@code $skip}, {@code $count}, custom
 * options such as TM1's {@code !sandbox}, and parameter aliases. Immutable: each method
 * returns a new query.
 *
 * <pre>{@code
 * ODataQuery.none().select("Name", "Type")
 *     .expand("Hierarchies", h -> h.select("Name").top(5))
 *     .filter(Filter.startsWith(ODataProperty.of("Name"), "Q"))
 *     .toQueryString()
 *     // $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q')   (then encoded)
 * }</pre>
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

	private final Filter filter;

	private final List<String> orderBy;

	private final Integer top;

	private final Integer skip;

	private final Boolean count;

	private final Map<String, String> custom;

	private final Map<String, String> aliases;

	private ODataQuery(List<String> select, Map<String, ODataQuery> expand, Filter filter, List<String> orderBy,
			Integer top, Integer skip, Boolean count, Map<String, String> custom, Map<String, String> aliases) {
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

	/** No options: the server's default response. */
	public static ODataQuery none() {
		return EMPTY;
	}

	/**
	 * Adds properties to {@code $select}. Like every option here it's an instance method,
	 * so it works the same at the top level and inside
	 * {@link #expand(String, UnaryOperator)}.
	 */
	public ODataQuery select(String... properties) {
		List<String> next = new ArrayList<>(select);
		for (String p : properties) {
			next.add(Objects.requireNonNull(p, "property"));
		}
		return with(List.copyOf(next), expand, filter, orderBy, top, skip, count, custom, aliases);
	}

	/** Adds properties to {@code $select}. */
	public ODataQuery select(ODataProperty... properties) {
		String[] paths = new String[properties.length];
		for (int i = 0; i < properties.length; i++) {
			paths[i] = properties[i].path();
		}
		return select(paths);
	}

	/** Expands a navigation property with no nested options. */
	public ODataQuery expand(String navigation) {
		return expand(navigation, q -> q);
	}

	/**
	 * Expands a navigation property, with nested options:
	 * {@code expand("Hierarchies", h -> h.select("Name"))}.
	 */
	public ODataQuery expand(String navigation, UnaryOperator<ODataQuery> options) {
		Objects.requireNonNull(navigation, "navigation");
		Map<String, ODataQuery> next = new LinkedHashMap<>(expand);
		next.put(navigation, options.apply(next.getOrDefault(navigation, EMPTY)));
		return with(select, next, filter, orderBy, top, skip, count, custom, aliases);
	}

	/** Sets {@code $filter}. */
	public ODataQuery filter(Filter filter) {
		return with(select, expand, Objects.requireNonNull(filter, "filter"), orderBy, top, skip, count, custom,
				aliases);
	}

	/** Adds an ascending {@code $orderby} key. */
	public ODataQuery orderBy(String property) {
		return order(property, "");
	}

	/** Adds a descending {@code $orderby} key. */
	public ODataQuery orderByDescending(String property) {
		return order(property, " desc");
	}

	/** Sets {@code $top}: at most this many items. */
	public ODataQuery top(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("$top can't be negative: " + n);
		}
		return with(select, expand, filter, orderBy, n, skip, count, custom, aliases);
	}

	/** Sets {@code $skip}: skip this many items first. */
	public ODataQuery skip(int n) {
		if (n < 0) {
			throw new IllegalArgumentException("$skip can't be negative: " + n);
		}
		return with(select, expand, filter, orderBy, top, n, count, custom, aliases);
	}

	/** Sets {@code $count=true}: include the total number of matching items. */
	public ODataQuery withCount() {
		return with(select, expand, filter, orderBy, top, skip, true, custom, aliases);
	}

	/**
	 * Sets a custom query option, such as TM1's {@code !sandbox}. Custom options must not
	 * start with {@code $} or {@code @}. Setting one again replaces it.
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
	 */
	public ODataQuery alias(ODataPath.Alias alias, String literalOrJson) {
		Map<String, String> next = new LinkedHashMap<>(aliases);
		next.put(alias.name(), Objects.requireNonNull(literalOrJson, "literalOrJson"));
		return with(select, expand, filter, orderBy, top, skip, count, custom, next);
	}

	/** True if this query has no options. */
	public boolean isEmpty() {
		return toQueryString().isEmpty();
	}

	/**
	 * The encoded query string, without a leading {@code ?}; empty if there are no
	 * options.
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

	/** The custom option's value, e.g. the current {@code !sandbox}, if set. */
	public String option(String name) {
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

	private static ODataQuery with(List<String> select, Map<String, ODataQuery> expand, Filter filter,
			List<String> orderBy, Integer top, Integer skip, Boolean count, Map<String, String> custom,
			Map<String, String> aliases) {
		return new ODataQuery(select, new LinkedHashMap<>(expand), filter, orderBy, top, skip, count,
				new LinkedHashMap<>(custom), new LinkedHashMap<>(aliases));
	}

}
