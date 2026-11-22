package net.ajmyers.tm1.client.conformance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1ProtocolException;
import net.ajmyers.tm1.client.error.Tm1TransportException;
import net.ajmyers.tm1.client.error.Tm1UnsupportedOperationException;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The language-neutral encoding of a call's arguments, its result and its error, as
 * {@code spec/conformance/README.md} describes it. JSON values stand for themselves;
 * anything else is an object with one {@code $}-tag, such as {@code {"$query": {...}}} or
 * {@code {"$patch": "CubePatch", "json": {...}}}. A value it can't encode is an error,
 * never a guess.
 */
public final class ConformanceCodec {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String MODEL = "net.ajmyers.tm1.client.model";

	private static final Map<Class<?>, Function<Object, JsonNode>> ENCODERS = new ConcurrentHashMap<>();

	private ConformanceCodec() {
	}

	/**
	 * Registers how to encode a helper type of a service package (a record with static
	 * factories, such as {@code AttributeOwner}): as {@code {"$type": "AttributeOwner",
	 * "factory": "view", "args": [...]}}, which every runner rebuilds by calling the same
	 * factory.
	 */
	public static void register(Class<?> type, Function<Object, JsonNode> encoder) {
		ENCODERS.put(type, encoder);
	}

	/** {@code {"$type": type, "factory": factory, "args": [encoded args]}}. */
	public static JsonNode factory(String type, String factory, @Nullable Object... args) {
		ObjectNode node = NODES.objectNode();
		node.put("$type", type);
		node.put("factory", factory);
		ArrayNode list = node.putArray("args");
		for (Object a : args) {
			list.add(arg(a));
		}
		return node;
	}

	/** An argument. */
	public static JsonNode arg(@Nullable Object value) {
		if (value == null) {
			return NODES.nullNode();
		}
		Function<Object, JsonNode> registered = ENCODERS.get(value.getClass());
		if (registered != null) {
			return registered.apply(value);
		}
		if (value instanceof String s) {
			return NODES.stringNode(s);
		}
		if (value instanceof Boolean b) {
			return NODES.booleanNode(b);
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short) {
			return NODES.numberNode(((Number) value).longValue());
		}
		if (value instanceof Double || value instanceof Float) {
			ObjectNode node = NODES.objectNode();
			node.put("$double", ((Number) value).doubleValue());
			return node;
		}
		if (value instanceof ODataQuery q) {
			return tagged("$query", query(q));
		}
		if (value instanceof ODataPath p) {
			return tagged("$path", NODES.stringNode(p.toString()));
		}
		if (value instanceof Duration d) {
			return tagged("$duration", NODES.stringNode(d.toString()));
		}
		if (value instanceof OffsetDateTime t) {
			return tagged("$dateTime", NODES.stringNode(t.toString()));
		}
		if (value instanceof LocalDate d) {
			return tagged("$date", NODES.stringNode(d.toString()));
		}
		if (value instanceof LocalTime t) {
			return tagged("$time", NODES.stringNode(t.toString()));
		}
		if (value instanceof byte[] bytes) {
			return tagged("$bytes", NODES.stringNode(Base64.getEncoder().encodeToString(bytes)));
		}
		if (value instanceof Enum<?> e) {
			ObjectNode node = NODES.objectNode();
			node.put("$enum", e.getDeclaringClass().getSimpleName());
			node.put("name", e.name());
			node.set("json", JSON.valueToTree(e));
			return node;
		}
		if (value instanceof Map<?, ?> map) {
			ObjectNode entries = NODES.objectNode();
			map.forEach((k, v) -> entries.set(String.valueOf(k), arg(v)));
			return tagged("$map", entries);
		}
		if (value instanceof Collection<?> list) {
			ArrayNode array = NODES.arrayNode();
			list.forEach(v -> array.add(arg(v)));
			return array;
		}
		Class<?> type = value.getClass();
		if (type.getPackageName().equals(MODEL) && type.getSimpleName().endsWith("Patch")) {
			ObjectNode node = NODES.objectNode();
			node.put("$patch", type.getSimpleName());
			node.set("json", JSON.valueToTree(value));
			return node;
		}
		if (type.getPackageName().equals(MODEL) && type.isRecord()) {
			ObjectNode node = NODES.objectNode();
			node.put("$model", type.getSimpleName());
			node.set("json", JSON.valueToTree(value));
			return node;
		}
		throw new IllegalArgumentException("Conformance can't encode a " + type.getName()
				+ ": register an encoder with ConformanceCodec.register, or leave this call out of the fixtures");
	}

	/**
	 * A call's result: a model as its JSON, a list for a {@code Flux}, null for nothing.
	 */
	public static JsonNode result(@Nullable Object value) {
		if (value == null) {
			return NODES.nullNode();
		}
		if (value instanceof List<?> list) {
			ArrayNode array = NODES.arrayNode();
			list.forEach(v -> array.add(result(v)));
			return array;
		}
		if (value instanceof Double || value instanceof Float) {
			return NODES.numberNode(((Number) value).doubleValue());
		}
		Class<?> type = value.getClass();
		if (type.getPackageName().equals(MODEL) && !type.isEnum()) {
			ObjectNode node = NODES.objectNode();
			node.put("$model", type.getSimpleName());
			node.set("json", JSON.valueToTree(value));
			return node;
		}
		return JSON.valueToTree(value);
	}

	/**
	 * How a call failed: its category, and for TM1's errors the kind, status and code.
	 */
	public static ObjectNode error(Throwable error) {
		ObjectNode node = NODES.objectNode();
		node.put("exception", error.getClass().getSimpleName());
		if (error instanceof Tm1ApiException api) {
			node.put("category", "api");
			node.put("kind", api.kind().name());
			node.put("status", api.status());
			if (api.error() != null && api.error().code() != null) {
				node.put("code", api.error().code());
			}
		}
		else if (error instanceof Tm1UnsupportedOperationException) {
			node.put("category", "unsupported");
		}
		else if (error instanceof Tm1ProtocolException) {
			node.put("category", "protocol");
		}
		else if (error instanceof Tm1TransportException) {
			node.put("category", "transport");
		}
		else if (error instanceof IllegalArgumentException || error instanceof NullPointerException) {
			node.put("category", "argument");
		}
		else if (error instanceof IllegalStateException) {
			node.put("category", "state");
		}
		else {
			throw new IllegalArgumentException("Conformance has no category for " + error, error);
		}
		return node;
	}

	private static ObjectNode tagged(String tag, JsonNode value) {
		ObjectNode node = NODES.objectNode();
		node.set(tag, value);
		return node;
	}

	/**
	 * A query as its parts, which every runner rebuilds with its own query builder:
	 * select, expand (each a query), filter (as written, rebuilt with
	 * {@code Filter.raw}), orderBy ({@code "Name"} or {@code "Name desc"}), top, skip,
	 * count, options, aliases.
	 */
	static ObjectNode query(ODataQuery q) {
		ObjectNode node = NODES.objectNode();
		List<String> select = get(q, "select");
		if (!select.isEmpty()) {
			ArrayNode s = node.putArray("select");
			select.forEach(s::add);
		}
		Map<String, ODataQuery> expand = get(q, "expand");
		if (!expand.isEmpty()) {
			ObjectNode e = node.putObject("expand");
			expand.forEach((name, nested) -> e.set(name, query(nested)));
		}
		Filter filter = get(q, "filter");
		if (filter != null) {
			node.put("filter", filter.toString());
		}
		List<String> orderBy = get(q, "orderBy");
		if (!orderBy.isEmpty()) {
			ArrayNode o = node.putArray("orderBy");
			orderBy.forEach(o::add);
		}
		Integer top = get(q, "top");
		if (top != null) {
			node.put("top", top);
		}
		Integer skip = get(q, "skip");
		if (skip != null) {
			node.put("skip", skip);
		}
		Boolean count = get(q, "count");
		if (count != null) {
			node.put("count", count);
		}
		Map<String, String> custom = get(q, "custom");
		if (!custom.isEmpty()) {
			ObjectNode c = node.putObject("options");
			custom.forEach(c::put);
		}
		Map<String, String> aliases = get(q, "aliases");
		if (!aliases.isEmpty()) {
			ObjectNode a = node.putObject("aliases");
			aliases.forEach(a::put);
		}
		return node;
	}

	@SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
	private static <T> T get(ODataQuery q, String field) {
		try {
			Field f = ODataQuery.class.getDeclaredField(field);
			f.setAccessible(true);
			return (T) f.get(q);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ODataQuery has no field " + field, e);
		}
	}

	/** The JSON mapper fixtures are written with. */
	static JsonMapper json() {
		return JSON;
	}

	/** For messages: a method's Java signature. */
	static String signature(Method m) {
		StringBuilder b = new StringBuilder(m.getName()).append('(');
		Class<?>[] types = m.getParameterTypes();
		for (int i = 0; i < types.length; i++) {
			b.append(i == 0 ? "" : ", ").append(types[i].getSimpleName());
		}
		return b.append(')').toString();
	}

}
