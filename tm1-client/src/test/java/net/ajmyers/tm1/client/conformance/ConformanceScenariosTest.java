package net.ajmyers.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import net.ajmyers.tm1.client.Tm1Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The live conformance runner, offline: the cubes scenario against a fake TM1 that
 * answers as TM1 would, writing traffic without the session's own requests.
 */
class ConformanceScenariosTest {

	private static final Pattern GET = Pattern.compile("Cubes\\('([^']*)'\\)(\\?.*)?");

	/** Answers as TM1 would, for the cubes scenario. */
	private static ExchangeFunction tm1(Map<String, String> names, boolean down) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		return request -> {
			String target = ConformanceTm1.target(request.url());
			String key = request.method().name() + " " + target;
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata") || key.startsWith("GET Server?")
					|| key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			return ConformanceTm1.body(request).map(body -> answer(request, target, body, names, down));
		};
	}

	private static ClientResponse answer(ClientRequest request, String target, byte[] body, Map<String, String> names,
			boolean down) {
		if (down) {
			return json(500, "{\"error\":{\"code\":\"1\",\"message\":\"down\"}}");
		}
		String method = request.method().name();
		Matcher get = GET.matcher(target);
		if (method.equals("POST") && (target.equals("Dimensions") || target.equals("Cubes"))) {
			JsonNode json = ConformanceCodec.json().readTree(new String(body, StandardCharsets.UTF_8));
			return json(201, "{\"Name\":\"" + json.get("Name").asString() + "\"}");
		}
		if (method.equals("GET") && get.matches()) {
			return get.group(1).equals(names.get("missing"))
					? json(404, "{\"error\":{\"code\":\"278\",\"message\":\"no\"}}")
					: json(200, "{\"Name\":\"" + get.group(1) + "\"}");
		}
		if (method.equals("GET") && target.endsWith("/Dimensions?$select=Name")) {
			return json(200, "{\"value\":[{\"Name\":\"" + names.get("region") + "\"},{\"Name\":\"" + names.get("month")
					+ "\"}]}");
		}
		if (target.endsWith("tm1.CheckRules")) {
			return json(200, "{\"value\":[]}");
		}
		return ClientResponse.create(HttpStatus.NO_CONTENT).build();
	}

	private static ClientResponse json(int status, String body) {
		return ClientResponse.create(HttpStatus.valueOf(status))
			.header(HttpHeaders.CONTENT_TYPE, "application/json")
			.body(body)
			.build();
	}

	private static Tm1Client client(ConformanceScenarios.Traffic traffic, ExchangeFunction tm1) {
		return Tm1Client.builder()
			.baseUrl(ConformanceTm1.ROOT)
			.basicAuth("admin", "apple")
			.webClient(WebClient.builder().filter(traffic).exchangeFunction(tm1))
			.build();
	}

	@Test
	void runsTheCubesScenarioAndRecordsItsTraffic(@TempDir Path dir) throws Exception {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve("cubes.json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		List<String> failures = ConformanceScenarios.run(client(traffic, tm1(names, false)), traffic, scenario, names);
		assertThat(failures).isEmpty();
		assertThat(traffic.exchanges()).hasSize(scenario.get("steps").size() + scenario.get("finally").size());
		assertThat(traffic.exchanges().get(0).get("request").get("target").asString()).isEqualTo("Dimensions");
		assertThat(traffic.exchanges().get(2).get("request").get("json").get("Dimensions@odata.bind").get(0).asString())
			.isEqualTo("Dimensions('" + names.get("region") + "')");
		assertThat(Files.exists(dir)).isTrue();
	}

	/**
	 * Answers each step's one request with what the step expects (a {@code finally}
	 * step's with {@code 204}), so the scenario runs offline.
	 */
	private static ExchangeFunction expected(JsonNode scenario, Map<String, String> names) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		Deque<ClientResponse> answers = new ArrayDeque<>();
		for (JsonNode step : scenario.get("steps")) {
			answers.add(answer(ConformanceScenarios.substitute(step.get("expect"), names)));
		}
		scenario.get("finally").forEach(step -> answers.add(ClientResponse.create(HttpStatus.NO_CONTENT).build()));
		return request -> {
			String key = request.method().name() + " " + ConformanceTm1.target(request.url());
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata") || key.startsWith("GET Server?")
					|| key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			return ConformanceTm1.body(request)
				.map(body -> answers.isEmpty() ? json(599, "{\"error\":{\"message\":\"unscripted\"}}")
						: answers.poll());
		};
	}

	private static ClientResponse answer(JsonNode expect) {
		if (expect.has("error")) {
			JsonNode error = expect.get("error");
			int status = error.has("status") ? error.get("status").asInt() : switch (error.path("kind").asString()) {
				case "NOT_FOUND" -> 404;
				case "CONFLICT" -> 409;
				case "PRECONDITION_FAILED" -> 412;
				default -> 400;
			};
			String message = error.path("kind").asString().equals("NOT_FOUND") ? "can not be found" : "refused";
			return json(status, "{\"error\":{\"code\":\"278\",\"message\":\"" + message + "\"}}");
		}
		if (expect.has("fields")) {
			return json(200, expect.get("fields").toString());
		}
		ArrayNode value = ConformanceCodec.json().createArrayNode();
		if (expect.has("count")) {
			for (int i = 0; i < expect.get("count").asInt(); i++) {
				value.addObject();
			}
		}
		if (expect.has("items")) {
			for (var field : expect.get("items").properties()) {
				for (int i = 0; i < field.getValue().size(); i++) {
					(value.size() > i ? (ObjectNode) value.get(i) : value.addObject()).set(field.getKey(),
							field.getValue().get(i));
				}
			}
		}
		if (expect.has("count") || expect.has("items")) {
			return json(200, "{\"value\":" + value + "}");
		}
		return ClientResponse.create(HttpStatus.NO_CONTENT).build();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "subsets", "members", "edges", "member-sets" })
	void runsAScenarioSendingOneRequestPerStep(String name) {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve(name + ".json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		List<String> failures = ConformanceScenarios.run(client(traffic, expected(scenario, names)), traffic, scenario,
				names);
		assertThat(failures).isEmpty();
		assertThat(traffic.exchanges()).hasSize(scenario.get("steps").size() + scenario.get("finally").size());
	}

	/**
	 * Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of
	 * 3 × 2 cells (and the slicer), names that exist but {@code missing}.
	 */
	private static ExchangeFunction data(Map<String, String> names) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		return request -> {
			String target = ConformanceTm1.target(request.url());
			String key = request.method().name() + " " + target;
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata") || key.startsWith("GET Server?")
					|| key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			return ConformanceTm1.body(request).map(body -> data(request.method().name(), target, body, names));
		};
	}

	private static String cells(long begin, long end) {
		StringBuilder out = new StringBuilder("[");
		for (long n = begin; n <= end; n++) {
			out.append(n == begin ? "" : ",")
				.append("{\"Ordinal\":")
				.append(n)
				.append(",\"Value\":")
				.append(n)
				.append('}');
		}
		return out.append(']').toString();
	}

	/** The match of {@code regex} on the whole target, or null. */
	private static @Nullable Matcher match(String regex, String target) {
		Matcher m = Pattern.compile(regex).matcher(target);
		return m.matches() ? m : null;
	}

	private static ClientResponse data(String method, String target, byte[] body, Map<String, String> names) {
		JsonNode json = body.length == 0 ? null : ConformanceCodec.json().readTree(body);
		boolean get = method.equals("GET");
		boolean post = method.equals("POST");
		if (target.contains("('" + names.get("missing") + "')")
				|| (post && target.startsWith("ExecuteRelationalDrillthrough"))) {
			return json(404, "{\"error\":{\"code\":\"278\",\"message\":\"no\"}}");
		}
		if (post && target.matches("Dimensions|Cubes|Sandboxes|Cubes\\('[^']*'\\)/(Private)?Views")) {
			return json(201, "{\"Name\":\"" + json.get("Name").asString() + "\"}");
		}
		Matcher execute = match("ExecuteMDX(\\?.*)?", target);
		if (post && execute != null) {
			if (json.get("MDX").asString().contains("SELEKT")) {
				return json(400, "{\"error\":{\"code\":\"501\",\"message\":\"syntax\"}}");
			}
			Matcher top = match(".*\\$top%3D(\\d+).*", target);
			return json(200,
					"{\"ID\":\"cs1\",\"Axes\":[{\"Ordinal\":0,\"Cardinality\":3},{\"Ordinal\":1,\"Cardinality\":2},"
							+ "{\"Ordinal\":2,\"Cardinality\":1}],\"Cells\":"
							+ cells(0, top == null ? -1 : Long.parseLong(top.group(1)) - 1) + "}");
		}
		if (post && target.endsWith("/tm1.Execute?$select=ID")) {
			return json(200, "{\"ID\":\"cs2\"}");
		}
		Matcher partition = match("Cellsets\\('cs1'\\)/tm1\\.GetPartition\\(Begin=(\\d+),End=(\\d+)\\).*", target);
		if (get && partition != null) {
			return json(200, "{\"value\":"
					+ cells(Long.parseLong(partition.group(1)), Long.parseLong(partition.group(2))) + "}");
		}
		Matcher cube = match("Cubes\\('([^']*)'\\)/[A-Za-z]+\\('[^']*'\\)/Cube.*", target);
		if (get && cube != null) {
			return json(200, "{\"Name\":\"" + cube.group(1) + "\"}");
		}
		Matcher view = match("Cubes\\('[^']*'\\)/(Private)?Views\\('([^']*)'\\).*", target);
		if (get && view != null) {
			String nativeView = target.contains("NativeView")
					? "\"@odata.type\":\"#ibm.tm1.api.v1.NativeView\",\"SuppressEmptyRows\":true," : "";
			return json(200, "{" + nativeView + "\"Name\":\"" + view.group(2) + "\"}");
		}
		if (get && target.matches("Cubes\\('[^']*'\\)/PrivateViews\\?.*")) {
			return json(200, "{\"value\":[{\"Name\":\"" + names.get("native") + "\"}]}");
		}
		Matcher sandbox = match("Sandboxes\\('([^']*)'\\).*", target);
		if (get && sandbox != null) {
			return json(200, "{\"Name\":\"" + sandbox.group(1) + "\",\"IsLoaded\":true}");
		}
		return ClientResponse.create(HttpStatus.NO_CONTENT).build();
	}

	@ParameterizedTest
	@ValueSource(strings = { "cellsets", "views", "sandboxes" })
	void runsTheDataScenarios(String name) {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve(name + ".json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		List<String> failures = ConformanceScenarios.run(client(traffic, data(names)), traffic, scenario, names);
		assertThat(failures).isEmpty();
		assertThat(traffic.exchanges().stream().map(e -> e.get("step").asString()).distinct())
			.hasSize(scenario.get("steps").size() + scenario.get("finally").size());
	}

	@Test
	void stopsAtAStepThatMissesItsExpectationAndStillCleansUp() {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve("cubes.json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		List<String> failures = ConformanceScenarios.run(client(traffic, tm1(names, true)), traffic, scenario, names);
		assertThat(failures).singleElement().asString().startsWith("step 0 dimensions.create");
		List<String> steps = traffic.exchanges().stream().map(e -> e.get("step").asString()).toList();
		assertThat(steps.subList(steps.size() - 3, steps.size())).containsExactly("finally[0]", "finally[1]",
				"finally[2]");
	}

}
