// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	void everyContainerNamesTheLanguageThatStartedIt() {
		var env = builder().tm1("planning")
			.tm1("reporting")
			.tm1Web()
			.cognos(cognos -> cognos.withUser("admin", "adminpw"))
			.keycloak()
			.build();
		assertThat(env.containers()).hasSize(6);
		for (Startable part : env.containers()) {
			var container = (GenericContainer<?>) part;
			// The same label in every language, so docker ps finds what this library
			// started
			assertThat(container.getLabels()).as(container.toString())
				.containsEntry("io.skipcheck.tm1.testcontainers", "java");
		}
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
	void pawLogsOnToTm1WithoutCognos() {
		var env = builder().tm1("planning").paw(PawContainer::acceptLicense).pawSource(pawSource()).build();
		assertThat(env.paw().scriptSettings("10.0.0.2", "10.0.0.3", null)).containsEntry("PAW_AUTH_MODE", "tm1")
			.containsEntry("PAW_TM1_LOGIN_URL", "http://10.0.0.2:8010")
			.containsEntry("PAW_TM1_ADMIN_URL", "http://10.0.0.3:5895")
			.doesNotContainKeys("PAW_CAM_SERVLET", "PAW_CAM_GATEWAY", "PAW_CAM_NAMESPACE");
	}

	@Test
	void pawWithCognosLogsOnThroughCam() {
		var env = builder().tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw"))
			.paw(PawContainer::acceptLicense)
			.pawSource(pawSource())
			.build();
		assertThat(env.paw().getTm1Server()).isSameAs(env.tm1("planning"));
		// The dispatcher on the network checks logons; browsers go to Cognos on the host
		assertThat(env.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/"))
			.containsEntry("PAW_AUTH_MODE", "cam")
			.containsEntry("PAW_CAM_SERVLET", "http://cognos:9300/p2pd/servlet/dispatch")
			.containsEntry("PAW_CAM_GATEWAY", "http://localhost:9300/bi/v1/disp")
			.containsEntry("PAW_CAM_NAMESPACE", "LDAP");
		// The TM1 server's namespace
		var oidc = builder().tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw"))
			.keycloak()
			.camSecurity(Namespace.OIDC)
			.paw(PawContainer::acceptLicense)
			.pawSource(pawSource())
			.build();
		assertThat(oidc.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/"))
			.containsEntry("PAW_CAM_NAMESPACE", "OIDC");
		assertThatIllegalStateException().isThrownBy(() -> env.paw().scriptSettings("10.0.0.2", "10.0.0.2", null))
			.withMessageContaining("Cognos");
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

	private static PawSource pawSource() {
		return new PawSource(fixture("ibm").resolve("ipa_workspace_local_0.0.0.zip"));
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
	void fromConfigRunsPawWithCognos() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("paw-cognos.yaml")).build();
		assertThat(env.paw().getTm1Server()).isSameAs(env.tm1("tm1"));
		assertThat(env.tm1("tm1").getCognos()).isSameAs(env.cognos());
		assertThat(env.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/"))
			.containsEntry("PAW_AUTH_MODE", "cam")
			.containsEntry("PAW_CAM_NAMESPACE", "LDAP");
	}

	@Test
	void anEngineRunsBesideTheTm1Servers() {
		var env = engineBuilder().tm1("legacy")
			.tm1Engine(engine -> engine.withDatabase("planning").withUser("analyst", "Analysts"))
			.build();
		var engine = env.tm1Engine();
		assertThat(engine.getNetwork()).isSameAs(env.network()).isSameAs(env.tm1("legacy").getNetwork());
		assertThat(engine.getSource()).isEqualTo(new Tm1EngineSource.PrivateImage("tm1-engine:test"));
		// Before anything configures or starts it: the environment starts its datastore
		// first
		assertThat(engine.getDatastore().getNetwork()).isSameAs(env.network());
		// Its name on the network, as in pa-setup's compose project
		assertThat(engine.getNetworkAliases()).contains("tm1-engine");
		engine.configure();
		// Its datastore is the engine's own, on the environment's network with it
		assertThat(engine.getDatastore().getNetwork()).isSameAs(env.network());
		assertThat(engine.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning")
			.containsEntry("TM1_USERS", "analyst:Analysts");
		assertThat(env.tm1Database("planning").getName()).isEqualTo("planning");
		// Started with TM1 11's parts, and before PAW
		assertThat(env.containers()).containsExactly(env.tm1("legacy"), engine);
	}

	@Test
	void anEngineAloneIsAnEnvironment() {
		var env = engineBuilder().tm1Engine().build();
		assertThat(env.tm1Servers()).isEmpty();
		assertThat(env.adminServer()).isEmpty();
		assertThat(env.containers()).containsExactly(env.tm1Engine());
		assertThat(env.tm1Database("tm1").getInstance()).isEqualTo("tm1");
		assertThatIllegalStateException().isThrownBy(() -> builder().tm1("planning").build().tm1Engine())
			.withMessageContaining("no TM1 v12 engine; add it with tm1Engine()");
		assertThatIllegalStateException().isThrownBy(() -> PlanningAnalyticsEnvironment.builder().build())
			.withMessageContaining("tm1(name), or a TM1 v12 engine with tm1Engine()");
	}

	@Test
	void theAdminServerTm1WebPawAndCognosNeedATm1ServerNotAnEngine() {
		for (var incomplete : List.of(engineBuilder().tm1Engine().tm1Web(), engineBuilder().tm1Engine().adminServer(),
				engineBuilder().tm1Engine().pawSource(pawSource()).paw(PawContainer::acceptLicense),
				engineBuilder().tm1Engine().cognos(cognos -> cognos.withUser("admin", "adminpw")))) {
			assertThatIllegalStateException().isThrownBy(incomplete::build)
				.withMessageContaining("are TM1 11's: add a TM1 server with tm1(name)");
		}
	}

	@Test
	void aServerCantTakeTheEnginesNamesOnTheNetwork() {
		for (String name : List.of("tm1-datastore", "TM1-Engine")) {
			assertThatIllegalStateException().isThrownBy(() -> engineBuilder().tm1(name).tm1Engine().build())
				.withMessageContaining("A TM1 server can't be named " + name + " beside a TM1 v12 engine");
		}
		// Without an engine the names are free
		assertThat(builder().tm1("tm1-datastore").build().tm1Servers()).containsKey("tm1-datastore");
	}

	@Test
	void fromConfigKeepsTheFilesEngineUnderACustomizerAndItsImageWithoutASection() {
		// A customizer adds to the file's databases and users; it doesn't replace them
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("tm1-engine.yaml"))
			.tm1Engine(engine -> engine.withUser("extra"))
			.build();
		env.tm1Engine().configure();
		assertThat(env.tm1Engine().getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning,tm1/Sales,other/sales")
			.containsEntry("TM1_USERS", "analyst:Analysts:Planners,viewer,boss:ADMIN,planner:Planners,extra");

		// images.tm1Engine is the image of an engine added in code too
		var added = PlanningAnalyticsEnvironment.fromConfig(fixture("engine-image-only.yaml")).tm1Engine().build();
		assertThat(image(added.tm1Engine())).isEqualTo("registry.example.com/tm1-engine:local");
		assertThat(added.tm1Engine().getSource())
			.isEqualTo(new Tm1EngineSource.Bundle(fixture("ibm").toAbsolutePath().normalize(), null));
	}

	@Test
	void anEngineTakesOAuth2TokensFromTheEnvironmentsKeycloak() {
		var env = engineBuilder().tm1Engine(engine -> engine.withUser("analyst").withLogin("analyst", "analystpw"))
			.tm1EngineOAuth2()
			.build();
		// Added for the engine, without Cognos
		var keycloak = env.keycloak();
		assertThat(env.tm1Engine().getKeycloak()).isSameAs(keycloak);
		assertThat(keycloak.getNetwork()).isSameAs(env.network()).isSameAs(env.tm1Engine().getNetwork());
		assertThat(env.tm1Engine().getEnvMap()).containsEntry("TM1_OAUTH2_ISSUER_URL",
				"http://keycloak:8080/realms/tm1");
		assertThat(env.containers()).containsExactly(keycloak, env.tm1Engine());
		assertThat(env.tm1Database("tm1").oauth2Login("analyst").getPassword()).isEqualTo("analystpw");

		// A Keycloak that's there for the engine alone gives Cognos no OIDC namespace
		var beside = engineBuilder().tm1("legacy")
			.cognos(cognos -> cognos.withUser("admin", "adminpw"))
			.tm1Engine(engine -> engine.withLogin("admin", "adminpw"))
			.tm1EngineOAuth2()
			.build();
		assertThat(beside.cognos().getKeycloak()).isNull();
		assertThat(beside.tm1Engine().getKeycloak()).isSameAs(beside.keycloak());

		assertThatIllegalStateException().isThrownBy(() -> builder().tm1("legacy").tm1EngineOAuth2().build())
			.withMessageContaining("OAuth 2.0 logins are a TM1 v12 engine's: add tm1Engine() too");
		assertThatIllegalStateException().isThrownBy(() -> builder().tm1("legacy").keycloak().build())
			.withMessageContaining("add cognos(...) or tm1EngineOAuth2() too");
	}

	@Test
	void fromConfigTurnsOAuth2OnWithTheUsersWhoHaveAPassword() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("engine-oauth2.yaml")).build();
		var engine = env.tm1Engine();
		assertThat(engine.getKeycloak()).isSameAs(env.keycloak());
		engine.configure();
		// TM1 gets names and groups; Keycloak the passwords
		assertThat(engine.getEnvMap()).containsEntry("TM1_USERS", "analyst:Analysts,boss,viewer,planner:Planners");
		var db = env.tm1Database("tm1");
		assertThat(db.oauth2Login("analyst").getPassword()).isEqualTo("analyst$pw");
		assertThat(db.oauth2Login("boss").getPassword()).isEqualTo("bosspw");
		assertThat(db.oauth2Login("planner").getPassword()).isEqualTo("plannerpw");
		assertThatIllegalArgumentException().isThrownBy(() -> db.oauth2Login("viewer"));

		// oauth2: false is off, with the passwords in the file left readable
		var off = PlanningAnalyticsEnvironment.fromConfig(fixture("engine-oauth2-off.yaml")).build();
		assertThat(off.tm1Engine().getKeycloak()).isNull();
		assertThat(off.tm1Engine().getEnvMap()).doesNotContainKey("TM1_OAUTH2_ISSUER_URL");
	}

	@Test
	void anEnvironmentWithAnEngineCantBeReused() {
		// Whatever the developer's switch says: the same code must do the same everywhere
		for (boolean enabled : new boolean[] { true, false }) {
			assertThatIllegalStateException()
				.isThrownBy(() -> engineBuilder().tm1("legacy").tm1Engine().reuse(true).build(enabled))
				.withMessageContaining("An environment with a TM1 v12 engine can't be reused");
		}
	}

	@Test
	void fromConfigAddsTheEngineWithItsDatabasesAndUsers() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("tm1-engine.yaml")).build();
		Path ibm = fixture("ibm").toAbsolutePath().normalize();
		var engine = env.tm1Engine();
		engine.configure();
		// Sales and sales are two databases to TM1 v12, and each is in its instance
		assertThat(engine.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning,tm1/Sales,other/sales")
			// A user's password isn't TM1 v12's to keep
			.containsEntry("TM1_USERS", "analyst:Analysts:Planners,viewer,boss:ADMIN,planner:Planners");
		assertThat(env.tm1Database("Sales").getInstance()).isEqualTo("tm1");
		assertThat(engine.database("other", "sales").getName()).isEqualTo("sales");
		// ibm is every part's source; images are what they run
		assertThat(engine.getSource()).isEqualTo(new Tm1EngineSource.Bundle(ibm, null));
		assertThat(image(engine)).isEqualTo("registry.example.com/tm1-engine:local");
		assertThat(env.tm1("legacy").getSource()).isEqualTo(new Tm1Source.Bundle(ibm, null));
	}

	@Test
	void fromConfigRunsAnEngineAlone() {
		var env = PlanningAnalyticsEnvironment
			.fromConfig(Path.of(System.getProperty("tm1.containerConformance.scenarios")).resolve("engine.yaml"))
			.tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
			.build();
		assertThat(env.tm1Servers()).isEmpty();
		env.tm1Engine().configure();
		assertThat(env.tm1Engine().getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning,tm1/sales")
			.containsEntry("TM1_USERS", "analyst:Analysts");
	}

	@Test
	void fromConfigTakesOidcTrueAsOn() {
		var env = PlanningAnalyticsEnvironment.fromConfig(fixture("oidc-true.yaml")).build();
		assertThat(env.keycloak()).isNotNull();
		var planning = env.tm1("planning");
		planning.configure();
		assertThat(planning.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "OIDC");
	}

	private PlanningAnalyticsEnvironment.Builder engineBuilder() {
		return builder().tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"));
	}

	@Test
	void fromConfigRefusesWhatItCannotRun() throws IOException {
		List<Path> invalid = new ArrayList<>();
		try (var files = Files.list(fixture("invalid"))) {
			invalid.addAll(files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList());
		}
		assertThat(invalid).hasSizeGreaterThan(40);
		for (Path file : invalid) {
			String first = Files.readAllLines(file).get(0);
			assertThat(first).as(file.toString()).startsWith("# error: ");
			assertThatIllegalArgumentException().as(file.toString())
				.isThrownBy(() -> PlanningAnalyticsEnvironment.fromConfig(file))
				.withMessageStartingWith(file.getFileName() + ": ")
				.withMessageContaining(first.substring("# error: ".length()));
		}
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PlanningAnalyticsEnvironment.fromConfig(fixture("missing.yaml")))
			.withMessageContaining("not found");
	}

}
