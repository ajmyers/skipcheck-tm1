// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.model.UserType;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.KeycloakContainer;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;

/**
 * {@code @ServiceConnection} to a TM1 v12 engine with OAuth 2.0 (Keycloak): the client
 * logs in with bearer tokens from Keycloak.
 */
@RequiresTm1Engine
@SpringBootTest(classes = Tm1EngineOAuth2ServiceConnectionTest.App.class)
class Tm1EngineOAuth2ServiceConnectionTest {

	private static final Duration WAIT = Duration.ofSeconds(60);

	@Autowired
	private Tm1Client client;

	@Autowired
	private Tm1ConnectionDetails connection;

	@Test
	void logsInWithOAuth2BearerTokens() {
		assertThat(connection.getDialect())
			.isEqualTo(Tm1Dialect.v12(Tm1EngineContainer.DEFAULT_INSTANCE, Tm1EngineContainer.DEFAULT_DATABASE));
		assertThat(connection.getAuthenticator().describe()).isEqualTo("Bearer");
		User user = client.users().activeUser(ODataQuery.none()).block(WAIT);
		assertThat(user.name()).isEqualToIgnoringCase("admin");
		assertThat(user.type()).isEqualTo(UserType.ADMIN);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		KeycloakContainer keycloak() {
			return new KeycloakContainer();
		}

		@Bean
		@ServiceConnection
		Tm1EngineContainer tm1(KeycloakContainer keycloak) {
			return new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withLogin("admin", "adminpw")
				.withOAuth2(keycloak);
		}

	}

}
