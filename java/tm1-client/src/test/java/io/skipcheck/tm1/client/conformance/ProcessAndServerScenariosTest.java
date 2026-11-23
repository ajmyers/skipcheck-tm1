// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.skipcheck.tm1.client.Tm1Client;
import tools.jackson.databind.JsonNode;

/**
 * The live conformance runner, offline: the processes, chores and server scenarios
 * against a fake TM1 that answers as TM1 would, writing traffic without the session's own
 * requests. Every client's runner has the same test, with the same fake.
 */
class ProcessAndServerScenariosTest {

	private static final Pattern ENTITY = Pattern
		.compile("(Processes|Chores|Loggers|ErrorLogFiles|SQLDataSources)\\('([^']*)'\\)(\\?.*)?");

	private static final Pattern TOP = Pattern
		.compile("(MessageLogEntries|MessageLog\\(Reverse=true\\))\\?\\$top=(\\d+)");

	/** Answers as TM1 would, for the processes, chores and server scenarios. */
	private static ExchangeFunction tm1(Map<String, String> names) {
		ConformanceTm1 plumbing = new ConformanceTm1();
		AtomicInteger tasks = new AtomicInteger();
		return request -> {
			String target = ConformanceTm1.target(request.url());
			String key = request.method().name() + " " + target;
			if (key.startsWith("GET ActiveSession") || key.equals("GET $metadata")
					|| key.equals("GET Server?$select=ProductVersion") || key.startsWith("POST ActiveSession")) {
				return plumbing.exchange(request);
			}
			return ConformanceTm1.body(request).map(body -> answer(request, target, body, names, tasks));
		};
	}

	private static ClientResponse answer(ClientRequest request, String target, byte[] body, Map<String, String> names,
			AtomicInteger tasks) {
		String method = request.method().name();
		Matcher entity = ENTITY.matcher(target);
		Matcher top = TOP.matcher(target);
		if (method.equals("POST") && (target.equals("Processes") || target.equals("Chores"))) {
			JsonNode json = ConformanceCodec.json().readTree(new String(body, StandardCharsets.UTF_8));
			if (target.equals("Chores")) {
				tasks.set(json.path("Tasks").size());
			}
			return json(201, "{\"Name\":\"" + json.get("Name").asString() + "\"}");
		}
		if (method.equals("GET") && entity.matches()) {
			String name = entity.group(2);
			if (name.equals(names.get("missing")) || name.equals(names.get("missing") + ".log")) {
				return json(404, "{\"error\":{\"code\":\"278\",\"message\":\"no\"}}");
			}
			return json(200,
					entity.group(1).equals("Chores")
							? "{\"Name\":\"" + name + "\",\"Active\":false,\"ExecutionMode\":\"SingleCommit\"}"
							: "{\"Name\":\"" + name + "\"}");
		}
		if (method.equals("GET") && target.startsWith("Processes?")) {
			return json(200, "{\"value\":[{\"Name\":\"" + names.get("process") + "\"}]}");
		}
		if (method.equals("GET") && target.startsWith("Chores?")) {
			return json(200, "{\"value\":[{\"Name\":\"" + names.get("chore") + "\"}]}");
		}
		if (target.endsWith("/tm1.Compile")) {
			return json(200, "{\"value\":[]}");
		}
		if (target.equals("CompileProcess")) {
			return json(200, "{\"value\":[{\"Procedure\":\"Prolog\",\"LineNumber\":1,\"Message\":\"Syntax error\"}]}");
		}
		if (target.contains("ExecuteWithReturn") || target.startsWith("ExecuteProcessWithReturn")) {
			return json(200, "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}");
		}
		if (method.equals("GET") && target.startsWith("ProcessDebugContexts")) {
			return json(200, "{\"value\":[]}");
		}
		if (target.matches("Chores\\('[^']*'\\)/Tasks")) {
			return json(201, "{\"Step\":" + tasks.getAndIncrement() + "}");
		}
		if (method.equals("GET") && target.matches("Chores\\('[^']*'\\)/Tasks\\?.*")) {
			StringBuilder steps = new StringBuilder();
			for (int i = 0; i < tasks.get(); i++) {
				steps.append(i == 0 ? "" : ",").append("{\"Step\":").append(i).append('}');
			}
			return json(200, "{\"value\":[" + steps + "]}");
		}
		if (method.equals("DELETE") && target.matches("Chores\\('[^']*'\\)/Tasks\\(\\d+\\)")) {
			tasks.decrementAndGet();
			return ClientResponse.create(HttpStatus.NO_CONTENT).build();
		}
		if (method.equals("GET") && target.matches("Chores\\('[^']*'\\)/Tasks\\(\\d+\\)/Process(\\?.*)?")) {
			return json(200, "{\"Name\":\"" + names.get("process") + "\"}");
		}
		if (method.equals("GET") && target.matches("Chores\\('[^']*'\\)/Tasks\\(\\d+\\)/Chore(\\?.*)?")) {
			return json(200, "{\"Name\":\"" + names.get("chore") + "\"}");
		}
		if (method.equals("GET") && target.startsWith("Server?")) {
			return json(200, "{\"Name\":\"tm1\",\"ProductVersion\":\"" + ConformanceTm1.PRODUCT_VERSION + "\"}");
		}
		if (method.equals("GET") && target.matches("(StaticConfiguration|ActiveConfiguration|Configuration)(\\?.*)?")) {
			return json(200, "{\"ServerName\":\"tm1\"}");
		}
		if (method.equals("GET") && target.startsWith("Loggers?")) {
			return json(200, "{\"value\":[{\"Name\":\"TM1.Server\",\"Level\":\"Info\"}]}");
		}
		if (method.equals("GET") && top.matches()) {
			StringBuilder entries = new StringBuilder();
			for (int i = 0; i < Integer.parseInt(top.group(2)); i++) {
				entries.append(i == 0 ? "" : ",").append("{\"ID\":").append(i).append(",\"Message\":\"m\"}");
			}
			return json(200, "{\"value\":[" + entries + "]}");
		}
		if (method.equals("GET") && target.equals("SQLDataSources")) {
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

	@ParameterizedTest
	@ValueSource(strings = { "server" })
	void runsTheScenarioAndRecordsItsTraffic(String name) {
		JsonNode scenario = ConformanceScenarios.load(ConformanceScenarios.directory().resolve(name + ".json"));
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(ConformanceTm1.ROOT);
		Tm1Client client = Tm1Client.builder()
			.baseUrl(ConformanceTm1.ROOT)
			.basicAuth("admin", "apple")
			.webClient(WebClient.builder().filter(traffic).exchangeFunction(tm1(names)))
			.build();
		List<String> failures = ConformanceScenarios.run(client, traffic, scenario, names);
		assertThat(failures).isEmpty();
		assertThat(traffic.exchanges()).as("one request a step, the session's own left out")
			.hasSize(scenario.get("steps").size() + scenario.get("finally").size());
	}

}
