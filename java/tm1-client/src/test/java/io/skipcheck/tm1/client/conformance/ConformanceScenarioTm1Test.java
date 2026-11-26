// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.metadata.Endpoints;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Version;
import tools.jackson.databind.JsonNode;

/**
 * The live conformance scenarios ({@code spec/conformance/live}) against a real TM1: each
 * step must meet its expectation, and what the client sent is written to
 * {@code build/conformance-live/java} for {@code scripts/compare-live-traffic.py} to
 * compare with the other clients'.
 */
@LiveTm1
class ConformanceScenarioTm1Test {

	private static LiveServer server;

	@BeforeAll
	static void server(LiveServer tm1) {
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
		Tm1Client tm1 = server.clientBuilder().webClient(server.webClient().filter(traffic)).build();
		try {
			// What the server has and is: a scenario or a step for one it isn't is
			// skipped
			Tm1Capabilities capabilities = tm1.session().capabilities().block();
			Set<String> endpoints = Endpoints.of(capabilities.metadata());
			Tm1Version version = capabilities.productVersion().orElseThrow();
			ServerAssumptions.Install install = server.install();
			assumeTrue(ConformanceScenarios.applies(scenario, endpoints, version, install),
					() -> "the scenario needs " + scenario.path("requires") + " " + scenario.path("tm1From") + " "
							+ scenario.path("tm1Before") + " " + scenario.path("install") + "; the server is " + version
							+ ", schema " + capabilities.schemaVersion().orElse("unknown") + ", install " + install);
			List<String> failures = ConformanceScenarios.run(tm1, traffic, scenario, names,
					step -> ConformanceScenarios.applies(step, endpoints, version, install));
			traffic.write(scenario.get("name").asString(), names);
			assertThat(failures).isEmpty();
		}
		finally {
			tm1.close().block();
		}
	}

}
