package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.CognosContainer.Namespace;
import net.ajmyers.tm1.testcontainers.PlanningAnalyticsEnvironment;
import net.ajmyers.tm1.testcontainers.RequiresCognos;
import net.ajmyers.tm1.testcontainers.RequiresTm1Web;
import net.ajmyers.tm1.testcontainers.Tm1Seed;
import net.ajmyers.tm1.testcontainers.Tm1WebSession;

/**
 * <b>A whole environment in one object</b>: {@link PlanningAnalyticsEnvironment} starts
 * and wires any mix of TM1 servers, an Admin Server, TM1 Web, PAW and Cognos security.
 * Each part's customizer gets the ordinary container class, with all its settings.
 */
class EnvironmentExampleTest {

	@Test
	@RequiresTm1Web
	void twoServersAndTm1Web() {
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA))
			.tm1("reporting")
			.tm1Web()
			.build()) {
			env.start();

			// Two servers share one Admin Server, and TM1 Web lists both
			assertThat(new Tm1WebSession(env.tm1Web()).servers(env.tm1Web().getAdminHost())).contains("planning",
					"reporting");
			try (var planning = new Tm1Rest(env.tm1("planning")); var reporting = new Tm1Rest(env.tm1("reporting"))) {
				assertThat(planning.modelCubeCount()).isPositive();
				assertThat(reporting.modelCubeCount()).isZero();
			}
		}
	}

	@Test
	@RequiresCognos
	void twoServersInCamSecurityWithLdapAndOidc() {
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("planning")
			.tm1("reporting")
			.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
			.keycloak()
			.camSecurity(Namespace.OIDC, "admin")
			.build()) {
			env.start();

			var cognos = env.cognos();
			String viewerPassport = "CAMPassport " + cognos.passport(env.keycloak().idToken("viewer", "viewerpw"));
			for (var server : env.tm1Servers().values()) {
				try (var admin = new Tm1Rest(server, cognos.camAuthorization("admin", Namespace.OIDC));
						var viewer = new Tm1Rest(server, viewerPassport)) {
					assertThat(admin.get("ActiveUser?$select=Type")).contains("\"Type\":\"Admin\"");
					assertThat(viewer.get("ActiveUser/Groups?$select=Name")).contains("CAMID(\\\"OIDC:g:readers\\\")");
				}
			}
		}
	}

}
