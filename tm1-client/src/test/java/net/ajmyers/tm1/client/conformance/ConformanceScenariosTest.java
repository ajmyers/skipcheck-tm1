package net.ajmyers.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import net.ajmyers.tm1.client.Tm1Client;
import tools.jackson.databind.JsonNode;

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
