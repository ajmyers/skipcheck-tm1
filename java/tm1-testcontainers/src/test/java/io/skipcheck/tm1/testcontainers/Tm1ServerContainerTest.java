// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

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

	private HttpResponse<String> get(Tm1ServerContainer tm1, String path) throws Exception {
		String auth = Base64.getEncoder()
			.encodeToString((tm1.getUsername() + ":" + tm1.getPassword()).getBytes(StandardCharsets.UTF_8));
		return http.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + path))
			.header("Authorization", "Basic " + auth)
			.build(), HttpResponse.BodyHandlers.ofString());
	}

}
