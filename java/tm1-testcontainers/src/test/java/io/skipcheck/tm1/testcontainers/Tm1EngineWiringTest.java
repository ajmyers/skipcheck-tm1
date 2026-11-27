// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import tools.jackson.databind.json.JsonMapper;

/** Wiring only: nothing here starts a container. */
class Tm1EngineWiringTest {

	private static final Tm1EngineSource SOURCE = new Tm1EngineSource.PrivateImage("tm1-engine:test");

	@TempDir
	Path temp;

	@Test
	void hasOneDatabaseUnlessSomeAreNamed() {
		var engine = new Tm1EngineContainer(SOURCE);
		engine.configure();
		assertThat(engine.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/tm1").doesNotContainKey("TM1_USERS");
		assertThat(engine.database("tm1").getInstance()).isEqualTo("tm1");

		var named = new Tm1EngineContainer(SOURCE).withDatabase("planning").withDatabase("other", "sales");
		named.configure();
		assertThat(named.getEnvMap()).containsEntry("TM1_DATABASES", "tm1/planning,other/sales");
		assertThat(named.database("other", "sales").getName()).isEqualTo("sales");
		assertThatIllegalArgumentException().isThrownBy(() -> named.database("tm1"))
			.withMessageContaining("No database tm1/tm1 was added");
	}

	@Test
	void passesTheUsersAndTheirGroupsToTheImage() {
		var engine = new Tm1EngineContainer(SOURCE).withUser("analyst", "Analysts", "Planners").withUser("viewer");
		engine.configure();
		assertThat(engine.getEnvMap()).containsEntry("TM1_USERS", "analyst:Analysts:Planners,viewer");
	}

	@Test
	void refusesNamesTheImageCantTake() {
		var engine = new Tm1EngineContainer(SOURCE);
		// TM1 wants at least three characters of a database's name; an instance's may be
		// shorter
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("ab"));
		assertThat(new Tm1EngineContainer(SOURCE).withDatabase("qa", "sales").configured()).containsExactly("qa/sales");
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("two words"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withDatabase("a/b", "sales"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("two words"));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("analyst", "a:b"));
		engine.withUser("analyst");
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withUser("analyst", "Again"))
			.withMessageContaining("already");
	}

	@Test
	void runsItsDatastoreOnItsOwnNetwork() {
		var engine = new Tm1EngineContainer(SOURCE);
		engine.configure();
		var datastore = engine.getDatastore();
		assertThat(image(datastore)).isEqualTo("mongo:8.0");
		assertThat(engine.getNetwork()).isNotNull().isSameAs(datastore.getNetwork());
		assertThat(datastore.getNetworkAliases()).contains(Tm1EngineContainer.DATASTORE_ALIAS);
		assertThat(engine.getDependencies()).contains(datastore);
		assertThat(engine.getEnvMap().get("TM1_DATASTORE_URL")).startsWith("mongodb://")
			.endsWith("@tm1-datastore:27017");
		// Both are this library's, like every container it starts
		assertThat(engine.getLabels()).containsEntry("io.skipcheck.tm1.testcontainers", "java");
		assertThat(datastore.getLabels()).containsEntry("io.skipcheck.tm1.testcontainers", "java");
	}

	@Test
	void takesOAuth2TokensFromAKeycloakOnItsNetwork() {
		var keycloak = new KeycloakContainer();
		var engine = new Tm1EngineContainer(SOURCE).withOAuth2(keycloak);
		// Keycloak's address on the network is what its tokens name, and what TM1 can
		// reach
		assertThat(engine.getEnvMap()).containsEntry("TM1_OAUTH2_ISSUER_URL", "http://keycloak:8080/realms/tm1")
			.containsEntry("TM1_OAUTH2_AUDIENCE", "tm1")
			.containsEntry("TM1_OAUTH2_ID_CLAIMS", "preferred_username");
		assertThat(keycloak.getNetwork()).isNotNull()
			.isSameAs(engine.getNetwork())
			.isSameAs(engine.getDatastore().getNetwork());
		assertThat(engine.getDependencies()).contains(keycloak, engine.getDatastore());
		assertThat(engine.getKeycloak()).isSameAs(keycloak);
		assertThat(new Tm1EngineContainer(SOURCE).getKeycloak()).isNull();

		// Moved off the Keycloak's network, TM1 couldn't reach its issuer
		assertThatIllegalStateException().isThrownBy(() -> engine.withNetwork(Network.newNetwork()))
			.withMessageContaining("set the network before withOAuth2");
		assertThat(engine.withNetwork(keycloak.getNetwork())).isSameAs(engine);

		// A Keycloak that has a network already is joined there, the datastore too
		Network network = Network.newNetwork();
		var placed = new KeycloakContainer().withNetwork(network);
		var joining = new Tm1EngineContainer(SOURCE).withOAuth2(placed);
		assertThat(joining.getNetwork()).isSameAs(network).isSameAs(joining.getDatastore().getNetwork());
	}

	@Test
	void keycloakImportsTheRealmAsItIsWhenKeycloakStarts() {
		var keycloak = new KeycloakContainer().withCommand("start-dev", "--features=token-exchange");
		var engine = new Tm1EngineContainer(SOURCE).withOAuth2(keycloak);
		// Added after withOAuth2, and still in the realm: it's asked for at Keycloak's
		// start
		engine.withUser("analyst").withLogin("analyst", "analystpw");
		keycloak.configure();
		// The import is added to the command it has: a caller's own options stay
		assertThat(keycloak.getCommandParts()).containsExactly("start-dev", "--features=token-exchange",
				"--import-realm");
		assertThat(keycloak.realmFiles()).containsOnlyKeys("/opt/keycloak/data/import/tm1-realm.json");
		assertThat(keycloak.realmFiles().values().iterator().next()).isEqualTo(engine.realm()).contains("\"analyst\"");
		// Without a realm, Keycloak starts as it was
		var plain = new KeycloakContainer();
		plain.configure();
		assertThat(plain.getCommandParts()).containsExactly("start-dev");
	}

	@Test
	void anEngineHasOneKeycloakOfItsOwnThatItStarts() {
		var keycloak = new KeycloakContainer();
		var engine = new Tm1EngineContainer(SOURCE).withOAuth2(keycloak);
		// The same again is nothing new
		assertThat(engine.withOAuth2(keycloak).getDependencies()).containsOnlyOnce(keycloak);
		// A second would start beside the first, under the same name on the network
		assertThatIllegalStateException().isThrownBy(() -> engine.withOAuth2(new KeycloakContainer()))
			.withMessageContaining("already takes tokens from another Keycloak");
		// And another engine's realm would replace this one's
		assertThatIllegalStateException().isThrownBy(() -> new Tm1EngineContainer(SOURCE).withOAuth2(keycloak))
			.withMessageContaining("each engine needs a Keycloak of its own");
		// An engine taken off its network gets one again, and the Keycloak with it
		var off = new Tm1EngineContainer(SOURCE).withNetwork(null).withOAuth2(new KeycloakContainer());
		assertThat(off.getNetwork()).isNotNull().isSameAs(off.getKeycloak().getNetwork());
		// A Keycloak moved since is found when the engine is configured, not by TM1
		keycloak.withNetwork(Network.newNetwork());
		assertThatIllegalStateException().isThrownBy(engine::configure)
			.withMessageContaining("set the network before withOAuth2");
	}

	@Test
	void givesKeycloakARealmWithTheUsersWhoHaveAPassword() throws Exception {
		var keycloak = new KeycloakContainer();
		var engine = new Tm1EngineContainer(SOURCE).withOAuth2(keycloak)
			// After withOAuth2: the realm is asked for when Keycloak starts
			.withUser("analyst", "Analysts")
			.withUser("viewer")
			.withLogin("analyst", "an$pw")
			.withLogin("Admin", "adminpw");
		var realm = JsonMapper.shared().readTree(engine.realm());
		assertThat(realm.path("realm").asString()).isEqualTo("tm1");
		var client = realm.path("clients").get(0);
		assertThat(client.path("clientId").asString()).isEqualTo("tm1");
		assertThat(client.path("publicClient").asBoolean()).isTrue();
		assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isTrue();
		var mapper = client.path("protocolMappers").get(0);
		assertThat(mapper.path("protocolMapper").asString()).isEqualTo("oidc-audience-mapper");
		assertThat(mapper.path("config").path("included.custom.audience").asString()).isEqualTo("tm1");
		// viewer has no password, so nothing to log in to Keycloak with; admin is always
		// a user
		assertThat(realm.path("users")).hasSize(2);
		assertThat(realm.path("users").get(0).path("username").asString()).isEqualTo("analyst");
		assertThat(realm.path("users").get(0).path("credentials").get(0).path("value").asString()).isEqualTo("an$pw");
		assertThat(realm.path("users").get(0).path("email").asString()).isEqualTo("user1@example.com");
		assertThat(realm.path("users").get(1).path("username").asString()).isEqualTo("Admin");
		// TM1 gets names and groups only
		engine.configure();
		assertThat(engine.getEnvMap()).containsEntry("TM1_USERS", "analyst:Analysts,viewer");
	}

	@Test
	void refusesAnOAuth2LoginItCantGive() {
		var engine = new Tm1EngineContainer(SOURCE).withUser("analyst").withLogin("analyst", "pw");
		var database = engine.database("tm1");
		assertThatIllegalStateException().isThrownBy(() -> database.oauth2Login("analyst"))
			.withMessageContaining("give it a Keycloak with withOAuth2");
		engine.withOAuth2(new KeycloakContainer());
		assertThatIllegalArgumentException().isThrownBy(() -> database.oauth2Login("viewer"))
			.withMessageContaining("viewer has no password to log in with");
		// A name is one whatever its case: to log in as, and a second password replaces
		// the first
		assertThat(database.oauth2Login("ANALYST").getUser()).isEqualTo("analyst");
		engine.withLogin("Analyst", "second");
		assertThat(database.oauth2Login("analyst").getPassword()).isEqualTo("second");
		assertThat(JsonMapper.shared().readTree(engine.realm()).path("users")).hasSize(1);
		engine.withLogin("analyst", "pw");
		assertThat(database.oauth2Login("analyst").isOAuth2()).isTrue();
		assertThat(database.oauth2Login("analyst").getPassword()).isEqualTo("pw");
		assertThat(database.oauth2Login("analyst").getClientId()).isEqualTo("tm1");
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withLogin("analyst", ""));
		assertThatIllegalArgumentException().isThrownBy(() -> engine.withLogin("two words", "pw"));
		// A password for someone who isn't a user is a mistake, found when the realm is
		// made
		var stray = new Tm1EngineContainer(SOURCE).withLogin("nobody", "pw");
		assertThatIllegalStateException().isThrownBy(stray::realm).withMessageContaining("isn't a user of the engine");
	}

	private static final Tm1Source TM1 = new Tm1Source.PrivateImage("tm1-server:test");

	private static CognosContainer cognos() {
		return new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
			.withUser("admin", "adminpw", "Admins")
			.withUser("analyst", "analystpw", "Analysts")
			.withUser("viewer", "viewerpw", "Viewers");
	}

	@Test
	void passesLoginsToATm1ServerOnItsNetwork() {
		var server = new Tm1ServerContainer(TM1);
		var engine = new Tm1EngineContainer(SOURCE).withPassThrough(server);
		// The server's REST API by the name it gets on the network; the image's defaults
		// are a TM1 11 server's, so the address is all it takes
		assertThat(engine.getEnvMap()).containsEntry("TM1_PASSTHROUGH_URL", "http://tm1-passthrough:8010/api/v1/");
		assertThat(engine.getEnvMap().keySet()).filteredOn(name -> name.startsWith("TM1_PASSTHROUGH_")).hasSize(1);
		assertThat(server.getNetworkAliases()).contains(Tm1EngineContainer.PASS_THROUGH_ALIAS);
		assertThat(server.getNetwork()).isNotNull()
			.isSameAs(engine.getNetwork())
			.isSameAs(engine.getDatastore().getNetwork());
		assertThat(engine.getDependencies()).contains(server, engine.getDatastore());
		assertThat(engine.getPassThrough()).isSameAs(server);
		assertThat(new Tm1EngineContainer(SOURCE).getPassThrough()).isNull();
		// The same server again is nothing new; another is refused
		assertThat(engine.withPassThrough(server)).isSameAs(engine);
		assertThatIllegalStateException().isThrownBy(() -> engine.withPassThrough(new Tm1ServerContainer(TM1)))
			.withMessageContaining("already passes logins to another TM1 server");

		// Moved off the server's network, TM1 couldn't reach it
		assertThatIllegalStateException().isThrownBy(() -> engine.withNetwork(Network.newNetwork()))
			.withMessageContaining("set the network before withPassThrough");
		assertThat(engine.withNetwork(server.getNetwork())).isSameAs(engine);

		// A server that has a network already (Cognos's, say) is joined there, the
		// datastore too
		Network network = Network.newNetwork();
		var placed = new Tm1ServerContainer(TM1).withNetwork(network);
		var joining = new Tm1EngineContainer(SOURCE).withPassThrough(placed);
		assertThat(joining.getNetwork()).isSameAs(network).isSameAs(joining.getDatastore().getNetwork());
	}

	@Test
	void takesTokensOrPassesLoginsOnNotBoth() {
		// With both set TM1 takes tokens and refuses a name and password: half would be
		// dead
		var tokens = new Tm1EngineContainer(SOURCE).withOAuth2(new KeycloakContainer());
		assertThatIllegalStateException().isThrownBy(() -> tokens.withPassThrough(new Tm1ServerContainer(TM1)))
			.withMessageContaining("TM1 takes tokens or passes logins on, not both");
		var passing = new Tm1EngineContainer(SOURCE).withPassThrough(new Tm1ServerContainer(TM1));
		assertThatIllegalStateException().isThrownBy(() -> passing.withOAuth2(new KeycloakContainer()))
			.withMessageContaining("TM1 takes tokens or passes logins on, not both");
	}

	@Test
	void namesItsUsersByTheirCamidsBehindAServerInCamSecurity() {
		var cognos = cognos();
		var server = new Tm1ServerContainer(TM1);
		var engine = new Tm1EngineContainer(SOURCE).withUser("Analyst", "Analysts").withPassThrough(server);
		// After withPassThrough: what the server is in is read when the engine starts
		server.withCamSecurity(cognos, "viewer", "admin");
		engine.configure();
		// The server answers a login with the user's CAMID, so the image is told the
		// namespace and makes the users under theirs. Listed users first, as Cognos
		// spells
		// them; then the server's administrators, who are administrators here too
		assertThat(engine.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "LDAP")
			.containsEntry("TM1_CAM_NAMESPACE_TYPE", "ldap")
			.containsEntry("TM1_USERS", "analyst:Analysts,viewer:ADMIN,admin:ADMIN");
		assertThat(engine.getNetwork()).isSameAs(cognos.getNetwork());

		// An administrator who is listed keeps its groups, and gets ADMIN once
		var listed = new Tm1EngineContainer(SOURCE).withUser("admin", "Ops", "admin")
			.withUser("viewer", "Readers")
			.withPassThrough(new Tm1ServerContainer(TM1).withCamSecurity(cognos(), CognosContainer.Namespace.OIDC,
					"viewer", "admin"));
		listed.configure();
		assertThat(listed.getEnvMap()).containsEntry("TM1_CAM_NAMESPACE", "OIDC")
			.containsEntry("TM1_CAM_NAMESPACE_TYPE", "oidc")
			.containsEntry("TM1_USERS", "admin:Ops:admin,viewer:Readers:ADMIN");

		// Only Cognos's users log in to a server in CAM security
		var stranger = new Tm1EngineContainer(SOURCE).withUser("planner")
			.withPassThrough(new Tm1ServerContainer(TM1).withCamSecurity(cognos(), "admin"));
		assertThatIllegalStateException().isThrownBy(stranger::configure)
			.withMessageContaining("planner isn't one of Cognos's test users");

		// A server whose tm1s.cfg takes it out of CAM security has users of its own: the
		// image applies those parameters last, whatever the case of their names
		var own = new Tm1EngineContainer(SOURCE).withUser("planner", "Planners")
			.withPassThrough(new Tm1ServerContainer(TM1).withCamSecurity(cognos(), "admin")
				.withEnv("TM1_CFG_integratedsecuritymode", "1"));
		own.configure();
		assertThat(own.getEnvMap()).doesNotContainKey("TM1_CAM_NAMESPACE")
			.containsEntry("TM1_USERS", "planner:Planners");
		// Without Cognos nothing changes for the users
		var plain = new Tm1EngineContainer(SOURCE).withUser("planner").withPassThrough(new Tm1ServerContainer(TM1));
		plain.configure();
		assertThat(plain.getEnvMap()).doesNotContainKey("TM1_CAM_NAMESPACE").containsEntry("TM1_USERS", "planner");
	}

	@Test
	void refusesAServerItCantAskOverPlainHttp() {
		// The engine asks over plain HTTP, and trusts no certificate of the server's
		for (String on : new String[] { "T", "true" }) {
			var engine = new Tm1EngineContainer(SOURCE)
				.withPassThrough(new Tm1ServerContainer(TM1).withEnv("TM1_CFG_usessl", on));
			assertThatIllegalStateException().isThrownBy(engine::configure).withMessageContaining("with UseSSL on");
		}
		var off = new Tm1EngineContainer(SOURCE)
			.withPassThrough(new Tm1ServerContainer(TM1).withEnv("TM1_CFG_UseSSL", " f "));
		off.configure();
		// A server that's running can't be given its name on the network any more
		var running = new Tm1ServerContainer(TM1) {
			@Override
			public boolean isRunning() {
				return true;
			}
		};
		assertThatIllegalStateException().isThrownBy(() -> new Tm1EngineContainer(SOURCE).withPassThrough(running))
			.withMessageContaining("give withPassThrough one that isn't started");
	}

	@Test
	void givesAPassThroughLoginTheHeaderTheServerTakes() {
		var none = new Tm1EngineContainer(SOURCE).database("tm1");
		assertThatIllegalStateException().isThrownBy(none::passThroughLogin)
			.withMessageContaining("give it a TM1 server with withPassThrough");
		assertThatIllegalStateException().isThrownBy(() -> none.passThroughLoginWith("Basic eA=="));

		// A server with users of its own: Basic, with its own login's password
		var server = new Tm1ServerContainer(TM1).withSeed(Tm1Seed.SDATA);
		var database = new Tm1EngineContainer(SOURCE).withPassThrough(server).database("tm1");
		Tm1Login own = database.passThroughLogin();
		assertThat(own.isPassThrough()).isTrue();
		assertThat(own.isOAuth2()).isFalse();
		assertThat(own.getUser()).isEqualTo("admin");
		assertThat(own.getPassword()).isEqualTo("apple");
		assertThat(own.getAuthorization()).isEqualTo(server.getAuthorization()).startsWith("Basic ");
		assertThat(own.getInstance()).isEqualTo("tm1");
		assertThat(own.getClientId()).isEmpty();
		assertThat(own.getClientSecret()).isEmpty();
		assertThat(own).hasToString("Tm1Login[admin in tm1]");
		assertThat(database.passThroughLogin("ADMIN").getAuthorization()).isEqualTo(own.getAuthorization());
		// Another user's password is the test's to give
		assertThatIllegalArgumentException().isThrownBy(() -> database.passThroughLogin("planner"))
			.withMessageContaining("passThroughLogin(user, password)");
		assertThat(database.passThroughLogin("planner", "p:w").getAuthorization()).isEqualTo("Basic cGxhbm5lcjpwOnc=");
		// It has no token and no session endpoint: the header is the login
		assertThatIllegalStateException().isThrownBy(own::token).withMessageContaining("has no token");
		assertThatIllegalStateException().isThrownBy(() -> own.session("Basic eA=="))
			.withMessageContaining("has no session to ask for");
		assertThatIllegalStateException()
			.isThrownBy(() -> own.authorizeWithToken(HttpRequest.newBuilder(URI.create("http://x/"))))
			.withMessageContaining("has no token");
		// A header of the caller's own, a CAM passport say, names nobody
		Tm1Login passport = database.passThroughLoginWith("CAMPassport abc");
		assertThat(passport.getAuthorization()).isEqualTo("CAMPassport abc");
		assertThat(passport.getUser()).isEmpty();
		assertThat(passport.getPassword()).isEmpty();
		assertThat(passport).hasToString("Tm1Login[a pass-through header in tm1]");

		// A server in CAM security: CAMNamespace, for any of Cognos's users, whose
		// passwords Cognos has. Its own login is its first administrator's
		var cam = new Tm1EngineContainer(SOURCE)
			.withPassThrough(new Tm1ServerContainer(TM1).withCamSecurity(cognos(), "viewer", "admin"))
			.database("tm1");
		assertThat(cam.passThroughLogin().getUser()).isEqualTo("viewer");
		assertThat(cam.passThroughLogin().getAuthorization())
			.isEqualTo(CognosContainer.camAuthorization("viewer", "viewerpw", "LDAP"));
		assertThat(cam.passThroughLogin("ANALYST").getUser()).isEqualTo("analyst");
		assertThat(cam.passThroughLogin("analyst").getPassword()).isEqualTo("analystpw");
		assertThat(cam.passThroughLogin("analyst", "other").getAuthorization())
			.isEqualTo(CognosContainer.camAuthorization("analyst", "other", "LDAP"));
		assertThatIllegalArgumentException().isThrownBy(() -> cam.passThroughLogin("nobody"));
		// Its users are Cognos's, under their CAMIDs: a login through the application by
		// a
		// short name would be refused everywhere, so it says what to do
		assertThatIllegalArgumentException().isThrownBy(() -> cam.login("analyst"))
			.withMessageContaining("Log in with passThroughLogin(\"analyst\")");
		// A login that isn't pass-through is no header
		var oauth2 = new Tm1EngineContainer(SOURCE).withLogin("admin", "pw").withOAuth2(new KeycloakContainer());
		assertThatIllegalStateException()
			.isThrownBy(() -> oauth2.database("tm1").oauth2Login("admin").getAuthorization())
			.withMessageContaining("Only a pass-through login is a header of its own");
	}

	@Test
	void itsDatastoreIsOnItsNetworkBeforeAnythingStarts() {
		// Not only once the engine starts or is configured: an environment starts what
		// the
		// engine depends on first, and TM1 couldn't reach a datastore left off the
		// network
		var engine = new Tm1EngineContainer(SOURCE);
		assertThat(engine.getNetwork()).isNotNull().isSameAs(engine.getDatastore().getNetwork());
		Network network = Network.newNetwork();
		engine.withNetwork(network);
		assertThat(engine.getDatastore().getNetwork()).isSameAs(network);
	}

	@Test
	void joinsANetworkItIsGiven() {
		Network network = Network.newNetwork();
		var engine = new Tm1EngineContainer(SOURCE).withNetwork(network);
		engine.configure();
		assertThat(engine.getNetwork()).isSameAs(network);
		assertThat(engine.getDatastore().getNetwork()).isSameAs(network);
	}

	@Test
	void mountsEachSourceWhereTheImageLooks() throws IOException {
		Path install = Files.createDirectories(temp.resolve("install/bin")).getParent();
		Files.createFile(install.resolve("bin/tm1sd.exe"));
		assertThat(binds(new Tm1EngineSource.InstallDir(install))).containsExactly(install + ":/opt/ibm/tm1-engine:ro");
		assertThat(binds(new Tm1EngineSource.InstallVolume("tm1-engine")))
			.containsExactly("tm1-engine:/opt/ibm/tm1-engine:rw");

		Files.createFile(temp.resolve("tm1-v12.6.7-standalone-linux.tgz"));
		var bundle = new Tm1EngineContainer(new Tm1EngineSource.Bundle(temp, "tm1-v12.6.7-standalone-linux.tgz"));
		assertThat(bundle.sourceBinds().stream().map(Object::toString)).containsExactly(temp + ":/ibm:ro",
				"tm1-engine-testcontainers-cache:/tm1/cache:rw");
		assertThat(bundle.getEnvMap()).containsEntry("TM1_ENGINE_BUNDLE", "tm1-v12.6.7-standalone-linux.tgz");

		var url = new Tm1EngineContainer(new Tm1EngineSource.BundleUrl(URI.create("https://example.com/p.tgz"), "abc"));
		assertThat(url.sourceBinds().stream().map(Object::toString))
			.containsExactly("tm1-engine-testcontainers-cache:/tm1/cache:rw");
		assertThat(url.getEnvMap()).containsEntry("TM1_ENGINE_BUNDLE_URL", "https://example.com/p.tgz")
			.containsEntry("TM1_ENGINE_BUNDLE_SHA256", "abc");

		assertThat(binds(SOURCE)).isEmpty();
		assertThat(image(new Tm1EngineContainer(SOURCE))).isEqualTo("tm1-engine:test");
	}

	@Test
	void aSubclassKeepsTheAnnotationsOptions() {
		// JUnit runs the extension for a subclass of an annotated class, so the options
		// have to come with it
		var inherited = Sub.class.getAnnotation(RequiresTm1Engine.class);
		assertThat(inherited).isNotNull();
		assertThat(inherited.shareDatabase()).isTrue();
		assertThat(inherited.minVersion()).isEqualTo("12.6");
	}

	@RequiresTm1Engine(shareDatabase = true, minVersion = "12.6")
	abstract static class Base {

	}

	static final class Sub extends Base {

	}

	@Test
	void namesAClassesDatabaseAfterTheClass() {
		assertThat(Tm1EngineExtension.databaseName("CubeTest", "k3f9")).isEqualTo("cubetest_k3f9");
		// A short suffix is padded, so the name is always long enough for TM1
		assertThat(Tm1EngineExtension.databaseName("T", "7")).isEqualTo("t_0007");
		assertThat(Tm1EngineExtension.databaseName("$$", "ab")).isEqualTo("test_00ab");
		assertThat(Tm1EngineExtension.databaseName("AVeryLongTestClassNameThatGoesOnAndOn", "zzzz"))
			.isEqualTo("averylongtestclassnameth_zzzz");
	}

	/** The image a container runs, without pulling it as getDockerImageName does. */
	private static String image(GenericContainer<?> container) {
		Matcher name = Pattern.compile("imageName=([^,)]+)").matcher(container.getImage().toString());
		assertThat(name.find()).as(container.getImage().toString()).isTrue();
		return name.group(1);
	}

	private static List<String> binds(Tm1EngineSource source) {
		return new Tm1EngineContainer(source).sourceBinds().stream().map(Object::toString).toList();
	}

}
