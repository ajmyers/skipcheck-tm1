// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.skipcheck.tm1.client.version.Tm1Version;

@RequiresTm1
class Tm1ServerContainerTest {

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void startsATm1ServerAndReportsItsVersion(Tm1ServerContainer tm1) throws Exception {
		assertThat(tm1.getRestUrl()).startsWith("http://").endsWith("/api/v1/");
		assertThat(tm1.getTm1Version()).isGreaterThan(Tm1Version.parse("11"));

		HttpResponse<String> version = get(tm1, "Configuration/ProductVersion/$value");
		assertThat(version.statusCode()).isEqualTo(200);
		assertThat(Tm1Version.parse(version.body())).isEqualTo(tm1.getTm1Version());
	}

	@Test
	void startsWithAnEmptyDatabase(Tm1ServerContainer tm1) throws Exception {
		// Only control cubes (named with a leading '}') in a new database
		HttpResponse<String> cubes = get(tm1, "Cubes?$select=Name&$filter=not%20startswith(Name,'%7D')");
		assertThat(cubes.statusCode()).isEqualTo(200);
		assertThat(cubes.body()).contains("\"value\":[]");
	}

	@Test
	void sharesOneContainerAcrossTests(Tm1ServerContainer tm1) {
		assertThat(tm1).isSameAs(Tm1ServerContainer.shared());
		assertThat(tm1.isRunning()).isTrue();
	}

	@Test
	void refusesToStartOnTheWrongVersion(Tm1ServerContainer shared) {
		try (var tm1 = new Tm1ServerContainer(shared.getSource()).withExpectedVersion(Tm1Version.parse("99.0"))) {
			assertThatThrownBy(tm1::start).hasStackTraceContaining("Expected TM1 99.0 but the server from");
		}
	}

	@Test
	void failsAtOnceWhenTheContainerExits(@TempDir Path install) throws Exception {
		// An install whose programs can't run, as when TM1 lacks a library: the container
		// exits at once, and start() says so with its log, rather than waiting out the
		// startup timeout. They're executable, with an interpreter that doesn't exist, so
		// exec fails the same way whatever the host's file permissions
		Files.createDirectories(install.resolve("bin64"));
		for (String program : List.of("tm1s.exe", "tm1admsrv.exe")) {
			Path file = Files.writeString(install.resolve("bin64").resolve(program), "#!/not/a/program\n");
			file.toFile().setExecutable(true, false);
		}
		long started = System.nanoTime();
		try (var tm1 = new Tm1ServerContainer(new Tm1Source.InstallDir(install))) {
			assertThatThrownBy(tm1::start).hasStackTraceContaining("The container exited while starting, with code")
				.hasStackTraceContaining("[tm1-entrypoint] Starting TM1 Admin Server");
		}
		// Well inside the five-minute startup timeout, with room for pulling the image
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMinutes(4));
	}

	private HttpResponse<String> get(Tm1ServerContainer tm1, String path) throws Exception {
		String auth = Base64.getEncoder()
			.encodeToString((tm1.getUsername() + ":" + tm1.getPassword()).getBytes(StandardCharsets.UTF_8));
		return http.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + path))
			.header("Authorization", "Basic " + auth)
			.build(), HttpResponse.BodyHandlers.ofString());
	}

}
