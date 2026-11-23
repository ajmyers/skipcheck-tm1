// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.skipcheck.tm1.client.Tm1Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The dimension scenarios ({@code dimensions}, {@code hierarchies}, {@code elements},
 * {@code levels}), offline: against a fake TM1 that answers each step with what the step
 * expects, so every step's call is made, sends one request, and reads its answer.
 */
class ConformanceDimensionScenariosTest {

	/** Answers the running step as it expects; finally steps with 204. */
	private static ExchangeFunction tm1(JsonNode scenario, Map<String, String> names,
			ConformanceScenarios.Traffic traffic) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		return request -> {
			String target = ConformanceTm1.target(request.url());
			String key = request.method().name() + " " + target;
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata") || key.startsWith("GET Server?")
					|| key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			String step = traffic.step();
			JsonNode expect = step.startsWith("steps[") ? ConformanceScenarios.substitute(
					scenario.get("steps").get(Integer.parseInt(step.substring(6, step.length() - 1))).get("expect"),
					names) : null;
			return ConformanceTm1.body(request).map(body -> answer(expect));
		};
	}

	/** What TM1 would answer for the call to meet the expectation. */
	static ClientResponse answer(JsonNode expect) {
		if (expect == null || expect.has("none")) {
			return ClientResponse.create(HttpStatus.NO_CONTENT).build();
		}
		if (expect.has("error")) {
			JsonNode error = expect.get("error");
			String kind = error.path("kind").asString("");
			int status = error.has("status") ? error.get("status").asInt() : switch (kind) {
				case "NOT_FOUND" -> 404;
				case "CONFLICT", "ALREADY_EXISTS" -> 409;
				case "PRECONDITION_FAILED" -> 412;
				default -> 400;
			};
			String message = switch (kind) {
				case "NOT_FOUND" -> "'x' can not be found in collection";
				case "ALREADY_EXISTS" -> "'x' already exists";
				default -> "refused";
			};
			ObjectNode body = ConformanceCodec.json().createObjectNode();
			body.putObject("error").put("code", "278").put("message", message);
			return json(status, body);
		}
		if (expect.has("count")) {
			ObjectNode body = ConformanceCodec.json().createObjectNode();
			ArrayNode value = body.putArray("value");
			for (int i = 0; i < expect.get("count").asInt(); i++) {
				value.addObject();
			}
			return json(200, body);
		}
		if (expect.has("items")) {
			List<ObjectNode> items = new ArrayList<>();
			for (var field : expect.get("items").properties()) {
				for (int i = 0; i < field.getValue().size(); i++) {
					if (items.size() <= i) {
						items.add(ConformanceCodec.json().createObjectNode());
					}
					items.get(i).set(field.getKey(), field.getValue().get(i));
				}
			}
			ObjectNode body = ConformanceCodec.json().createObjectNode();
			ArrayNode value = body.putArray("value");
			items.forEach(value::add);
			return json(200, body);
		}
		return json(200, expect.get("fields"));
	}

	private static ClientResponse json(int status, JsonNode body) {
		return ClientResponse.create(HttpStatus.valueOf(status))
			.header(HttpHeaders.CONTENT_TYPE, "application/json")
			.body(body.toString())
			.build();
	}

	@ParameterizedTest
	@ValueSource(strings = { "dimensions", "hierarchies", "elements", "levels" })
	void runsEachStepOnce(String name) {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve(name + ".json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		Tm1Client client = Tm1Client.builder()
			.baseUrl(ConformanceTm1.ROOT)
			.basicAuth("admin", "apple")
			.webClient(WebClient.builder().filter(traffic).exchangeFunction(tm1(scenario, names, traffic)))
			.build();
		assertThat(ConformanceScenarios.run(client, traffic, scenario, names)).isEmpty();
		List<String> steps = new ArrayList<>();
		for (int i = 0; i < scenario.get("steps").size(); i++) {
			steps.add("steps[" + i + "]");
		}
		for (int i = 0; i < scenario.get("finally").size(); i++) {
			steps.add("finally[" + i + "]");
		}
		assertThat(traffic.exchanges()).extracting(e -> e.get("step").asString()).containsExactlyElementsOf(steps);
	}

}
