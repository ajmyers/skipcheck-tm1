package net.ajmyers.tm1.client.odata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A resource path relative to the service root, built from segments and encoded as it's
 * built:
 *
 * <pre>{@code
 * ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements").key("O'Neil")
 *     // Dimensions('Region')/Hierarchies('Region')/Elements('O''Neil')
 * ODataPath.of("Cubes").key("Sales").action("tm1.Reserve")
 *     // Cubes('Sales')/tm1.Reserve
 * }</pre>
 *
 * <p>
 * Names (entity sets, navigation properties, operations, type casts) must be OData
 * identifiers, optionally qualified; they're checked, never encoded. Key values can be
 * any text: they're written as string literals and percent-encoded. Paths are immutable.
 */
public final class ODataPath {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

	private final List<String> segments;

	private ODataPath(List<String> segments) {
		this.segments = segments;
	}

	/**
	 * A path starting at an entity set, singleton or operation import, e.g.
	 * {@code Cubes}.
	 * @param name the first segment's name, an OData identifier
	 * @return a one-segment path
	 * @throws IllegalArgumentException if {@code name} isn't an identifier
	 */
	public static ODataPath of(String name) {
		return new ODataPath(List.of(identifier(name)));
	}

	/**
	 * The service's metadata document, {@code $metadata}: its CSDL XML. A path of its
	 * own, since {@code $metadata} isn't an identifier {@link #of} accepts.
	 * @return the path
	 */
	public static ODataPath metadata() {
		return new ODataPath(List.of("$metadata"));
	}

	/**
	 * Adds a single-part key with a string value: {@code ('Sales')}.
	 * @param value the key value, any text; quoted and percent-encoded
	 * @return a new path with the key on its last segment
	 * @throws IllegalStateException if the last segment already has a key or is a
	 * {@code $} segment
	 */
	public ODataPath key(String value) {
		return appendToLast("(" + encodedString(value) + ")");
	}

	/**
	 * Adds a single-part key with a non-string value, such as a number: {@code (42)}.
	 * @param value the key value, written as an OData literal ({@link ODataLiterals#of});
	 * text is treated as by {@link #key(String)}
	 * @return a new path with the key on its last segment
	 */
	public ODataPath key(Object value) {
		if (value instanceof CharSequence text) {
			return key(text.toString());
		}
		return appendToLast("(" + ODataEncoding.pathValue(ODataLiterals.of(value)) + ")");
	}

	/**
	 * Adds a compound key, in the order given:
	 * {@code (ParentName='Total',ComponentName='Q1')}.
	 * @param parts the key's property names (identifiers) and values, in order
	 * @return a new path with the key on its last segment
	 * @throws IllegalArgumentException if {@code parts} is empty
	 */
	public ODataPath key(Map<String, ?> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("A compound key needs at least one part");
		}
		StringBuilder key = new StringBuilder("(");
		parts.forEach((name, value) -> {
			if (key.length() > 1) {
				key.append(',');
			}
			key.append(identifier(name))
				.append('=')
				.append(value instanceof CharSequence text ? encodedString(text.toString())
						: ODataEncoding.pathValue(ODataLiterals.of(value)));
		});
		return appendToLast(key.append(')').toString());
	}

	/**
	 * Adds a navigation property or structural property segment, e.g. {@code Views}.
	 * @param property the property name, an OData identifier
	 * @return a new path with the segment added
	 */
	public ODataPath nav(String property) {
		return append(identifier(property));
	}

	/**
	 * Adds a type-cast segment, e.g. {@code tm1.NativeView}.
	 * @param qualifiedType the namespace-qualified type name
	 * @return a new path with the segment added
	 */
	public ODataPath cast(String qualifiedType) {
		return append(qualified(qualifiedType));
	}

	/**
	 * Adds a bound action segment, e.g. {@code tm1.Reserve}. Actions are invoked with
	 * POST.
	 * @param qualifiedName the namespace-qualified action name
	 * @return a new path with the segment added
	 */
	public ODataPath action(String qualifiedName) {
		return append(qualified(qualifiedName));
	}

	/**
	 * Adds a bound function call with literal parameters, e.g.
	 * {@code tm1.GetPartition(Begin=0,End=99)}. Use parameter aliases (in
	 * {@link ODataQuery}) for complex or collection parameters.
	 * @param qualifiedName the namespace-qualified function name
	 * @param parameters the parameter names and values, in order; each value is written
	 * as an OData literal, or as the alias's name if it's an {@link Alias}
	 * @return a new path with the segment added
	 */
	public ODataPath function(String qualifiedName, Map<String, ?> parameters) {
		return append(call(qualified(qualifiedName), parameters));
	}

	/**
	 * A path a server returned, such as a delta link
	 * ({@code MessageLogEntries/!delta('…')}), relative to the service root and not yet
	 * encoded: each segment is percent-encoded as it is (keeping {@code !}, {@code (},
	 * {@code )} and {@code '}), without checking it's an identifier.
	 * @param link the path, without a query
	 * @return the path
	 * @throws IllegalArgumentException if it's empty, absolute or has a query
	 */
	public static ODataPath link(String link) {
		Objects.requireNonNull(link, "link");
		if (link.isEmpty() || link.startsWith("/") || link.contains("://") || link.contains("?")) {
			throw new IllegalArgumentException("Not a relative path without a query: " + link);
		}
		List<String> segments = new ArrayList<>();
		for (String segment : link.split("/", -1)) {
			segments.add(ODataEncoding.linkSegment(segment));
		}
		return new ODataPath(List.copyOf(segments));
	}

	/**
	 * A function import called from the service root, e.g. {@code ModelCubes()} or
	 * {@code TailMessageLog(Top=10)}.
	 * @param name the import's name, unqualified
	 * @param parameters the parameter names and values, in order, as for
	 * {@link #function}
	 * @return the path
	 */
	public static ODataPath functionImport(String name, Map<String, ?> parameters) {
		return new ODataPath(List.of()).append(call(identifier(name), parameters));
	}

	private static String call(String function, Map<String, ?> parameters) {
		StringBuilder call = new StringBuilder(function).append('(');
		boolean first = true;
		for (Map.Entry<String, ?> p : parameters.entrySet()) {
			if (!first) {
				call.append(',');
			}
			first = false;
			Object value = p.getValue();
			call.append(identifier(p.getKey()))
				.append('=')
				.append(value instanceof Alias alias ? alias.name() : ODataEncoding.pathValue(ODataLiterals.of(value)));
		}
		return call.append(')').toString();
	}

	/**
	 * Adds {@code $value}: a property's or media entity's raw value.
	 * @return a new path with the segment added
	 */
	public ODataPath value() {
		return append("$value");
	}

	/**
	 * Adds {@code $count}: the number of items in a collection, as plain text.
	 * @return a new path with the segment added
	 */
	public ODataPath count() {
		return append("$count");
	}

	/**
	 * Adds {@code $ref}: references to entities rather than the entities themselves.
	 * @return a new path with the segment added
	 */
	public ODataPath ref() {
		return append("$ref");
	}

	/** The encoded path, without a leading slash, e.g. {@code Cubes('Sales')/Views}. */
	@Override
	public String toString() {
		return String.join("/", segments);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ODataPath other && segments.equals(other.segments);
	}

	@Override
	public int hashCode() {
		return segments.hashCode();
	}

	/**
	 * A parameter alias used as a function parameter value: {@code tm1.Fn(p=@p)}, with
	 * {@code @p} defined in the query ({@link ODataQuery#alias}).
	 *
	 * @param name the alias, {@code @} followed by an identifier, e.g. {@code @p}
	 */
	public record Alias(String name) {
		/** Validates the name. */
		public Alias {
			if (name == null || !name.startsWith("@") || !IDENTIFIER.matcher(name.substring(1)).matches()) {
				throw new IllegalArgumentException("A parameter alias is @ followed by an identifier: " + name);
			}
		}
	}

	private ODataPath append(String segment) {
		List<String> next = new ArrayList<>(segments);
		next.add(segment);
		return new ODataPath(Collections.unmodifiableList(next));
	}

	private ODataPath appendToLast(String suffix) {
		String last = segments.get(segments.size() - 1);
		if (last.endsWith(")") || last.startsWith("$")) {
			throw new IllegalStateException("Can't add a key to '" + last + "': a key follows a collection, once");
		}
		List<String> next = new ArrayList<>(segments);
		next.set(next.size() - 1, last + suffix);
		return new ODataPath(Collections.unmodifiableList(next));
	}

	private static String encodedString(String value) {
		Objects.requireNonNull(value, "key value");
		return "'" + ODataEncoding.pathValue(value.replace("'", "''")) + "'";
	}

	private static String identifier(String name) {
		if (name == null || !IDENTIFIER.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"Not an OData identifier: '" + name + "'. Key values go in key(), not in a segment name.");
		}
		return name;
	}

	private static String qualified(String name) {
		if (name == null || !QUALIFIED.matcher(name).matches()) {
			throw new IllegalArgumentException("Not a qualified OData name: '" + name + "'");
		}
		return name;
	}

}
