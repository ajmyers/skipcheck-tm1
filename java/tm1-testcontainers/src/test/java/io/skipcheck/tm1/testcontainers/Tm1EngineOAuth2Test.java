// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.examples.Tm1EngineRest;

/**
 * OAuth 2.0 logins against real TM1 v12 and Keycloak: an engine of the class's own,
 * taking tokens from a Keycloak beside it.
 */
@RequiresTm1Engine(shareDatabase = true)
class Tm1EngineOAuth2Test {

	private static KeycloakContainer keycloak;

	private static Tm1EngineContainer engine;

	private static Tm1Database planning;

	@BeforeAll
	static void start() {
		keycloak = new KeycloakContainer();
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withDatabase("planning")
			.withUser("analyst", "Analysts")
			.withLogin("analyst", "analyst$pw")
			.withLogin("admin", "adminpw")
			.withOAuth2(keycloak);
		engine.start();
		planning = engine.database("planning");
	}

	@AfterAll
	static void stop() {
		if (engine != null) {
			engine.stop();
		}
		if (keycloak != null) {
			keycloak.stop();
		}
	}

	@Test
	void aKeycloakTokenLogsTheUserIn() {
		var analyst = planning.oauth2Login("analyst");
		// The token on a request of its own: TM1 answers, and gives a session
		var response = Tm1EngineRest.send(analyst.authorizeWithToken(
				HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser?$select=Name,Type"))));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"").contains("\"Type\":\"User\"");
		assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("TM1SessionId="));
		// And the session that gives serves later requests
		assertThat(analyst.cookie()).startsWith("TM1SessionId=");
		assertThat(Tm1EngineRest.get(planning, analyst, "ActiveUser").statusCode()).isEqualTo(200);
	}

	@Test
	void theAdministratorLogsInThroughOAuth2Too() {
		var response = Tm1EngineRest.get(planning, planning.oauth2Login("admin"), "ActiveUser?$select=Name,Type");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"Type\":\"Admin\"");
	}

	@Test
	void aTokenOfAnotherRealmIsRefused() {
		// Signed by the same Keycloak, but not by the issuer TM1 trusts, and without its
		// audience
		String other = keycloak.accessToken("master", "admin-cli", "admin", "admin");
		var response = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser"))
			.header("Authorization", "Bearer " + other));
		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void somethingThatIsntATokenIsRefused() {
		var response = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser"))
			.header("Authorization", "Bearer not.a.token"));
		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void anExpiredTokenIsRefused() throws Exception {
		// Tokens that live a second, for as long as it takes to get one
		String admin = keycloak.accessToken("master", "admin-cli", "admin", "admin");
		setTokenLife(admin, 1);
		String token;
		try {
			token = planning.oauth2Login("analyst").token();
		}
		finally {
			setTokenLife(admin, 300);
		}
		// TM1 may allow a clock some slack: wait for it to refuse, for up to two minutes
		long started = System.nanoTime();
		int status = 0;
		while (System.nanoTime() - started < 120_000_000_000L) {
			status = Tm1EngineRest
				.send(HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser"))
					.header("Authorization", "Bearer " + token))
				.statusCode();
			if (status == 401) {
				break;
			}
			Thread.sleep(2000);
		}
		System.out.printf("TM1 refused the expired token after %.0f s%n", (System.nanoTime() - started) / 1e9);
		assertThat(status).isEqualTo(401);
	}

	/** Sets how long the realm's access tokens live, through Keycloak's admin API. */
	private static void setTokenLife(String adminToken, int seconds) {
		var response = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(keycloak.getUrl() + "/admin/realms/tm1"))
			.header("Authorization", "Bearer " + adminToken)
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString("{\"accessTokenLifespan\":" + seconds + "}")));
		assertThat(response.statusCode()).isEqualTo(204);
	}

	@Test
	void loginsThroughTheApplicationStillWork() {
		var analyst = planning.login("analyst");
		assertThat(analyst.isOAuth2()).isFalse();
		assertThat(Tm1EngineRest.get(planning, analyst, "ActiveUser").statusCode()).isEqualTo(200);
		// TM1's own token isn't one to send on a request
		assertThatIllegalStateException().isThrownBy(() -> analyst.authorizeWithToken(HttpRequest.newBuilder()));
		// And an OAuth 2.0 login has no session endpoint to ask
		assertThatIllegalStateException().isThrownBy(() -> planning.oauth2Login("analyst").session("Bearer x"));
	}

	@Test
	void theLoginSaysWhereItsTokensComeFrom() {
		var analyst = planning.oauth2Login("analyst");
		assertThat(analyst.getTokenUrl()).isEqualTo(keycloak.getUrl() + "/realms/tm1/protocol/openid-connect/token");
		assertThat(analyst.getClientId()).isEqualTo("tm1");
		assertThat(analyst.getPassword()).isEqualTo("analyst$pw");
		assertThat(analyst.token().split("\\.")).hasSize(3);
	}

}
