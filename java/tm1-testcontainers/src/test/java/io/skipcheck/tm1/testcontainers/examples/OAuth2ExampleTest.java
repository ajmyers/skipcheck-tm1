// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.KeycloakContainer;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;

/**
 * <b>OAuth 2.0 logins, with Keycloak</b>: the way TM1 v12 logs users in from an identity
 * provider. The engine takes tokens from a Keycloak beside it, which knows the users who
 * were given a password. A test gets a token as the user and sends it to TM1.
 */
class OAuth2ExampleTest {

	@Test
	@RequiresTm1Engine(shareDatabase = true)
	void aUserLogsInWithATokenFromKeycloak() {
		try (var keycloak = new KeycloakContainer();
				var engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow())
					.withUser("analyst", "Analysts")
					.withLogin("analyst", "analystpw")
					.withOAuth2(keycloak)) {
			engine.start();
			Tm1Database db = engine.database("tm1");

			var response = Tm1EngineRest.send(db.oauth2Login("analyst")
				.authorizeWithToken(HttpRequest.newBuilder(URI.create(db.getRestUrl() + "ActiveUser?$select=Name"))));

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"");
		}
	}

}
