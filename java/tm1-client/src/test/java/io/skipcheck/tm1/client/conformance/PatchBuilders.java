// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.odata.ODataPath;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

/**
 * A write as the generated builders make it: {@code {"$patch": "CubeCreate", "json":
 * {...}}} through {@code CubeCreate.builder()}, each key by the setter that writes it, so
 * a scenario's write goes through the setters a caller uses (#354). A nested write type's
 * value is built by its own builder, a root's by the subtype its {@code @odata.type}
 * names; a binding ({@code Dimensions@odata.bind}) by the bind setter, from the paths; an
 * open type's undeclared property by {@code property}. A key no setter writes is refused:
 * the builders don't offer it, so neither may a scenario.
 */
final class PatchBuilders {

	private static final String PACKAGE = "io.skipcheck.tm1.client.model";

	/** Per builder class, the setter that writes each JSON name. */
	private static final Map<Class<?>, Map<String, Method>> SETTERS = new ConcurrentHashMap<>();

	private PatchBuilders() {
	}

	/**
	 * Builds a write.
	 * @param patch the write type, such as {@code CubeCreate}
	 * @param json what it should hold, by TM1's names
	 * @return the write, as its builder made it
	 * @throws IllegalArgumentException if a key has no setter, or a value doesn't fit it
	 */
	static Object build(Class<?> patch, JsonNode json) {
		Class<?> type = subtype(patch, json);
		try {
			Object builder = type.getMethod("builder").invoke(null);
			Map<String, Method> setters = setters(builder.getClass());
			for (var e : json.properties()) {
				String key = e.getKey();
				if (key.equals("@odata.type")) {
					// The builder sends its own; the subtype was picked by it
					continue;
				}
				Method setter = setters.get(key);
				if (setter != null) {
					setter.invoke(builder, argument(setter, e.getValue()));
				}
				else if (!key.startsWith("@") && open(builder.getClass())) {
					builder.getClass()
						.getMethod("property", String.class, Object.class)
						.invoke(builder, key, ConformanceCodec.json().treeToValue(e.getValue(), Object.class));
				}
				else {
					throw new IllegalArgumentException(type.getSimpleName() + " has no setter for " + key);
				}
			}
			return builder.getClass().getMethod("build").invoke(builder);
		}
		catch (InvocationTargetException e) {
			throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't build a " + type.getSimpleName(), e);
		}
	}

	/** A root's write type for a value naming its subtype, else the type itself. */
	private static Class<?> subtype(Class<?> patch, JsonNode json) {
		JsonNode id = json.get("@odata.type");
		if (id == null || !id.isString()) {
			return patch;
		}
		String suffix = patch.getSimpleName().endsWith("Create") ? "Create" : "Patch";
		String name = id.asString().substring(id.asString().lastIndexOf('.') + 1);
		try {
			Class<?> sub = Class.forName(PACKAGE + "." + name + suffix);
			if (!patch.isAssignableFrom(sub)) {
				throw new IllegalArgumentException(sub.getSimpleName() + " isn't a " + patch.getSimpleName());
			}
			return sub;
		}
		catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("No write type " + name + suffix + " for " + id.asString(), e);
		}
	}

	private static @Nullable Object[] argument(Method setter, JsonNode value) {
		Class<?> raw = setter.getParameterTypes()[0];
		if (raw == ODataPath[].class) {
			List<ODataPath> paths = new ArrayList<>();
			value.forEach(p -> paths.add(path(p.asString())));
			return new Object[] { paths.toArray(ODataPath[]::new) };
		}
		if (raw == ODataPath.class) {
			return new Object[] { path(value.asString()) };
		}
		return new Object[] { value(setter.getGenericParameterTypes()[0], value) };
	}

	/**
	 * A binding's path, which the JSON holds as sent, percent-encoded: decoded, so the
	 * bind setter encodes it once, as a caller's {@code ODataPath} would be.
	 */
	private static ODataPath path(String encoded) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < encoded.length(); i++) {
			char c = encoded.charAt(i);
			if (c == '%' && i + 2 < encoded.length()) {
				out.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
				i += 2;
			}
			else {
				out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
			}
		}
		return ODataPath.link(out.toString(StandardCharsets.UTF_8));
	}

	private static @Nullable Object value(Type type, JsonNode value) {
		if (value.isNull()) {
			return null;
		}
		Class<?> write = write(type);
		if (write != null) {
			return build(write, value);
		}
		if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
			Class<?> element = write(p.getActualTypeArguments()[0]);
			if (element != null) {
				List<Object> out = new ArrayList<>();
				value.forEach(v -> out.add(build(element, v)));
				return out;
			}
		}
		JavaType javaType = ConformanceCodec.json().getTypeFactory().constructType(type);
		return ConformanceCodec.json().treeToValue(value, javaType);
	}

	/** The write type a type is, or a list's element is, or null. */
	private static @Nullable Class<?> write(Type type) {
		Type t = type instanceof WildcardType w ? w.getUpperBounds()[0] : type;
		if (t instanceof Class<?> c && c.getPackageName().equals(PACKAGE)
				&& (c.getSimpleName().endsWith("Patch") || c.getSimpleName().endsWith("Create"))) {
			return c;
		}
		return null;
	}

	private static boolean open(Class<?> builder) {
		return Arrays.stream(builder.getMethods())
			.anyMatch(m -> m.getName().equals("property") && m.getParameterCount() == 2);
	}

	/**
	 * The setters of a builder, by the JSON name each writes: each called once on a fresh
	 * builder, as the builder manifest finds them.
	 */
	private static Map<String, Method> setters(Class<?> builder) {
		return SETTERS.computeIfAbsent(builder, b -> {
			try {
				Class<?> write = b.getEnclosingClass();
				Method make = write.getMethod("builder");
				Map<String, @Nullable Object> base = values(make.invoke(null));
				Map<String, Method> out = new LinkedHashMap<>();
				for (Method m : b.getMethods()) {
					if (m.isBridge() || m.isSynthetic() || Modifier.isStatic(m.getModifiers())
							|| !m.getReturnType().isAssignableFrom(b) || m.getName().equals("property")
							|| m.getParameterCount() != 1) {
						continue;
					}
					Object fresh = make.invoke(null);
					m.invoke(fresh, dummy(m.getParameterTypes()[0]));
					List<String> wrote = new ArrayList<>(values(fresh).keySet());
					wrote.removeAll(base.keySet());
					if (wrote.size() != 1) {
						throw new IllegalStateException(b.getName() + "." + m.getName() + " writes " + wrote);
					}
					out.put(wrote.get(0), m);
				}
				return out;
			}
			catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Can't read the setters of " + b.getName(), e);
			}
		});
	}

	private static @Nullable Object dummy(Class<?> type) {
		if (type == ODataPath[].class) {
			return new ODataPath[0];
		}
		if (type == ODataPath.class) {
			return ODataPath.of("x");
		}
		if (type == boolean.class) {
			return false;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, @Nullable Object> values(Object builder) throws ReflectiveOperationException {
		for (Class<?> c = builder.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField("values");
				f.setAccessible(true);
				return new LinkedHashMap<>((Map<String, @Nullable Object>) f.get(builder));
			}
			catch (NoSuchFieldException e) {
				// declared by a superclass
			}
		}
		throw new IllegalStateException("No values in " + builder.getClass());
	}

}
