// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.odata.ODataPath;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

/**
 * A write as the generated builders make it: {@code {"$patch": "CubeCreate", "json":
 * {...}}} through {@code CubeCreate.builder()}, each key by the setter that writes it, so
 * a scenario's write goes through the setters a caller uses (#354). A nested write type's
 * value is built by its own builder, a root's by the subtype its {@code @odata.type}
 * names; a binding ({@code Dimensions@odata.bind}) by the bind setter, from the paths as
 * written, already encoded, as TM1 gets them; an open type's undeclared property by
 * {@code property}. Refused, as a caller couldn't send them: a key no setter writes (a
 * declared one included, on an open type too); a value that doesn't read back as written
 * (an enum TM1 doesn't have, a duration not written as the client writes it, a fraction
 * for a whole number); a binding of the wrong shape; an {@code @odata.type} the write
 * type doesn't send.
 */
final class PatchBuilders {

	private static final String PACKAGE = "io.skipcheck.tm1.client.model";

	private static final JsonNode SPEC = ConformanceCodec.json()
		.readTree(new File(Objects.requireNonNull(System.getProperty("tm1.spec"), "tm1.spec")));

	private static final Map<Class<?>, Map<String, JsonNode>> DECLARED = new ConcurrentHashMap<>();

	private static final Map<String, JsonNode> ENUMS = new LinkedHashMap<>();

	static {
		SPEC.path("enums").forEach(e -> ENUMS.put(e.path("qualifiedName").asString(), e));
	}

	private static final String BIND = "@odata.bind";

	/**
	 * A path segment encoded as a client encodes one: unreserved characters, or a %XX.
	 */
	private static final Pattern ENCODED = Pattern.compile("([A-Za-z0-9\\-._~'()=,@$!]|%[0-9A-F]{2})*");

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
		return build(patch, json, null);
	}

	/**
	 * Builds a write from what was read, as a caller changes an object:
	 * {@code builder(read)}, then the setters {@code json} names, as
	 * {@link #build(Class, JsonNode)} applies them.
	 * @param patch the write type, such as {@code CubePatch}
	 * @param json the changes, by TM1's names
	 * @param read what was read, which {@code builder(read)} starts from; null for
	 * {@code builder()}
	 * @return the write, as its builder made it
	 * @throws IllegalArgumentException if the write type has no {@code builder(read)} for
	 * it, a key has no setter, or a value doesn't fit it
	 */
	static Object build(Class<?> patch, JsonNode json, @Nullable Object read) {
		if (!json.isObject()) {
			throw new IllegalArgumentException("A " + patch.getSimpleName() + " is an object, not " + json);
		}
		Class<?> type = read == null ? subtype(patch, json) : patch;
		try {
			Object builder = read == null ? type.getMethod("builder").invoke(null)
					: fromRead(type, read).invoke(null, read);
			Map<String, Method> setters = setters(builder.getClass());
			for (var e : json.properties()) {
				String key = e.getKey();
				if (key.equals("@odata.type")) {
					// The builder sends its own, which must be this one
					if (!e.getValue().isString()
							|| !e.getValue().asString().equals(values(builder).get("@odata.type"))) {
						throw new IllegalArgumentException(
								type.getSimpleName() + " doesn't send @odata.type " + e.getValue());
					}
					continue;
				}
				Method setter = setters.get(key);
				if (setter != null) {
					if (!key.endsWith(BIND)) {
						check(type, key, e.getValue());
					}
					setter.invoke(builder, argument(setter, e.getValue()));
				}
				else if (!key.startsWith("@") && open(builder.getClass())
						&& !declared(type).containsKey(key.replace(BIND, ""))) {
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

	/** A write type's {@code builder(read)}. */
	private static Method fromRead(Class<?> type, Object read) {
		for (Method m : type.getMethods()) {
			if (m.getName().equals("builder") && m.getParameterCount() == 1 && Modifier.isStatic(m.getModifiers())
					&& m.getParameterTypes()[0].isInstance(read)) {
				return m;
			}
		}
		throw new IllegalArgumentException(
				type.getSimpleName() + " has no builder(read) for a " + read.getClass().getSimpleName());
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
			if (!value.isArray()) {
				throw new IllegalArgumentException(setter.getName() + " binds a list of paths, not " + value);
			}
			List<ODataPath> paths = new ArrayList<>();
			value.forEach(p -> paths.add(path(setter, p)));
			return new Object[] { paths.toArray(ODataPath[]::new) };
		}
		if (raw == ODataPath.class) {
			return new Object[] { path(setter, value) };
		}
		return new Object[] { value(setter.getGenericParameterTypes()[0], value) };
	}

	/**
	 * A binding's path as written, already encoded, as TM1 gets it: its segments taken as
	 * they are, so a key's {@code %2F} stays one, and the bind setter writes it back the
	 * same.
	 */
	private static ODataPath path(Method setter, JsonNode value) {
		if (!value.isString()) {
			throw new IllegalArgumentException(setter.getName() + " binds a path, not " + value);
		}
		for (String segment : value.asString().split("/", -1)) {
			if (!ENCODED.matcher(segment).matches()) {
				throw new IllegalArgumentException(value + " isn't encoded as TM1 gets it: " + segment);
			}
		}
		try {
			Constructor<ODataPath> make = ODataPath.class.getDeclaredConstructor(List.class);
			make.setAccessible(true);
			return make.newInstance(List.of(value.asString().split("/", -1)));
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't make a path", e);
		}
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
				if (!value.isArray()) {
					throw new IllegalArgumentException(
							"A list of " + element.getSimpleName() + " is a list, not " + value);
				}
				List<Object> out = new ArrayList<>();
				value.forEach(v -> out.add(build(element, v)));
				return out;
			}
		}
		Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
		if (raw == OffsetDateTime.class) {
			// As a caller gives it, its offset kept
			return OffsetDateTime.parse(value.asString());
		}
		if ((raw == Integer.class || raw == Long.class) && !value.canConvertToExactIntegral()) {
			throw new IllegalArgumentException(value + " isn't a whole number");
		}
		JavaType javaType = ConformanceCodec.json().getTypeFactory().constructType(type);
		Object out = ConformanceCodec.json().treeToValue(value, javaType);
		if (raw.isEnum() || raw == Duration.class) {
			// What the client sends for it: an enum TM1 doesn't have reads as UNKNOWN, a
			// duration as the client writes durations
			JsonNode back = ConformanceCodec.json().valueToTree(out);
			if (!back.equals(value)) {
				throw new IllegalArgumentException(value + " doesn't read back as written, but as " + back);
			}
		}
		return out;
	}

	/**
	 * A value's shape and, for an enum, its members, by the spec, as every runner checks
	 * them: one value or a list, as the plan's setter takes; an enum's value one TM1 has
	 * (a flags enum's, each of its comma-separated members), which the typed setters of a
	 * flags enum, taking text, wouldn't check.
	 */
	private static void check(Class<?> write, String key, JsonNode value) {
		if (value.isNull()) {
			return;
		}
		JsonNode setter = null;
		for (JsonNode s : SPEC.path("builders").path(write.getSimpleName()).path("setters")) {
			if (s.path("json").asString().equals(key)) {
				setter = s;
			}
		}
		if (setter != null && setter.path("collection").asBoolean() != value.isArray()) {
			throw new IllegalArgumentException(write.getSimpleName() + "." + key + " takes "
					+ (setter.path("collection").asBoolean() ? "a list" : "one value") + ", not " + value);
		}
		JsonNode property = declared(write).get(key);
		JsonNode enumType = property == null ? null : ENUMS.get(property.path("type").asString());
		if (enumType == null) {
			return;
		}
		Set<String> members = new HashSet<>();
		enumType.path("members").forEach(m -> members.add(m.path("name").asString()));
		for (JsonNode v : value.isArray() ? value : List.of(value)) {
			List<String> parts = !v.isString() ? List.of() : enumType.path("flags").asBoolean()
					? Arrays.stream(v.asString().split(",")).map(String::trim).toList() : List.of(v.asString());
			if (parts.isEmpty() || !members.containsAll(parts)) {
				throw new IllegalArgumentException(v + " doesn't read back as written: "
						+ enumType.path("name").asString() + " has no such member");
			}
		}
	}

	/**
	 * The properties and navigations a write's type declares, its base's too, by name.
	 */
	private static Map<String, JsonNode> declared(Class<?> write) {
		return DECLARED.computeIfAbsent(write, w -> {
			String name = w.getSimpleName().replaceAll("(Create|Patch)$", "");
			Map<String, JsonNode> byName = new LinkedHashMap<>();
			SPEC.path("types").forEach(t -> byName.put(t.path("qualifiedName").asString(), t));
			Map<String, JsonNode> out = new LinkedHashMap<>();
			for (JsonNode t : byName.values()) {
				if (t.path("name").asString().equals(name)) {
					for (JsonNode level = t; level != null; level = byName.get(level.path("baseType").asString(""))) {
						level.path("properties").forEach(p -> out.putIfAbsent(p.path("name").asString(), p));
						level.path("navigationProperties").forEach(p -> out.putIfAbsent(p.path("name").asString(), p));
					}
				}
			}
			return out;
		});
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
