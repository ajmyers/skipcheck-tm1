package net.ajmyers.tm1.client.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OData encoding every client must agree on
 * ({@code spec/conformance/odata-encoding.json}), which the Python client's tests read
 * too: the same calls make the same paths and literals in each language.
 */
class ODataConformanceTest {

	private static final JsonNode CASES = JsonMapper.builder()
		.build()
		.readTree(Path.of(System.getProperty("tm1.spec")).resolveSibling("conformance/odata-encoding.json").toFile());

	@TestFactory
	List<DynamicTest> paths() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode c : CASES.path("paths")) {
			String expect = c.path("expect").asString();
			tests.add(DynamicTest.dynamicTest(expect, () -> assertThat(build(c.path("steps"))).hasToString(expect)));
		}
		return tests;
	}

	@TestFactory
	List<DynamicTest> literals() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode c : CASES.path("literals")) {
			String expect = c.path("expect").asString();
			tests.add(DynamicTest.dynamicTest(expect,
					() -> assertThat(ODataLiterals.of(value(c.path("value"), c.path("as").asString(null))))
						.isEqualTo(expect)));
		}
		return tests;
	}

	private static ODataPath build(JsonNode steps) {
		ODataPath path = null;
		for (JsonNode step : steps) {
			path = step(path, step);
		}
		return Objects.requireNonNull(path, "no steps");
	}

	private static ODataPath step(@Nullable ODataPath path, JsonNode step) {
		String op = step.get(0).asString();
		if (op.equals("of")) {
			return ODataPath.of(step.get(1).asString());
		}
		if (op.equals("functionImport")) {
			return ODataPath.functionImport(step.get(1).asString(), map(step.get(2)));
		}
		ODataPath p = Objects.requireNonNull(path, "a path starts with of or functionImport");
		return switch (op) {
			case "key" -> p.key(Objects.requireNonNull(value(step.get(1), null)));
			case "compoundKey" -> p.key(map(step.get(1)));
			case "nav" -> p.nav(step.get(1).asString());
			case "cast" -> p.cast(step.get(1).asString());
			case "action" -> p.action(step.get(1).asString());
			case "function" -> p.function(step.get(1).asString(), map(step.get(2)));
			case "value" -> p.value();
			case "count" -> p.count();
			case "ref" -> p.ref();
			default -> throw new IllegalArgumentException("Unknown step " + op);
		};
	}

	private static Map<String, Object> map(JsonNode node) {
		Map<String, Object> out = new LinkedHashMap<>();
		node.properties().forEach(e -> out.put(e.getKey(), value(e.getValue(), null)));
		return out;
	}

	private static @Nullable Object value(JsonNode node, @Nullable String as) {
		if (node.isNull()) {
			return null;
		}
		if ("double".equals(as)) {
			return node.asDouble();
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		return node.asString();
	}

}
