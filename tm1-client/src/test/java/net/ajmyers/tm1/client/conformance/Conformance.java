package net.ajmyers.tm1.client.conformance;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.commons.annotation.Testable;
import org.opentest4j.AssertionFailedError;
import org.springframework.core.io.buffer.DataBuffer;

import net.ajmyers.tm1.client.Tm1Client;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records a test class's service calls as a conformance fixture,
 * {@code spec/conformance/calls/<TestClass>.json}, which every other client replays.
 *
 * <pre>
 * &#64;RegisterExtension
 * static final Conformance conformance = new Conformance();
 *
 * &#64;Test
 * void getsACube() {
 *     conformance.tm1().json("{\"Name\":\"Sales\"}");
 *     Cube cube = conformance.call("cubes", "get", "Sales", ODataQuery.none().select("Name"));
 *     assertThat(cube.name()).isEqualTo("Sales");
 *     assertThat(conformance.tm1().last().target()).isEqualTo("Cubes('Sales')?$select=Name");
 * }
 * </pre>
 *
 * A call goes through {@link #call} (or {@link #fail} when it should fail): the service
 * and method by name, so what's recorded is exactly what ran. Each test gets a fresh
 * client and {@link ConformanceTm1}. Without {@code -Dtm1.conformance.record=true} the
 * class's fixture must equal what its tests record, so a change of behaviour shows up as
 * a failing build until the fixture is re-recorded (and then as a diff every other client
 * must match).
 */
public final class Conformance implements BeforeEachCallback, AfterAllCallback, TestWatcher {

	/** Fixtures' format version; runners refuse one they don't know. */
	public static final int SPEC_VERSION = 1;

	private static final boolean RECORD = Boolean.getBoolean("tm1.conformance.record");

	private final List<ObjectNode> cases = new ArrayList<>();

	private @Nullable ConformanceTm1 tm1;

	private @Nullable Tm1Client client;

	private String test = "";

	private int n;

	private final List<String> failed = new ArrayList<>();

	private final Set<String> ran = new TreeSet<>();

	private @Nullable Class<?> testClass;

	/** How many items {@link #take} takes of the call's {@code Flux}; null for all. */
	private @Nullable Integer taking;

	@Override
	public void beforeEach(ExtensionContext context) {
		testClass = context.getRequiredTestClass();
		test = context.getRequiredTestMethod().getName();
		ran.add(test);
		n = 0;
		tm1 = new ConformanceTm1();
		client = tm1.client();
	}

	/** This test's fake TM1: script its answers, read its requests. */
	public ConformanceTm1 tm1() {
		if (tm1 == null) {
			throw new IllegalStateException("Register Conformance with @RegisterExtension on a static field");
		}
		return tm1;
	}

	/** This test's client, for anything that isn't a recorded call. */
	public Tm1Client client() {
		tm1();
		return client;
	}

	/**
	 * Calls {@code client.<service>().<method>(args)}, answering with what's scripted,
	 * and records it: the arguments, every request and answer, and the result. Returns
	 * the {@code Mono}'s value (null if empty) or the {@code Flux}'s items as a list.
	 */
	@SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
	public <T> T call(String service, String method, @Nullable Object... args) {
		Outcome outcome = invoke(service, method, args);
		if (outcome.error() != null) {
			record(service, method, args, outcome, outcome.error());
			throw new AssertionFailedError(service + "." + method + " failed: " + outcome.error(), outcome.error());
		}
		record(service, method, args, outcome, null);
		return (T) outcome.value();
	}

	/**
	 * Calls as {@link #call} a method whose {@code Flux} doesn't end by itself, such as a
	 * log tail: takes its first {@code n} items, then cancels it. Records
	 * {@code "take": n}, which every runner replays by stopping after as many items.
	 */
	public <T> List<T> take(int n, String service, String method, @Nullable Object... args) {
		taking = n;
		try {
			return call(service, method, args);
		}
		finally {
			taking = null;
		}
	}

	/** Calls as {@link #call}, which must fail; records how, and returns the error. */
	public Throwable fail(String service, String method, @Nullable Object... args) {
		Outcome outcome = invoke(service, method, args);
		if (outcome.error() == null) {
			throw new AssertionFailedError(service + "." + method + " should have failed, but gave " + outcome.value());
		}
		record(service, method, args, outcome, outcome.error());
		return outcome.error();
	}

	private record Outcome(Method method, @Nullable Object value, @Nullable Throwable error) {
	}

	private Outcome invoke(String service, String method, @Nullable Object[] args) {
		ConformanceTm1 fake = tm1();
		fake.takeExchanges();
		Object target;
		try {
			target = Tm1Client.class.getMethod(service).invoke(client);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Tm1Client has no service " + service, e);
		}
		Method m = resolve(target.getClass(), method, args);
		Object returned;
		try {
			returned = m.invoke(target, args);
		}
		catch (InvocationTargetException e) {
			return new Outcome(m, null, e.getCause());
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		try {
			Object value;
			if (returned instanceof Mono<?> mono) {
				value = mono.block();
			}
			else if (returned instanceof Flux<?> flux && bytes(m)) {
				// A stream of bytes, such as a document's content: recorded as its bytes,
				// however they're split
				value = ConformanceCodec.bytes(flux);
			}
			else if (returned instanceof Flux<?> flux) {
				value = (taking == null ? flux : flux.take(taking)).collectList().block();
			}
			else {
				value = returned;
			}
			if (fake.unanswered() > 0) {
				throw new AssertionFailedError(fake.unanswered() + " scripted answer(s) left over after " + service
						+ "." + method + ": the call sent fewer requests than scripted");
			}
			return new Outcome(m, value, null);
		}
		catch (RuntimeException e) {
			return new Outcome(m, null, Exceptions.unwrap(e));
		}
	}

	/** Whether the method gives a {@code Flux<DataBuffer>}. */
	static boolean bytes(Method m) {
		return m.getGenericReturnType() instanceof ParameterizedType p
				&& p.getActualTypeArguments()[0] == DataBuffer.class;
	}

	/**
	 * The one public method of that name the arguments fit; more than one is an error.
	 */
	static Method resolve(Class<?> type, String name, @Nullable Object[] args) {
		List<Method> fits = Arrays.stream(type.getMethods())
			.filter(m -> m.getName().equals(name) && Modifier.isPublic(m.getModifiers()) && fits(m, args))
			.toList();
		if (fits.size() != 1) {
			throw new IllegalArgumentException(
					fits.size() + " methods " + type.getSimpleName() + "." + name + " fit " + Arrays.toString(args)
							+ (fits.isEmpty() ? "" : ": " + fits.stream().map(ConformanceCodec::signature).toList()));
		}
		return fits.get(0);
	}

	private static boolean fits(Method m, @Nullable Object[] args) {
		Class<?>[] types = m.getParameterTypes();
		if (types.length != args.length) {
			return false;
		}
		for (int i = 0; i < types.length; i++) {
			Class<?> t = types[i].isPrimitive() ? boxed(types[i]) : types[i];
			if (args[i] == null ? types[i].isPrimitive() : !t.isInstance(args[i])) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> boxed(Class<?> primitive) {
		return Map
			.<Class<?>, Class<?>>of(int.class, Integer.class, long.class, Long.class, double.class, Double.class,
					boolean.class, Boolean.class, float.class, Float.class, short.class, Short.class)
			.get(primitive);
	}

	private void record(String service, String method, @Nullable Object[] args, Outcome outcome,
			@Nullable Throwable error) {
		ObjectNode c = ConformanceCodec.json().createObjectNode();
		n++;
		c.put("id", testClass.getSimpleName() + "#" + test + "#" + n);
		c.put("service", service);
		c.put("method", method);
		ArrayNode p = c.putArray("params");
		for (Parameter parameter : outcome.method().getParameters()) {
			p.add(parameter.getName());
		}
		ArrayNode a = c.putArray("args");
		for (Object arg : args) {
			a.add(ConformanceCodec.arg(arg));
		}
		if (taking != null) {
			c.put("take", taking);
		}
		ArrayNode exchanges = c.putArray("exchanges");
		tm1().takeExchanges().forEach(exchanges::add);
		if (error != null) {
			c.set("error", ConformanceCodec.error(error));
		}
		else {
			c.set("result", ConformanceCodec.result(outcome.value()));
		}
		cases.add(c);
	}

	@Override
	public void testFailed(ExtensionContext context, @Nullable Throwable cause) {
		failed.add(context.getDisplayName());
	}

	@Override
	public void afterAll(ExtensionContext context) throws IOException {
		Class<?> type = context.getRequiredTestClass();
		if (!failed.isEmpty()) {
			// A failing test's calls aren't the client's behaviour: neither write nor
			// check them
			return;
		}
		Set<String> declared = tests(type);
		if (!ran.equals(declared)) {
			if (RECORD) {
				throw new AssertionFailedError(
						"Record a conformance fixture from the whole class: only " + ran + " of " + declared + " ran");
			}
			// Some of the class's tests were filtered out: the fixture is checked when
			// they all run
			return;
		}
		ObjectNode fixture = ConformanceCodec.json().createObjectNode();
		fixture.put("specVersion", SPEC_VERSION);
		fixture.put("source", type.getName());
		fixture.putObject("session").put("productVersion", ConformanceTm1.PRODUCT_VERSION);
		ArrayNode list = fixture.putArray("cases");
		cases.stream().sorted(Comparator.comparing(c -> order(c.get("id").asString()))).forEach(list::add);
		String text = write(fixture);
		Path file = directory().resolve(type.getSimpleName() + ".json");
		if (RECORD) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text, StandardCharsets.UTF_8);
			return;
		}
		String rerecord = " (run ./gradlew :tm1-client:test -PrecordConformance, then check the other clients replay it)";
		if (!Files.exists(file)) {
			throw new AssertionFailedError("No conformance fixture " + file + rerecord);
		}
		String existing = Files.readString(file, StandardCharsets.UTF_8);
		if (!existing.equals(text)) {
			throw new AssertionFailedError("The conformance fixture " + file + " is out of date" + rerecord, existing,
					text);
		}
	}

	/**
	 * The class's test methods that run: annotated with a JUnit test annotation, and not
	 * disabled.
	 */
	private static Set<String> tests(Class<?> type) {
		Set<String> names = new TreeSet<>();
		for (Method m : type.getDeclaredMethods()) {
			boolean test = Arrays.stream(m.getAnnotations())
				.anyMatch(a -> a.annotationType().isAnnotationPresent(Testable.class)
						|| a.annotationType().getName().equals("org.junit.jupiter.api.Test"));
			if (test && !m.isAnnotationPresent(Disabled.class)) {
				names.add(m.getName());
			}
		}
		return names;
	}

	/** Sorts {@code Class#test#10} after {@code Class#test#9}. */
	private static String order(String id) {
		int hash = id.lastIndexOf('#');
		return id.substring(0, hash) + "#" + "%05d".formatted(Integer.parseInt(id.substring(hash + 1)));
	}

	/** {@code spec/conformance/calls}, from the {@code tm1.spec} system property. */
	public static Path directory() {
		String spec = System.getProperty("tm1.spec");
		if (spec == null) {
			throw new IllegalStateException("tm1.spec isn't set: run the tests through Gradle");
		}
		return Path.of(spec).getParent().resolve("conformance").resolve("calls");
	}

	static String write(JsonNode node) {
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("\t", "\n"))
			.withArrayIndenter(new DefaultIndenter("\t", "\n"));
		return ConformanceCodec.json().writer().with(printer).writeValueAsString(node) + "\n";
	}

}
