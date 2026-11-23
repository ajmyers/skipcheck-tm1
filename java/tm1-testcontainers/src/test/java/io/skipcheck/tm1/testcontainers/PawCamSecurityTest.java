// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/**
 * PAW with Cognos (CAM) security, in a {@link PlanningAnalyticsEnvironment}: users log on
 * to PAW through Cognos. PAW and Cognos together need most of a developer machine's
 * Docker memory, so this class has one test, and nothing else should run beside it.
 */
@RequiresCognos
@RequiresPaw
class PawCamSecurityTest {

	@Test
	void logsOnThroughCognosAndCallsTm1() throws Exception {
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
			.paw(PawContainer::acceptLicense)
			.build()) {
			env.start();
			String url = env.paw().getUrl();

			// Browsers are sent to Cognos, as the host reaches it, to log on
			HttpResponse<String> page = new PawSession(url).get("login");
			assertThat(page.body()).contains("extLoginUrl = \"" + env.cognos().getUrl() + "v1/disp?b_action=xts.run");

			// A test user logs on with their Cognos password, and calls TM1 as themselves
			var viewer = new PawSession(url).login("viewer", "viewerpw");
			assertThat(viewer.servers()).contains("planning");
			HttpResponse<String> user = viewer.tm1Get("planning", "ActiveUser?$select=Name");
			assertThat(user.statusCode()).isEqualTo(200);
			assertThat(user.body()).contains("CAMID").containsIgnoringCase("viewer");

			// Cognos checks the password
			assertThatIOException().isThrownBy(() -> new PawSession(url).login("viewer", "not-the-password"));
		}
	}

}
