// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1AuthenticationException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1ForbiddenException;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.User;
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
 * OAuth 2.0 bearer tokens from Keycloak, including real error classification and refusal
 * caching.
 */
@RequiresTm1Engine
class Tm1EngineAuthTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

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

	/** A real JWT signed by Keycloak's master realm, not the realm TM1 trusts. */
	private static String masterRealmToken() {
		try {
			HttpRequest request = HttpRequest
				.newBuilder(URI.create(keycloak.getUrl() + "/realms/master/protocol/openid-connect/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers
					.ofString("grant_type=password&client_id=admin-cli&username=admin&password=admin"))
				.timeout(WAIT)
				.build();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			Matcher matcher = ACCESS_TOKEN.matcher(response.body());
			assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
			assertThat(matcher.find()).as(response.body()).isTrue();
			return matcher.group(1);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void logsOnThroughApplicationCredentials() {
		Tm1Login login = database.login("analyst");
		Tm1Client analyst = client(
				Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), login.getUser()));
		try {
			User user = analyst.users().activeUser(ODataQuery.none().expand("Groups")).block(WAIT);
			assertThat(user.name()).isEqualToIgnoringCase("analyst");
			assertThat(user.groups()).extracting(Group::name).contains("Analysts");
		}
		finally {
			analyst.close().block(WAIT);
		}
	}

	@Test
	void refusesWrongApplicationSecretAndUnknownUser() {
		Tm1Login login = database.login("analyst");
		Tm1Client wrongSecret = client(
				Tm1Authenticator.application(login.getClientId(), "wrong-secret", login.getUser()));
		try {
			assertThatThrownBy(() -> wrongSecret.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.satisfies(e -> {
					Tm1ApiException api = (Tm1ApiException) e;
					assertThat(api.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
					assertThat(api.error()).isNotNull();
					assertThat(api.error().code()).isEqualTo("invalid_client");
				});
			assertThatThrownBy(() -> wrongSecret.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
		}
		finally {
			wrongSecret.close().onErrorComplete().block(WAIT);
		}

		// The gateway creates a session for the unknown user; the database refuses it
		// (403)
		Tm1Client unknownUser = client(
				Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), "nobody_here"));
		try {
			assertThatThrownBy(() -> unknownUser.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1ForbiddenException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.FORBIDDEN);
		}
		finally {
			unknownUser.close().onErrorComplete().block(WAIT);
		}
	}

	@Test
	void logsOnWithABearerToken() {
		Tm1Login oauth2 = database.oauth2Login("analyst");
		Tm1Client supplied = client(Tm1Authenticator.bearer(() -> Mono.fromCallable(oauth2::token)));
		try {
			User user = supplied.users().activeUser(ODataQuery.none().expand("Groups")).block(WAIT);
			assertThat(user.name()).isEqualToIgnoringCase("analyst");
			assertThat(user.groups()).extracting(Group::name).contains("Analysts");
		}
		finally {
			supplied.close().block(WAIT);
		}

		Tm1Client fixed = client(Tm1Authenticator.bearer(oauth2.token()));
		try {
			assertThat(fixed.users().activeUser(ODataQuery.none()).block(WAIT).name()).isEqualToIgnoringCase("analyst");
		}
		finally {
			fixed.close().block(WAIT);
		}
	}

	@Test
	void refusesInvalidAndWrongRealmBearerTokensOnceWithoutRetrying() {
		AtomicInteger asked = new AtomicInteger();
		Tm1Client wrongRealm = client(Tm1Authenticator.bearer(() -> Mono.fromCallable(() -> {
			asked.incrementAndGet();
			return masterRealmToken();
		})));
		try {
			assertThatThrownBy(() -> wrongRealm.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
			assertThatThrownBy(() -> wrongRealm.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
			assertThat(asked).as("supplier asked once: a refusal isn't retried at once").hasValue(1);
		}
		finally {
			wrongRealm.close().onErrorComplete().block(WAIT);
		}

		Tm1Client malformed = client(Tm1Authenticator.bearer("not.a.token"));
		try {
			assertThatThrownBy(() -> malformed.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
		}
		finally {
			malformed.close().onErrorComplete().block(WAIT);
		}
	}

}
