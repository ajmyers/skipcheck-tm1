// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.testcontainers.KeycloakContainer;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1Login;
import reactor.core.publisher.Mono;

/**
 * Logging on to a TM1 v12 engine through its application (service-to-service) and through
 * OAuth 2.0 bearer tokens from Keycloak.
 */
@RequiresTm1Engine
class Tm1EngineAuthTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static KeycloakContainer keycloak;

	private static Tm1EngineContainer engine;

	private static Tm1Database database;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in stop()
	static void start() {
		keycloak = new KeycloakContainer();
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withUser("analyst", "Analysts")
			.withLogin("analyst", "analystpw")
			.withOAuth2(keycloak);
		engine.start();
		database = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);
	}

	@AfterAll
	static void stop() {
		try {
			if (engine != null) {
				engine.stop();
			}
		}
		finally {
			if (keycloak != null) {
				keycloak.stop();
			}
		}
	}

	private static Tm1Client client(Tm1Authenticator authenticator) {
		return Tm1Client.builder()
			.baseUrl(database.getRestUrl())
			.dialect(Tm1Dialect.v12(database.getInstance(), database.getName()))
			.authenticator(authenticator)
			.build();
	}

	@Test
	void logsOnThroughApplicationCredentials() {
		Tm1Login login = database.login("analyst");
		Tm1Client analyst = client(
				Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), login.getUser()));
		try {
			assertThat(analyst.users().activeUser(ODataQuery.none()).block(WAIT).name())
				.isEqualToIgnoringCase("analyst");
		}
		finally {
			analyst.close().block(WAIT);
		}
	}

	@Test
	void logsOnWithABearerToken() {
		Tm1Login oauth2 = database.oauth2Login("analyst");
		Tm1Client analyst = client(Tm1Authenticator.bearer(() -> Mono.fromCallable(oauth2::token)));
		try {
			assertThat(analyst.users().activeUser(ODataQuery.none()).block(WAIT).name())
				.isEqualToIgnoringCase("analyst");
		}
		finally {
			analyst.close().block(WAIT);
		}
	}

}
