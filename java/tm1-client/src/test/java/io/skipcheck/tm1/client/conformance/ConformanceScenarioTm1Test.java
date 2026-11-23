// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import tools.jackson.databind.JsonNode;

/**
 * The live conformance scenarios ({@code spec/conformance/live}) against a real TM1: each
 * step must meet its expectation, and what the client sent is written to
 * {@code build/conformance-live/java} for {@code scripts/compare-live-traffic.py} to
 * compare with the other clients'.
 */
@RequiresTm1
class ConformanceScenarioTm1Test {

	private static Tm1ServerContainer server;

	@BeforeAll
	static void server(Tm1ServerContainer tm1) {
		server = tm1;
	}

	@AfterAll
	static void forget() {
		server = null;
	}

	static Stream<Path> scenarios() {
		return ConformanceScenarios.all().stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("scenarios")
	void runsTheScenario(Path file) {
		JsonNode scenario = ConformanceScenarios.load(file);
		Map<String, String> names = ConformanceScenarios.names(scenario);
		ConformanceScenarios.Traffic traffic = new ConformanceScenarios.Traffic(server.getRestUrl());
		Tm1Client tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.webClient(WebClient.builder().filter(traffic))
			.build();
		try {
			List<String> failures = ConformanceScenarios.run(tm1, traffic, scenario, names);
			traffic.write(scenario.get("name").asString(), names);
			assertThat(failures).isEmpty();
		}
		finally {
			tm1.close().block();
		}
	}

}
