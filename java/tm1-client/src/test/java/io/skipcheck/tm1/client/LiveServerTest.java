// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * How {@link LiveServer} reads a running TM1's settings, as the Python, .NET and
 * TypeScript live harnesses do.
 */
class LiveServerTest {

	private static LiveServer running(Map<String, String> settings) {
		return LiveServer.running(settings::get).orElseThrow();
	}

	@Test
	void readsNoRunningServerWithoutATm1UrlOrWithABlankOne() {
		assertThat(LiveServer.running(Map.<String, String>of()::get)).isEmpty();
		assertThat(LiveServer.running(Map.of(LiveServer.URL, "  ")::get)).isEmpty();
	}

	@Test
	void defaultsTheLoginToAdminAndApple() {
		LiveServer server = running(Map.of(LiveServer.URL, "http://tm1:8010/api/v1"));
		assertThat(server.getRestUrl()).isEqualTo("http://tm1:8010/api/v1/");
		assertThat(server.getUsername()).isEqualTo("admin");
		assertThat(server.getPassword()).isEqualTo("apple");
		assertThat(server.container()).isEmpty();
		assertThat(server.install()).isEqualTo(ServerAssumptions.Install.UNKNOWN);
		assertThat(server.verifies()).isTrue();
	}

	@Test
	void keepsAnEmptyPasswordEmpty() {
		// tm1-server's admin has none: set but empty isn't unset
		assertThat(running(Map.of(LiveServer.URL, "http://tm1:8010/api/v1/", "TM1_PASSWORD", "")).getPassword())
			.isEmpty();
	}

	@Test
	void logsInWithBasicOrThroughTheCamNamespace() {
		assertThat(running(Map.of(LiveServer.URL, "http://tm1/api/v1/", "TM1_USER", "ann", "TM1_PASSWORD", "pw"))
			.getAuthorization()).isEqualTo("Basic " + base64("ann:pw"));
		assertThat(running(Map.of(LiveServer.URL, "http://tm1/api/v1/", "TM1_USER", "ann", "TM1_PASSWORD", "pw",
				"TM1_NAMESPACE", "LDAP"))
			.getAuthorization()).isEqualTo("CAMNamespace " + base64("ann:pw:LDAP"));
	}

	@Test
	void checksTheCertificateUnlessTm1VerifyIsFalse() {
		assertThat(running(Map.of(LiveServer.URL, "https://tm1/api/v1/", "TM1_VERIFY", "false")).verifies()).isFalse();
		assertThat(running(Map.of(LiveServer.URL, "https://tm1/api/v1/", "TM1_VERIFY", "FALSE")).verifies()).isFalse();
		assertThat(running(Map.of(LiveServer.URL, "https://tm1/api/v1/", "TM1_VERIFY", "true")).verifies()).isTrue();
	}

	private static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}
