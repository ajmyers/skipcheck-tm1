// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import io.skipcheck.tm1.client.odata.ODataPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What every generated create and patch builder offers, which every client's builders
 * must match ({@code spec/conformance/builders.json}): for each {@code XPatch} and
 * {@code XCreate}, its {@code @odata.type}, the JSON names its setters write, the write
 * type each setter of a structured value takes ({@code "Edges": "EdgeCreate[]"}), the
 * properties its {@code build()} requires, and whether it takes undeclared properties.
 * Each client drives its own generated builders to find them, so the check is on what
 * each generator emitted, not on a restatement of its rules. This client's builders are
 * the reference: with {@code -PrecordConformance} the test rewrites the manifest from
 * them. Every client has this test.
 */
class BuilderManifestTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path SPEC = Path.of(System.getProperty("tm1.spec"));

	private static final Path MANIFEST = SPEC.resolveSibling("conformance/builders.json");

	private static final boolean RECORD = Boolean.getBoolean("tm1.conformance.record");

	private static final String BIND = "@odata.bind";

	@Test
	void everyBuilderOffersWhatTheManifestLists() throws Exception {
		TreeSet<String> names = new TreeSet<>();
		for (JsonNode type : JSON.readTree(SPEC.toFile()).path("types")) {
			for (String suffix : List.of("Patch", "Create")) {
				String name = type.path("name").asString() + suffix;
				if (exists(name)) {
					names.add(name);
				}
			}
		}
		ObjectNode manifest = JSON.createObjectNode();
		manifest.put("description",
				"What every generated create and patch builder offers: written by the Java client's BuilderManifestTest with -PrecordConformance, checked by every client's.");
		ObjectNode builders = manifest.putObject("builders");
		for (String name : names) {
			builders.set(name, describe(Class.forName(PACKAGE + "." + name)));
		}
		String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n";
		if (RECORD) {
			Files.writeString(MANIFEST, text, StandardCharsets.UTF_8);
			return;
		}
		String rerecord = " (run ./gradlew :tm1-client:test -PrecordConformance, then check the other clients match it)";
		if (!Files.exists(MANIFEST)) {
			throw new AssertionFailedError("No builder manifest " + MANIFEST + rerecord);
		}
		String existing = Files.readString(MANIFEST, StandardCharsets.UTF_8);
		if (!existing.equals(text)) {
			throw new AssertionFailedError("The builder manifest " + MANIFEST + " is out of date" + rerecord, existing,
					text);
		}
		assertThat(names).contains("CubePatch", "CubeCreate", "MDXViewCreate");
	}

	private static final String PACKAGE = Cube.class.getPackageName();

	private static boolean exists(String name) {
		try {
			Class.forName(PACKAGE + "." + name);
			return true;
		}
		catch (ClassNotFoundException e) {
			return false;
		}
	}

	private static ObjectNode describe(Class<?> patch) throws ReflectiveOperationException {
		Method builder = patch.getMethod("builder");
		Map<String, @Nullable Object> base = values(builder.invoke(null));
		// Each setter on a builder of its own: the name it writes
		Map<Method, String> setters = new LinkedHashMap<>();
		TreeMap<String, String> valueTypes = new TreeMap<>();
		for (Method m : setters(builder.invoke(null).getClass())) {
			Object b = builder.invoke(null);
			m.invoke(b, arguments(m));
			List<String> wrote = new ArrayList<>(values(b).keySet());
			wrote.removeAll(base.keySet());
			assertThat(wrote).as(patch.getSimpleName() + "." + m.getName() + " writes one property").hasSize(1);
			setters.put(m, wrote.get(0));
			String value = valueType(m);
			if (value != null) {
				valueTypes.put(wrote.get(0), value);
			}
		}
		// A property is required when a builder with every other setter called refuses to
		// build
		TreeSet<String> required = new TreeSet<>();
		for (String group : new TreeSet<>(setters.values().stream().map(BuilderManifestTest::bare).toList())) {
			Object b = builder.invoke(null);
			for (Map.Entry<Method, String> s : setters.entrySet()) {
				if (!bare(s.getValue()).equals(group)) {
					s.getKey().invoke(b, arguments(s.getKey()));
				}
			}
			try {
				b.getClass().getMethod("build").invoke(b);
			}
			catch (InvocationTargetException e) {
				assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
				required.add(group);
			}
		}
		ObjectNode out = JSON.createObjectNode();
		Object type = base.get("@odata.type");
		if (type == null) {
			out.putNull("odataType");
		}
		else {
			out.put("odataType", type.toString());
		}
		out.put("open", Arrays.stream(builder.invoke(null).getClass().getMethods())
			.anyMatch(m -> m.getName().equals("property") && m.getParameterCount() == 2));
		ArrayNode req = out.putArray("required");
		required.forEach(req::add);
		ArrayNode names = out.putArray("setters");
		new TreeSet<>(setters.values()).forEach(names::add);
		ObjectNode values = out.putObject("values");
		valueTypes.forEach(values::put);
		return out;
	}

	/**
	 * The write type a setter takes, as {@code XPatch} or {@code XCreate}, with
	 * {@code []} for a list of them; null for anything else (a primitive, a binding's
	 * path).
	 */
	private static @Nullable String valueType(Method m) {
		if (m.getParameterCount() != 1) {
			return null;
		}
		Type t = m.getGenericParameterTypes()[0];
		String suffix = "";
		if (t instanceof ParameterizedType p && p.getRawType() == List.class) {
			t = p.getActualTypeArguments()[0];
			suffix = "[]";
			if (t instanceof WildcardType w) {
				t = w.getUpperBounds()[0];
			}
		}
		if (t instanceof Class<?> c && c.getPackageName().equals(PACKAGE)
				&& (c.getSimpleName().endsWith("Patch") || c.getSimpleName().endsWith("Create"))) {
			return c.getSimpleName() + suffix;
		}
		return null;
	}

	private static List<Method> setters(Class<?> builder) {
		return Arrays.stream(builder.getMethods())
			.filter(m -> !m.isBridge() && !m.isSynthetic() && !Modifier.isStatic(m.getModifiers())
					&& m.getReturnType().isAssignableFrom(builder) && !m.getName().equals("property"))
			.sorted((a, b) -> a.getName().compareTo(b.getName()))
			.toList();
	}

	private static @Nullable Object[] arguments(Method m) {
		Class<?>[] types = m.getParameterTypes();
		@Nullable Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			if (types[i] == ODataPath[].class) {
				args[i] = new ODataPath[0];
			}
			else if (types[i] == ODataPath.class) {
				args[i] = ODataPath.of("x");
			}
			else if (types[i] == boolean.class) {
				args[i] = false;
			}
		}
		return args;
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

	private static String bare(String name) {
		return name.endsWith(BIND) ? name.substring(0, name.length() - BIND.length()) : name;
	}

}
