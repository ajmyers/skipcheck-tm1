// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startable;

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

	@Test
	void reuseNeedsTheBuilderAndTheDevelopersSwitch() {
		assertThat(builder().tm1("a").build(true).reused()).isFalse();
		var notSwitchedOn = builder().tm1("a").reuse(true).build(false);
		assertThat(notSwitchedOn.reused()).isFalse();
		assertThat(notSwitchedOn.network()).isNotInstanceOf(PlanningAnalyticsEnvironment.ReusableNetwork.class);
		var reused = builder().tm1("a").reuse(true).build(true);
		assertThat(reused.reused()).isTrue();
		// Not started, so not yet marked: start() does that
		assertThat(reused.tm1("a").isShouldBeReused()).isFalse();
	}

	@Test
	void aReusedEnvironmentsNetworkIsNamedAfterItsIdentity() {
		var env = builder().tm1("planning").tm1Web().reuse(true).build(true);
		assertThat(env.identity()).matches("[0-9a-f]{16}");
		assertThat(env.network()).isInstanceOfSatisfying(PlanningAnalyticsEnvironment.ReusableNetwork.class,
				network -> assertThat(network.getName()).isEqualTo("tm1-environment-" + env.identity()));
		for (var server : env.tm1Servers().values()) {
			assertThat(server.getNetwork()).isSameAs(env.network());
		}
		// The developer's own network is used as it is
		var own = new PlanningAnalyticsEnvironment.ReusableNetwork(() -> "mine");
		assertThat(builder().tm1("planning").network(own).reuse(true).build(true).network()).isSameAs(own);
	}

	@Test
	void theIdentityIsTheSameForTheSameConfigurationAndDiffersOtherwise() {
		assertThat(builder().tm1("planning").tm1Web().reuse(true).build(true).identity())
			.isEqualTo(builder().tm1("planning").tm1Web().reuse(true).build(true).identity());
		List<PlanningAnalyticsEnvironment.Builder> configurations = List.of(builder().tm1("planning"),
				builder().tm1("reporting"), builder().tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA)),
				builder().tm1("planning", tm1 -> tm1.withEnv("TM1_CFG_MaxThreads", "4")),
				builder().tm1("planning").tm1("reporting"), builder().tm1("planning").adminServer(),
				builder().tm1("planning").tm1Web(),
				builder().tm1("planning").tm1Web(web -> web.withEnv("TM1_WEB_HEAP", "2g")),
				builder().tm1("planning").tm1Source(new Tm1Source.PrivateImage("tm1-server:other")),
				builder().tm1("planning").cognos(c -> c.withUser("admin", "adminpw")),
				builder().tm1("planning").cognos(c -> c.withUser("admin", "otherpw")),
				builder().tm1("planning").cognos(c -> c.withUser("admin", "adminpw").withUser("boss", "bosspw")),
				builder().tm1("planning")
					.cognos(c -> c.withUser("admin", "adminpw").withUser("boss", "bosspw"))
					.camSecurity(Namespace.LDAP, "boss"),
				builder().tm1("planning").cognos(c -> c.withUser("admin", "adminpw")).keycloak());
		List<String> identities = configurations.stream().map(b -> b.reuse(true).build(true).identity()).toList();
		assertThat(identities).doesNotHaveDuplicates();
	}

	@Test
	void startingAReusedEnvironmentMarksEveryContainerWithItsIdentity() {
		var env = builder().tm1("planning").tm1("reporting").tm1Web().reuse(true).build(true);
		env.prepareForReuse();
		for (Startable part : env.containers()) {
			var container = (GenericContainer<?>) part;
			assertThat(container.isShouldBeReused()).as(container.toString()).isTrue();
			assertThat(container.getLabels()).containsEntry("io.skipcheck.tm1.environment", env.identity());
			// Testcontainers' random alias is gone: it would change every run
			assertThat(container.getNetworkAliases()).noneMatch(alias -> alias.startsWith("tc-"));
		}
		assertThat(env.tm1("planning").getNetworkAliases()).containsExactly("planning");
		assertThat(env.adminServer().orElseThrow().getNetworkAliases()).containsExactly("tm1-admin");
	}

	/** The image a container runs, without pulling it as getDockerImageName does. */
	private static String image(GenericContainer<?> container) {
		Matcher name = Pattern.compile("imageName=([^,)]+)").matcher(container.getImage().toString());
		assertThat(name.find()).as(container.getImage().toString()).isTrue();
		return name.group(1);
	}

	private static Path fixture(String name) {
		return Path.of(System.getProperty("tm1.paEnvironment.fixtures")).resolve(name);
	}

	@Test
	void fromConfigBuildsTheExampleWithEverything() {
		var env = PlanningAnalyticsEnvironment
			.fromConfig(Path.of(System.getProperty("tm1.paEnvironment.examples")).resolve("everything.yaml"))
			.tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
			.tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
			.cognosSource(new CognosSource.PrivateImage("cognos-server:test"))
			.build();
		assertThat(env.tm1Servers()).containsOnlyKeys("planning", "reporting");
		assertThat(env.tm1("planning").getEnvMap()).containsEntry("TM1_SEED", "sdata");
		assertThat(env.tm1("reporting").getEnvMap()).containsEntry("TM1_SEED", "empty");
		assertThat(env.adminServer()).isPresent();
		assertThat(env.tm1Web().getEnvMap()).containsEntry("TM1_WEB_HEAP", "1g");
		assertThat(env.cognos().getUsers()).extracting(CognosContainer.User::name)
			.containsExactly("admin", "analyst", "viewer");
		assertThat(env.cognos().getUser("analyst").groups()).containsExactly("Analysts", "Readers");
		assertThat(env.cognos().getEnvMap()).containsEntry("COGNOS_MAX_MEMORY", "6144");
		assertThat(env.cognos().getKeycloak()).isSameAs(env.keycloak());
		// The sources set on the builder win over the file's ibm folder
		assertThat(env.tm1("planning").getSource()).isEqualTo(new Tm1Source.PrivateImage("tm1-server:test"));
		var planning = env.tm1("planning");
		planning.configure();
		assertThat(planning.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "LDAP")
			.containsEntry("TM1_CAM_ADMINS", "admin")
			.containsEntry("TM1_CAM_GROUPS", "Admins Analysts Readers");
	}

	@Test
	void fromConfigMapsEverySetting() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("settings.yaml")).build();
		Path ibm = fixture("ibm").toAbsolutePath().normalize();
		var planning = env.tm1("planning");
		assertThat(planning.getEnvMap()).containsEntry("TM1_SEED", "sdata")
			.containsEntry("TM1_CFG_MaxThreads", "4")
			.containsEntry("TM1_CFG_EnableNewHierarchyCreation", "True")
			.containsEntry("TM1_CFG_DefaultMeasuresDimension", "Measures");
		// adminServer: true, with one server
		assertThat(planning.getAdminServer()).isSameAs(env.adminServer().orElseThrow());
		assertThat(env.tm1Web().getEnvMap()).containsEntry("TM1_WEB_HEAP", "2g");
		assertThat(env.cognos().getUser("boss").groups()).containsExactly("Admins", "Planners");
		assertThat(env.cognos().getEnvMap()).containsEntry("COGNOS_MAX_MEMORY", "8192");
		planning.configure();
		assertThat(planning.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "OIDC")
			.containsEntry("TM1_CAM_ADMINS", "boss");
		// ibm is every part's source; images are what they run
		assertThat(planning.getSource()).isEqualTo(new Tm1Source.Bundle(ibm, null));
		assertThat(env.tm1Web().getSource()).isEqualTo(new Tm1WebSource.Bundle(ibm, null));
		assertThat(env.cognos().getSource()).isEqualTo(new CognosSource.Zip(ibm, null));
		assertThat(image(planning)).isEqualTo("registry.example.com/tm1-server:local");
		assertThat(image(env.adminServer().orElseThrow())).isEqualTo("registry.example.com/tm1-server:local");
		assertThat(image(env.tm1Web())).isEqualTo("registry.example.com/tm1-web:local");
		assertThat(image(env.cognos())).isEqualTo("registry.example.com/cognos-server:local");
		assertThat(image(env.keycloak())).isEqualTo("registry.example.com/keycloak:26");
	}

	@Test
	void fromConfigRunsPawFromTheIbmFolder() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("paw.yaml")).build();
		Path ibm = fixture("ibm").toAbsolutePath().normalize();
		assertThat(env.paw().getSource()).isEqualTo(new PawSource(ibm.resolve("ipa_workspace_local_0.0.0.zip")));
		assertThat(env.paw().licenseAccepted()).isTrue();
		assertThat(env.paw().getTm1Server()).isSameAs(env.tm1("tm1"));
		assertThat(env.adminServer()).isEmpty();
	}

	@Test
	void fromConfigRefusesWhatItCannotRun() throws IOException {
		try (var files = Files.list(fixture("invalid"))) {
			List<Path> invalid = files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
			assertThat(invalid).isNotEmpty();
			for (Path file : invalid) {
				String first = Files.readAllLines(file).get(0);
				assertThat(first).as(file.toString()).startsWith("# error: ");
				assertThatIllegalArgumentException().as(file.toString())
					.isThrownBy(() -> PlanningAnalyticsEnvironment.fromConfig(file))
					.withMessageStartingWith(file.getFileName() + ": ")
					.withMessageContaining(first.substring("# error: ".length()));
			}
		}
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PlanningAnalyticsEnvironment.fromConfig(fixture("missing.yaml")))
			.withMessageContaining("not found");
	}

}
