// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.CognosContainer.Namespace;

/** Wiring only: nothing here starts a container. */
class PlanningAnalyticsEnvironmentTest {

	private PlanningAnalyticsEnvironment.Builder builder() {
		return PlanningAnalyticsEnvironment.builder()
			.tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
			.tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
			.cognosSource(new CognosSource.PrivateImage("cognos-server:test"));
	}

	@Test
	void oneServerUsesItsOwnAdminServer() {
		var env = builder().tm1("planning").build();
		var planning = env.tm1("planning");
		assertThat(env.adminServer()).isEmpty();
		assertThat(planning.getEnvMap()).containsEntry("TM1_SERVER_NAME", "planning")
			.doesNotContainKey("TM1_ADMIN_HOST");
		assertThat(planning.getNetworkAliases()).contains("planning");
		assertThat(planning.getNetwork()).isSameAs(env.network());
	}

	@Test
	void severalServersShareAnAdminServerThatTm1WebListsFrom() {
		var env = builder().tm1("planning").tm1("reporting").tm1Web().build();
		var admin = env.adminServer().orElseThrow();
		assertThat(env.tm1Servers()).containsOnlyKeys("planning", "reporting");
		for (var server : env.tm1Servers().values()) {
			assertThat(server.getAdminServer()).isSameAs(admin);
			assertThat(server.getNetwork()).isSameAs(env.network());
		}
		assertThat(env.tm1Web().getAdminServer()).isSameAs(admin);
		assertThat(env.tm1Web().getDependencies()).contains(env.tm1("planning"), env.tm1("reporting"));
		assertThat(env.containers()).startsWith(admin).endsWith(env.tm1Web());
	}

	@Test
	void cognosPutsEveryServerInCamSecurity() {
		var env = builder().tm1("planning")
			.tm1("reporting")
			.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
			.build();
		for (var server : env.tm1Servers().values()) {
			server.configure();
			assertThat(server.getCognos()).isSameAs(env.cognos());
			// The first test user is the TM1 administrator unless camSecurity names
			// others
			assertThat(server.getEnvMap()).containsEntry("TM1_CAM_ADMINS", "admin")
				.containsEntry("TM1_CAM_GROUPS", "Admins Readers")
				.containsEntry("TM1_CAM_NAMESPACE", "LDAP");
		}
	}

	@Test
	void keycloakAddsOidc() {
		var env = builder().tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw").withUser("boss", "bosspw"))
			.keycloak()
			.camSecurity(Namespace.OIDC, "boss")
			.build();
		var planning = env.tm1("planning");
		planning.configure();
		assertThat(env.cognos().getKeycloak()).isSameAs(env.keycloak());
		assertThat(planning.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "OIDC")
			.containsEntry("TM1_CAM_ADMINS", "boss");
		assertThat(env.containers()).startsWith(env.keycloak(), env.cognos());
	}

	@Test
	void customizersSeeTheWiredContainerAndCanChangeIt() {
		var env = builder().tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA).withEnv("TM1_CFG_MaxThreads", "4"))
			.build();
		assertThat(env.tm1("planning").getEnvMap()).containsEntry("TM1_SEED", "sdata")
			.containsEntry("TM1_CFG_MaxThreads", "4")
			.containsEntry("TM1_SERVER_NAME", "planning");
		assertThat(env.tm1("planning").getUsername()).isEqualTo(Tm1Seed.SDATA.username());
	}

	@Test
	void refusesMissingOrUnsupportedParts() {
		assertThatIllegalStateException().isThrownBy(() -> builder().build()).withMessageContaining("at least one");
		assertThatIllegalStateException().isThrownBy(() -> builder().tm1("a").keycloak().build())
			.withMessageContaining("cognos");
		assertThatIllegalStateException()
			.isThrownBy(() -> builder().tm1("a").cognos(c -> c.withUser("u", "p")).camSecurity(Namespace.OIDC).build())
			.withMessageContaining("keycloak");
		assertThatIllegalStateException()
			.isThrownBy(() -> builder().tm1("a").cognos(c -> c.withUser("u", "p")).paw(paw -> {
			}).build())
			.withMessageContaining("PAW");
		assertThatIllegalStateException().isThrownBy(() -> builder().tm1("a").cognos(c -> {
		}).build()).withMessageContaining("test users");
		assertThatIllegalArgumentException().isThrownBy(() -> builder().tm1("a").tm1("a"))
			.withMessageContaining("already");
		assertThatIllegalArgumentException().isThrownBy(() -> builder().tm1("two words"));
		var env = builder().tm1("a").build();
		assertThatIllegalStateException().isThrownBy(env::tm1Web).withMessageContaining("tm1Web()");
		assertThatIllegalArgumentException().isThrownBy(() -> env.tm1("b")).withMessageContaining("[a]");
	}

}
