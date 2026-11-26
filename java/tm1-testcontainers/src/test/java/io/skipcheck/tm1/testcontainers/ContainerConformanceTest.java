// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Starts each scenario of {@code testcontainers-conformance/scenarios}, a
 * {@code pa-environment.yaml}, and writes what Docker says it got ({@code docker inspect}
 * of each container and of the network) to
 * {@code build/container-conformance/java/<scenario>.json}. Python, .NET and TypeScript
 * have the same tests, and {@code scripts/compare-containers.py} then requires all four
 * to have got the same containers ({@code ./gradlew containerConformance}).
 */
class ContainerConformanceTest {

	@Test
	@RequiresTm1
	void oneServer() throws Exception {
		record("one-server");
	}

	@Test
	@RequiresTm1Web
	void serversAndTm1Web() throws Exception {
		record("servers-and-web");
	}

	@Test
	@RequiresCognos
	void serversInCamSecurity() throws Exception {
		record("cam-security");
	}

	@Test
	@RequiresTm1Engine(shareDatabase = true)
	void engine() throws Exception {
		record("engine");
	}

	@Test
	@RequiresTm1Engine(shareDatabase = true)
	void engineWithOAuth2() throws Exception {
		record("engine-oauth2");
	}

	private static void record(String scenario) throws IOException, InterruptedException {
		Path home = Path.of(System.getProperty("tm1.containerConformance.scenarios"));
		try (var env = PlanningAnalyticsEnvironment.fromConfig(home.resolve(scenario + ".yaml")).build()) {
			env.start();

			Map<String, GenericContainer<?>> containers = new LinkedHashMap<>();
			env.tm1Servers().forEach((name, server) -> containers.put("tm1/" + name, server));
			env.adminServer().ifPresent(admin -> containers.put("adminServer", admin));
			if (scenario.equals("servers-and-web")) {
				containers.put("tm1Web", env.tm1Web());
			}
			if (scenario.equals("engine-oauth2")) {
				containers.put("tm1Engine", env.tm1Engine());
				containers.put("tm1EngineDatastore", env.tm1Engine().getDatastore());
				containers.put("keycloak", env.keycloak());
			}
			if (scenario.equals("engine")) {
				containers.put("tm1Engine", env.tm1Engine());
				containers.put("tm1EngineDatastore", env.tm1Engine().getDatastore());
			}
			if (scenario.equals("cam-security")) {
				containers.put("cognos", env.cognos());
				containers.put("keycloak", env.keycloak());
			}
			// Each inspection as Docker's CLI prints it: every language writes the same
			// text, and none reads it through its own Docker client's types
			StringBuilder log = new StringBuilder(
					"{\"scenario\": \"" + scenario + "\", \"language\": \"java\",\n\"network\": ")
				.append(docker("network", "inspect", env.network().getId()))
				.append(",\n\"containers\": {\n");
			log.append(containers.entrySet()
				.stream()
				.map(part -> "\"" + part.getKey() + "\": " + docker("inspect", part.getValue().getContainerId()))
				.collect(Collectors.joining(",\n")));
			log.append("}}\n");
			Path file = Path.of(System.getProperty("tm1.containerConformance.output"), "java", scenario + ".json");
			Files.createDirectories(file.getParent());
			Files.writeString(file, log);
			assertThat(containers).isNotEmpty();
		}
	}

	private static String docker(String... arguments) {
		try {
			String[] command = new String[arguments.length + 1];
			command[0] = "docker";
			System.arraycopy(arguments, 0, command, 1, arguments.length);
			Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0) {
				throw new IllegalStateException("docker " + String.join(" ", arguments) + " failed");
			}
			return output.strip();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
