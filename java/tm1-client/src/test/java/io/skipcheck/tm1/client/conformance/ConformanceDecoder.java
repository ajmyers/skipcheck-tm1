// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/**
 * The Java side of the neutral encoding's other direction: an encoded argument, as the
 * parameter it goes to takes it. The live conformance runner uses it to run a scenario's
 * hand-written steps, which the replay runners of the other clients decode the same way.
 */
public final class ConformanceDecoder {

	private ConformanceDecoder() {
	}

	/** {@code client.<service>().<method>} whose parameters are named {@code params}. */
	public static Method method(Class<?> service, String name, List<String> params) {
		List<Method> fits = Arrays.stream(service.getMethods())
			.filter(m -> m.getName().equals(name) && Modifier.isPublic(m.getModifiers()))
			.filter(m -> Arrays.stream(m.getParameters()).map(Parameter::getName).toList().equals(params))
			.toList();
		if (fits.size() != 1) {
			throw new IllegalArgumentException(
					fits.size() + " methods " + service.getSimpleName() + "." + name + params);
		}
		return fits.get(0);
	}

	/** The arguments for {@code method}, decoded by its parameter types. */
	public static @Nullable Object[] args(Method method, JsonNode args) {
		Type[] types = method.getGenericParameterTypes();
		if (args.size() != types.length) {
			throw new IllegalArgumentException(method + " takes " + types.length + " arguments, not " + args.size());
		}
		Object[] out = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			out[i] = decode(args.get(i), types[i]);
		}
		return out;
	}

	public static @Nullable Object decode(@Nullable JsonNode node, Type type) {
		Class<?> raw = raw(type);
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isArray()) {
			Type element = type instanceof ParameterizedType p ? p.getActualTypeArguments()[0] : Object.class;
			List<Object> list = new ArrayList<>();
			node.forEach(item -> list.add(decode(item, element)));
			return list;
		}
		if (node.isString()) {
			return node.asString();
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isNumber()) {
			if (raw == int.class || raw == Integer.class) {
				return node.asInt();
			}
			if (raw == long.class || raw == Long.class) {
				return node.asLong();
			}
			return node.asDouble();
		}
		String tag = node.propertyNames().iterator().next();
		JsonNode body = node.get(tag);
		return switch (tag) {
			case "$double" -> body.asDouble();
			case "$query" -> query(body);
			case "$path" -> ODataPath.link(body.asString());
			case "$map" -> {
				Type value = type instanceof ParameterizedType p ? p.getActualTypeArguments()[1] : Object.class;
				Map<String, Object> map = new LinkedHashMap<>();
				body.properties().forEach(e -> map.put(e.getKey(), decode(e.getValue(), value)));
				yield map;
			}
			case "$patch" -> patch(model(body.asString()), node.get("json"));
			case "$model" -> ConformanceCodec.json().treeToValue(node.get("json"), model(body.asString()));
			case "$enum" -> constant(raw, type, body.asString(), node.get("name").asString());
			case "$duration" -> Duration.parse(body.asString());
			case "$dateTime" -> OffsetDateTime.parse(body.asString());
			case "$date" -> LocalDate.parse(body.asString());
			case "$time" -> LocalTime.parse(body.asString());
			case "$bytes" -> bytes(raw, Base64.getDecoder().decode(body.asString()));
			case "$handler" -> ConformanceHandler.named(body.asString());
			case "$type" -> factory(raw, node.get("factory").asString(), node.get("args"));
			default -> throw new IllegalArgumentException("Unknown tag " + tag);
		};
	}

	private static Object patch(Class<?> patch, JsonNode json) {
		try {
			Constructor<?> ctor = patch.getDeclaredConstructor(Map.class);
			ctor.setAccessible(true);
			return ctor.newInstance(ConformanceCodec.json().treeToValue(json, LinkedHashMap.class));
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't make a " + patch.getSimpleName(), e);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object constant(Class<?> raw, Type type, String name, String constant) {
		if (!raw.isEnum() || !raw.getSimpleName().equals(name)) {
			throw new IllegalArgumentException("enum " + name + " for a " + type);
		}
		return Enum.valueOf((Class) raw, constant);
	}

	/**
	 * Bytes, or for a {@code Publisher<DataBuffer>} a publisher of them that can be
	 * subscribed again.
	 */
	private static Object bytes(Class<?> raw, byte[] bytes) {
		if (Publisher.class.isAssignableFrom(raw)) {
			return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes.clone())));
		}
		return bytes;
	}

	/**
	 * A helper type by its static factory; {@code new} for its constructor; or an
	 * instance method, such as {@code ContentPath.child}, called on the first argument.
	 */
	private static @Nullable Object factory(Class<?> raw, String factory, JsonNode args) {
		List<Executable> candidates = new ArrayList<>();
		if (factory.equals("new")) {
			Arrays.stream(raw.getConstructors())
				.filter(c -> c.getParameterCount() == args.size())
				.forEach(candidates::add);
		}
		else {
			Arrays.stream(raw.getMethods())
				.filter(x -> Modifier.isStatic(x.getModifiers()) && x.getName().equals(factory)
						&& x.getParameterCount() == args.size())
				.forEach(candidates::add);
			if (candidates.isEmpty() && !args.isEmpty()) {
				Arrays.stream(raw.getMethods())
					.filter(x -> !Modifier.isStatic(x.getModifiers()) && x.getName().equals(factory)
							&& x.getParameterCount() == args.size() - 1)
					.forEach(candidates::add);
			}
		}
		for (Executable candidate : candidates) {
			boolean instance = candidate instanceof Method m && !Modifier.isStatic(m.getModifiers());
			Object receiver = instance ? decode(args.get(0), raw) : null;
			Type[] types = candidate.getGenericParameterTypes();
			Object[] values = new Object[types.length];
			boolean fits = true;
			for (int i = 0; i < types.length && fits; i++) {
				values[i] = decode(args.get(instance ? i + 1 : i), types[i]);
				Class<?> type = raw(types[i]);
				fits = values[i] == null ? !type.isPrimitive() : type.isPrimitive() || type.isInstance(values[i]);
			}
			if (!fits) {
				continue;
			}
			try {
				return candidate instanceof Constructor<?> c ? c.newInstance(values)
						: ((Method) candidate).invoke(receiver, values);
			}
			catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		throw new IllegalArgumentException("No factory " + raw.getSimpleName() + "." + factory + " takes " + args);
	}

	/** A query rebuilt from its parts, as every runner rebuilds it. */
	public static ODataQuery query(JsonNode parts) {
		ODataQuery q = ODataQuery.none();
		for (String key : parts.propertyNames()) {
			if (!List.of("select", "expand", "filter", "orderBy", "top", "skip", "count", "options", "aliases")
				.contains(key)) {
				throw new IllegalArgumentException("Unknown query part " + key);
			}
		}
		if (parts.has("select")) {
			List<String> select = new ArrayList<>();
			parts.get("select").forEach(s -> select.add(s.asString()));
			q = q.select(select.toArray(String[]::new));
		}
		if (parts.has("expand")) {
			for (var e : parts.get("expand").properties()) {
				JsonNode nested = e.getValue();
				UnaryOperator<ODataQuery> options = inner -> query(nested);
				q = nested.isEmpty() ? q.expand(e.getKey()) : q.expand(e.getKey(), options);
			}
		}
		if (parts.has("filter")) {
			q = q.filter(Filter.raw(parts.get("filter").asString()));
		}
		if (parts.has("orderBy")) {
			for (JsonNode k : parts.get("orderBy")) {
				String key = k.asString();
				q = key.endsWith(" desc") ? q.orderByDescending(key.substring(0, key.length() - 5)) : q.orderBy(key);
			}
		}
		if (parts.has("top")) {
			q = q.top(parts.get("top").asInt());
		}
		if (parts.has("skip")) {
			q = q.skip(parts.get("skip").asInt());
		}
		if (parts.path("count").asBoolean(false)) {
			q = q.withCount();
		}
		if (parts.has("options")) {
			for (var e : parts.get("options").properties()) {
				q = q.option(e.getKey(), e.getValue().asString());
			}
		}
		if (parts.has("aliases")) {
			for (var e : parts.get("aliases").properties()) {
				q = q.alias(new ODataPath.Alias(e.getKey()), e.getValue().asString());
			}
		}
		return q;
	}

	private static Class<?> model(String name) {
		try {
			return Class.forName("io.skipcheck.tm1.client.model." + name);
		}
		catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("No model type " + name, e);
		}
	}

	private static Class<?> raw(Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType p) {
			return (Class<?>) p.getRawType();
		}
		return Object.class;
	}

}
