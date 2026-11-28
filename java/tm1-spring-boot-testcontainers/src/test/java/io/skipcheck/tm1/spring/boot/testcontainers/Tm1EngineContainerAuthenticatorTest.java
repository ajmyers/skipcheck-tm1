// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.KeycloakContainer;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Offline unit tests for {@link Tm1EngineContainerConnectionDetailsFactory}: database
 * selection and OAuth 2.0 / pass-through authenticator selection without starting
 * containers.
 */
class Tm1EngineContainerAuthenticatorTest {

	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	@SuppressWarnings("resource") // never started
	void prefersTheDefaultDatabaseWhenMultipleExist() {
		Tm1EngineContainer engine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.withDatabase("other", Tm1EngineContainer.DEFAULT_DATABASE)
			.withDatabase("planning")
			.withDatabase(Tm1EngineContainer.DEFAULT_DATABASE);
		Tm1Database otherInstance = engine.database("other", Tm1EngineContainer.DEFAULT_DATABASE);
		Tm1Database planning = engine.database("planning");
		Tm1Database def = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);

		assertThat(Tm1EngineContainerConnectionDetailsFactory.selectDatabase(List.of(otherInstance, planning, def)))
			.isEqualTo(def);
		assertThat(Tm1EngineContainerConnectionDetailsFactory.selectDatabase(List.of(planning))).isEqualTo(planning);
		assertThatThrownBy(() -> Tm1EngineContainerConnectionDetailsFactory.selectDatabase(List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no databases");
	}

	@Test
	@SuppressWarnings("resource") // never started
	void logsInWithBearerTokensWhenOAuth2HasAnAdminLogin() {
		KeycloakContainer keycloak = new KeycloakContainer();
		Tm1EngineContainer engine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.withLogin("admin", "adminpw")
			.withOAuth2(keycloak);
		Tm1Database db = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);

		assertThat(Tm1EngineContainerConnectionDetailsFactory.authenticator(db).describe()).isEqualTo("Bearer");
	}

	@Test
	@SuppressWarnings("resource") // never started
	void fallsBackToApplicationCredentialsWhenOAuth2HasNoAdminLogin() {
		KeycloakContainer keycloak = new KeycloakContainer();
		Tm1EngineContainer engine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.withUser("analyst", "Analysts")
			.withLogin("analyst", "analystpw")
			.withOAuth2(keycloak);
		Tm1Database db = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);

		// Falls past oauth2Login("admin") to database.login(), which asks the started
		// container for its application credentials (rather than failing on missing admin
		// OAuth2 password)
		assertThatThrownBy(() -> Tm1EngineContainerConnectionDetailsFactory.authenticator(db))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("has no password");
	}

	@Test
	@SuppressWarnings("resource") // never started
	void logsInWithPassThroughServerCredentialsWhenPassThroughIsConfigured() {
		Tm1ServerContainer nativeServer = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"))
			.withCredentials("admin", "apple");
		Tm1EngineContainer nativeEngine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.withPassThrough(nativeServer);
		Tm1Database nativeDb = nativeEngine.database(Tm1EngineContainer.DEFAULT_DATABASE);

		assertThat(Tm1EngineContainerConnectionDetailsFactory.authenticator(nativeDb).authorization().block(WAIT))
			.isEqualTo(nativeServer.getAuthorization());

		CognosContainer cognos = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
			.withUser("admin", "adminpw", "Admins");
		Tm1ServerContainer camServer = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"))
			.withCamSecurity(cognos, CognosContainer.Namespace.LDAP, "admin")
			.withCredentials("admin", "adminpw");
		Tm1EngineContainer camEngine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.withPassThrough(camServer);
		Tm1Database camDb = camEngine.database(Tm1EngineContainer.DEFAULT_DATABASE);

		assertThat(Tm1EngineContainerConnectionDetailsFactory.authenticator(camDb).authorization().block(WAIT))
			.isEqualTo(CognosContainer.camAuthorization("admin", "adminpw", "LDAP"));
	}

}
