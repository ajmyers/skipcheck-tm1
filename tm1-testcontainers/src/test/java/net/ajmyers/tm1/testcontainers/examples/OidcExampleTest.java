package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.CognosContainer;
import net.ajmyers.tm1.testcontainers.CognosContainer.Namespace;
import net.ajmyers.tm1.testcontainers.CognosSource;
import net.ajmyers.tm1.testcontainers.KeycloakContainer;
import net.ajmyers.tm1.testcontainers.RequiresCognos;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import net.ajmyers.tm1.testcontainers.Tm1Source;

/**
 * <b>OpenID Connect logons</b>: TM1 in CAM security through Cognos's OIDC namespace, with
 * Keycloak as the provider. Two ways in, without a browser:
 * <ul>
 * <li>a password, which Cognos checks with Keycloak ({@code CAMNamespace ...:OIDC});
 * <li>an ID token an application already holds, exchanged at Cognos for a CAM passport
 * ({@code CAMPassport}).
 * </ul>
 */
@RequiresCognos
class OidcExampleTest {

	@Test
	void passwordsAndTokensLogOnToTm1(CognosSource cognosSource) {
		Tm1Source tm1Source = Tm1Source.discover().orElseThrow();
		try (var keycloak = new KeycloakContainer();
				var cognos = new CognosContainer(cognosSource).withUser("admin", "adminpw", "Admins")
					.withUser("viewer", "viewerpw", "Readers")
					.withKeycloak(keycloak);
				var tm1 = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, Namespace.OIDC, "admin")) {
			tm1.start();

			// A password: TM1 hands it to Cognos, which checks it with Keycloak
			try (var admin = new Tm1Rest(tm1, cognos.camAuthorization("admin", Namespace.OIDC))) {
				assertThat(admin.get("ActiveUser?$select=Name,Type")).contains("CAMID(\\\"OIDC:u:admin\\\")")
					.contains("\"Type\":\"Admin\"");
			}

			// A token: the app holds viewer's ID token, and Cognos turns it into a
			// passport
			String passport = cognos.passport(keycloak.idToken("viewer", "viewerpw"));
			try (var viewer = new Tm1Rest(tm1, "CAMPassport " + passport)) {
				assertThat(viewer.get("ActiveUser/Groups?$select=Name")).contains("CAMID(\\\"OIDC:g:readers\\\")");
			}
		}
	}

}
