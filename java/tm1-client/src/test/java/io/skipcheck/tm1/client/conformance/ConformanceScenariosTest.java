// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.skipcheck.tm1.client.ServerAssumptions.Install;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.metadata.Endpoints;
import io.skipcheck.tm1.client.metadata.MetadataSnapshot;
import io.skipcheck.tm1.client.version.Tm1Version;
import reactor.core.publisher.Flux;
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
			return json(200, "{\"value\":[{\"Name\":\"Sandboxes\"},{\"Name\":\"" + names.get("region")
					+ "\"},{\"Name\":\"" + names.get("month") + "\"}]}");
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
	 * step's with {@code 204}), so the scenario runs offline; with what a step saves too,
	 * as {@code saved_<name>}, when it doesn't expect it.
	 */
	private static ExchangeFunction expected(JsonNode scenario, Map<String, String> names) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		Deque<ClientResponse> answers = new ArrayDeque<>();
		for (JsonNode step : scenario.get("steps")) {
			if (sends(step)) {
				answers.add(answer(saving(step, ConformanceScenarios.substitute(step.get("expect"), names))));
			}
		}
		scenario.path("finally").forEach(step -> answers.add(ClientResponse.create(HttpStatus.NO_CONTENT).build()));
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

	/** The expectation with the fields the step saves, if it lacks them. */
	private static JsonNode saving(JsonNode step, JsonNode expect) {
		if (!step.has("save")) {
			return expect;
		}
		ObjectNode out = (ObjectNode) expect.deepCopy();
		ObjectNode fields = out.has("fields") ? (ObjectNode) out.get("fields") : out.putObject("fields");
		for (var s : step.get("save").properties()) {
			if (!fields.has(s.getValue().asString())) {
				fields.put(s.getValue().asString(), "saved_" + s.getKey());
			}
		}
		return out;
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
			String code = error.path("kind").asString().equals("INVALID_UNIQUE_NAME") ? "248" : "278";
			return json(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
		}
		if (expect.has("fields") && expect.get("fields").has("$bytes")) {
			return ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
				.body(Flux.just(DefaultDataBufferFactory.sharedInstance
					.wrap(Base64.getDecoder().decode(expect.get("fields").get("$bytes").asString()))))
				.build();
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
	@ValueSource(strings = { "subsets", "members", "edges", "member-sets", "content", "encryption", "git", "users",
			"sessions", "attributes", "annotations", "processes", "chores", "round-trips" })
	void runsAScenarioSendingOneRequestPerStep(String name) {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve(name + ".json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		List<String> failures = ConformanceScenarios.run(client(traffic, expected(scenario, names)), traffic, scenario,
				names);
		assertThat(failures).isEmpty();
		long sending = 0;
		for (JsonNode step : scenario.get("steps")) {
			sending += sends(step) ? 1 : 0;
		}
		assertThat(traffic.exchanges()).hasSize((int) sending + scenario.path("finally").size());
	}

	/**
	 * Whether a step sends a request: all but those refused before one (an argument
	 * error).
	 */
	private static boolean sends(JsonNode step) {
		JsonNode error = step.path("expect").path("error");
		return error.isMissingNode() || error.path("category").asString().equals("api");
	}

	/**
	 * Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of
	 * 3 × 2 cells (and the slicer), each worth its ordinal until an update writes it,
	 * names that exist but {@code missing}.
	 */
	private static ExchangeFunction data(Map<String, String> names) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		// The cells' values, by ordinal, as the scenario's updates write them
		Map<Long, JsonNode> values = new HashMap<>();
		return request -> {
			String target = ConformanceTm1.target(request.url());
			String key = request.method().name() + " " + target;
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata") || key.startsWith("GET Server?")
					|| key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			return ConformanceTm1.body(request).map(body -> data(request.method().name(), target, body, names, values));
		};
	}

	private static String cells(long begin, long end, Map<Long, JsonNode> values) {
		StringBuilder out = new StringBuilder("[");
		for (long n = begin; n <= end; n++) {
			out.append(n == begin ? "" : ",")
				.append("{\"Ordinal\":")
				.append(n)
				.append(",\"Value\":")
				.append(values.containsKey(n) ? values.get(n).toString() : String.valueOf(n))
				.append('}');
		}
		return out.append(']').toString();
	}

	/** The match of {@code regex} on the whole target, or null. */
	private static @Nullable Matcher match(String regex, String target) {
		Matcher m = Pattern.compile(regex).matcher(target);
		return m.matches() ? m : null;
	}

	/**
	 * A cell's ordinal in the scenario's cellset of 3 × 2 cells, by its update's ordinal
	 * or its tuple's elements (a region, then a month).
	 */
	private static long ordinal(JsonNode update) {
		if (update.has("Ordinal")) {
			return update.get("Ordinal").asLong();
		}
		List<String> elements = new ArrayList<>();
		for (JsonNode path : update.get("Tuple@odata.bind")) {
			Matcher element = Pattern.compile("Elements\\('([^']*)'\\)$").matcher(path.asString());
			elements.add(element.find() ? element.group(1) : "");
		}
		return List.of("North", "South").indexOf(elements.get(0)) * 3L
				+ List.of("Jan", "Feb", "Mar").indexOf(elements.get(1));
	}

	private static ClientResponse data(String method, String target, byte[] body, Map<String, String> names,
			Map<Long, JsonNode> values) {
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
							+ cells(0, top == null ? -1 : Long.parseLong(top.group(1)) - 1, values) + "}");
		}
		if (post && target.matches("(Cellsets\\('cs1'\\)|Cubes\\('[^']*'\\))/tm1\\.UpdateCells")) {
			for (JsonNode update : json.get("Updates")) {
				values.put(ordinal(update), update.get("Value"));
			}
			return ClientResponse.create(HttpStatus.NO_CONTENT).build();
		}
		if (post && target.endsWith("/tm1.Execute?$select=ID")) {
			return json(200, "{\"ID\":\"cs2\"}");
		}
		Matcher partition = match("Cellsets\\('cs1'\\)/tm1\\.GetPartition\\(Begin=(\\d+),End=(\\d+)\\).*", target);
		if (get && partition != null) {
			return json(200, "{\"value\":"
					+ cells(Long.parseLong(partition.group(1)), Long.parseLong(partition.group(2)), values) + "}");
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
	void treatsANullAsMissingAtAnyDepth() {
		JsonNode expected = ConformanceCodec.json().readTree("{\"A\":{\"B\":1,\"C\":[1.0,{\"D\":null}]}}");
		assertThat(ConformanceScenarios.same(expected,
				ConformanceCodec.json().readTree("{\"A\":{\"B\":1.0,\"C\":[1,{}],\"E\":null}}")))
			.isTrue();
		assertThat(ConformanceScenarios.same(expected,
				ConformanceCodec.json().readTree("{\"A\":{\"B\":1,\"C\":[1,{\"D\":2}]}}")))
			.isFalse();
		assertThat(ConformanceScenarios.same(expected, ConformanceCodec.json().readTree("{\"A\":{\"C\":[1,{}]}}")))
			.isFalse();
		assertThat(ConformanceScenarios.same(expected, ConformanceCodec.json().readTree("{\"A\":{\"B\":1,\"C\":[1]}}")))
			.isFalse();
		assertThat(ConformanceScenarios.same(ConformanceCodec.json().readTree("true"),
				ConformanceCodec.json().readTree("1")))
			.isFalse();
	}

	@Test
	void buildsAWriteFromWhatAStepKept() {
		// The patch carries what the read held (Rules) with the change; a finally step
		// setting
		// back a read no step kept sends nothing
		JsonNode scenario = ConformanceCodec.json()
			.readTree(
					"""
							{"names":["cube"],"steps":[{"service":"cubes","method":"get","params":["name","query"],"args":["${cube}",{"$query":{}}],"keep":"cube","expect":{"fields":{"Name":"${cube}","Rules":"SKIPCHECK;"}}},{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"cube","json":{"DrillthroughRules":"x"}}],"expect":{"none":true}}],"finally":[{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"never","json":{}}]}]}""");
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		assertThat(ConformanceScenarios.run(client(traffic, expected(scenario, names)), traffic, scenario, names))
			.isEmpty();
		assertThat(traffic.exchanges()).hasSize(2);
		assertThat(traffic.exchanges().get(1).get("request").get("json"))
			.isEqualTo(ConformanceCodec.json().readTree("{\"Rules\":\"SKIPCHECK;\",\"DrillthroughRules\":\"x\"}"));
	}

	@Test
	void savesAFieldOfAResultForTheStepsAfterIt() {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve("annotations.json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		assertThat(ConformanceScenarios.run(client(traffic, expected(scenario, names)), traffic, scenario, names))
			.isEmpty();
		assertThat(names).containsEntry("note", "saved_note").containsEntry("built", "saved_built");
		assertThat(traffic.exchanges())
			.extracting(
					e -> e.get("request").get("method").asString() + " " + e.get("request").get("target").asString())
			.contains("PATCH Annotations('saved_built')", "DELETE Annotations('saved_note')");

		ObjectNode step = ConformanceCodec.json().createObjectNode();
		step.putObject("save").put("note", "ID");
		ObjectNode outcome = ConformanceCodec.json().createObjectNode();
		outcome.putObject("result").put("Text", "no ID");
		assertThat(ConformanceScenarios.save(step, outcome, names)).startsWith("no ID to save as note");
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

	@Test
	void appliesWhatTheServerHasAndIs() {
		JsonNode scenario = ConformanceCodec.json()
			.readTree(
					"{\"requires\":[\"Action Cube/Reserve\"],\"tm1From\":\"11.8.03000\",\"tm1Before\":\"11.8.04000\"}");
		Set<String> reserve = Set.of("Action Cube/Reserve");
		Install writable = Install.WRITABLE;
		assertThat(ConformanceScenarios.applies(scenario, reserve, Tm1Version.parse("11.8.03500.6"), writable))
			.isTrue();
		assertThat(ConformanceScenarios.applies(scenario, Set.of(), Tm1Version.parse("11.8.03500.6"), writable))
			.as("an endpoint the server lacks")
			.isFalse();
		assertThat(ConformanceScenarios.applies(scenario, reserve, Tm1Version.parse("11.8.02400.7"), writable))
			.as("too old")
			.isFalse();
		assertThat(ConformanceScenarios.applies(scenario, reserve, Tm1Version.parse("11.8.04000.1"), writable))
			.as("tm1Before is exclusive")
			.isFalse();
		assertThat(ConformanceScenarios.applies(ConformanceCodec.json().readTree("{}"), Set.of(),
				Tm1Version.parse("11.8.02400.7"), Install.UNKNOWN))
			.as("one with none of them applies everywhere")
			.isTrue();
	}

	@Test
	void appliesWhatDependsOnTheInstallToThatInstallOnly() {
		JsonNode readOnly = ConformanceCodec.json().readTree("{\"install\":\"readOnly\"}");
		JsonNode writable = ConformanceCodec.json().readTree("{\"install\":\"writable\"}");
		Tm1Version version = Tm1Version.parse("11.8.03500.6");
		assertThat(ConformanceScenarios.applies(readOnly, Set.of(), version, Install.READ_ONLY)).isTrue();
		assertThat(ConformanceScenarios.applies(readOnly, Set.of(), version, Install.WRITABLE)).isFalse();
		assertThat(ConformanceScenarios.applies(writable, Set.of(), version, Install.WRITABLE)).isTrue();
		assertThat(ConformanceScenarios.applies(writable, Set.of(), version, Install.READ_ONLY)).isFalse();
		assertThat(ConformanceScenarios.applies(readOnly, Set.of(), version, Install.UNKNOWN))
			.as("a server the suite didn't start")
			.isFalse();
		assertThat(ConformanceScenarios.applies(writable, Set.of(), version, Install.UNKNOWN))
			.as("a server the suite didn't start")
			.isFalse();
	}

	@Test
	void skipsTheStepsThatDontApplyAndSendsNothingForThem() {
		// cubes.json without its dimension steps, two creates and two finally deletes:
		// they
		// send nothing, the rest run
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve("cubes.json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		ConformanceScenarios.run(client(traffic, tm1(names, false)), traffic, scenario, names,
				step -> !"dimensions".equals(step.path("service").asString()));
		List<String> steps = traffic.exchanges().stream().map(e -> e.get("step").asString()).toList();
		assertThat(steps).doesNotContain("steps[0]", "steps[1]", "finally[1]", "finally[2]")
			.contains("steps[2]", "finally[0]");
	}

	@Test
	void saysWhatEachScenarioNeedsInTermsEveryClientReadsAlike() {
		// An endpoint ID no server defines would skip its step everywhere unnoticed, and
		// a
		// string where a list belongs is read differently by each client
		Set<String> known = Endpoints.of(MetadataSnapshot.load());
		for (Path file : ConformanceScenarios.all()) {
			JsonNode scenario = ConformanceScenarios.load(file);
			List<JsonNode> nodes = new ArrayList<>();
			nodes.add(scenario);
			scenario.path("steps").forEach(nodes::add);
			scenario.path("finally").forEach(nodes::add);
			for (JsonNode node : nodes) {
				String where = file.getFileName() + " " + node.path("method").asString("");
				if (node.has("requires")) {
					assertThat(node.get("requires").isArray()).as(where + ": requires is a list").isTrue();
					node.get("requires")
						.forEach(id -> assertThat(known).as(where + ": an endpoint the newest snapshot defines")
							.contains(id.asString()));
				}
				for (String bound : List.of("tm1From", "tm1Before")) {
					if (node.has(bound)) {
						assertThat(node.get(bound).isString()).as(where + ": " + bound + " is a version").isTrue();
						Tm1Version.parse(node.get(bound).asString());
					}
				}
				if (node.has("install")) {
					assertThat(node.get("install").asString()).as(where + ": install is writable or readOnly")
						.isIn("writable", "readOnly");
				}
			}
			// A step that may not run keeps nothing for the steps after it
			scenario.path("steps").forEach(step -> {
				if (step.has("requires") || step.has("tm1From") || step.has("tm1Before") || step.has("install")) {
					assertThat(step.has("save") || step.has("keep"))
						.as(file.getFileName() + " " + step.path("method").asString() + ": saves or keeps")
						.isFalse();
				}
			});
		}
	}

}
