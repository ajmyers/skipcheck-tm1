package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.KeycloakContainer;
import io.skipcheck.tm1.testcontainers.RequiresCognos;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import reactor.core.publisher.Mono;

/**
 * Logging on to a TM1 in CAM security through Cognos's OpenID Connect namespace, with
 * Keycloak as the provider: a password Cognos checks with Keycloak, and an ID token the
 * application holds, exchanged at Cognos for a passport.
 */
@RequiresCognos
class CamOidcAuthenticationTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static KeycloakContainer keycloak;

	private static CognosContainer cognos;

	private static Tm1ServerContainer tm1;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in stop()
	static void start() {
		keycloak = new KeycloakContainer();
		cognos = new CognosContainer(CognosSource.discover().orElseThrow()).withUser("admin", "adminpw", "Admins")
			.withUser("viewer", "viewerpw", "Readers")
			.withKeycloak(keycloak);
		tm1 = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withCamSecurity(cognos,
				CognosContainer.Namespace.OIDC, "admin");
		tm1.start();
	}

	@AfterAll
	static void stop() {
		tm1.stop();
		cognos.stop();
		keycloak.stop();
	}

	private static Tm1Client client(Tm1Authenticator authenticator) {
		return Tm1Client.builder().baseUrl(tm1.getRestUrl()).authenticator(authenticator).build();
	}

	@Test
	void logsOnWithAPasswordCognosChecksWithTheProvider() {
		Tm1Client admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "OIDC"));
		try {
			assertThat(admin.users().activeUser(ODataQuery.none()).block(WAIT).name())
				.isEqualTo("CAMID(\"OIDC:u:admin\")");
		}
		finally {
			admin.close().block(WAIT);
		}
	}

	@Test
	void exchangesAnIdTokenForAPassport() {
		Tm1Client viewer = client(Tm1Authenticator.cognosIdToken(cognos.getUrl(), "OIDC",
				() -> Mono.fromCallable(() -> keycloak.idToken("viewer", "viewerpw"))));
		try {
			assertThat(viewer.users()
				.groups("CAMID(\"OIDC:u:viewer\")", ODataQuery.none())
				.map(Group::name)
				.collectList()
				.block(WAIT)).contains("CAMID(\"OIDC:g:readers\")");
		}
		finally {
			viewer.close().block(WAIT);
		}
	}

}
