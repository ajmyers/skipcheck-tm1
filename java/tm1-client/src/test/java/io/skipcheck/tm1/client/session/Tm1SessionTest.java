// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1AuthenticationException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1ForbiddenException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import io.skipcheck.tm1.client.error.Tm1TransportException;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

class Tm1SessionTest {

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"),
			ODataQuery.none().select("Name"), JsonNode.class);

	private final FakeTm1 tm1 = new FakeTm1().on("GET Server?$select=Name",
			r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"tm1\"}")));

	@Test
	void givesUpOnAnAuthenticatorThatNeverAnswers() {
		Tm1Authenticator never = new Tm1Authenticator() {
			@Override
			public Mono<String> authorization() {
				return Mono.never();
			}

			@Override
			public String describe() {
				return "Never";
			}
		};
		Tm1Session session = tm1.sessionWithOwnCredentials(Tm1Session.builder()
			.authenticator(never)
			.timeouts(Tm1Timeouts.builder().login(Duration.ofMillis(100)).build()));

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1TransportException.class,
					t -> assertThat(t.isRetryable()).as("nothing was sent to TM1").isTrue()))
			.verify(Duration.ofSeconds(5));
		StepVerifier.create(session.close()).expectComplete().verify(Duration.ofSeconds(5));
		assertThat(tm1.requests(FakeTm1.LOGIN)).isEmpty();
	}

	@Test
	void logsInOnceForConcurrentFirstRequestsAndSendsOnlyTheCookieAfterwards() {
		Sinks.One<ClientResponse> login = Sinks.one();
		tm1.on(FakeTm1.LOGIN, r -> login.asMono());
		Tm1Session session = tm1.session();

		Flux<JsonNode> three = Flux.merge(session.execute(SERVER), session.execute(SERVER), session.execute(SERVER));
		StepVerifier.create(three)
			.then(() -> login.tryEmitValue(FakeTm1.json(200, "{\"ID\":7}", "TM1SessionId=abc; Path=/api/; HttpOnly")))
			.expectNextCount(3)
			.verifyComplete();

		assertThat(tm1.requests(FakeTm1.LOGIN)).hasSize(1);
		assertThat(FakeTm1.authorization(tm1.requests(FakeTm1.LOGIN).get(0))).isEqualTo("Basic YWRtaW46YXBwbGU=");
		assertThat(tm1.requests("GET Server?$select=Name")).hasSize(3).allSatisfy(r -> {
			assertThat(FakeTm1.cookie(r)).isEqualTo("abc");
			assertThat(FakeTm1.authorization(r)).as("no credentials after login").isNull();
			assertThat(r.headers().getFirst("OData-Version")).isEqualTo("4.0");
			assertThat(r.headers().getFirst("OData-MaxVersion")).isEqualTo("4.0");
		});
		StepVerifier.create(session.sessionId()).expectNext(7L).verifyComplete();
	}

	@Test
	void findsTheSessionCookieByNameAmongOthers() {
		tm1.on(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.json(200, "{\"ID\":3}", "other=1; Path=/",
				"TM1SessionId=right; Path=/api/; HttpOnly", "more=2")));
		Tm1Session session = tm1.session();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
		assertThat(FakeTm1.cookie(tm1.requests("GET Server?$select=Name").get(0))).isEqualTo("right");
	}

	@Test
	void reportsRefusedCredentialsAndCanTryAgainOnTheNextRequest() {
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=anon; Path=/api/")),
				r -> Mono.just(FakeTm1.json(200, "{\"ID\":2}", "TM1SessionId=ok; Path=/api/")));
		// With the refused-login back-off off; Tm1SessionImpersonationTest covers the
		// back-off
		Tm1Session session = tm1
			.session(Tm1Session.builder().timeouts(Tm1Timeouts.builder().refusedLogin(Duration.ZERO).build()));

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class,
					a -> assertThat(a.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED)))
			.verify();
		assertThat(tm1.requests("GET Server?$select=Name")).as("nothing sent without a session").isEmpty();

		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
		assertThat(tm1.requests(FakeTm1.LOGIN)).hasSize(2);
	}

	@Test
	void mapsErrorResponsesThroughTheClassifier() {
		tm1.on("GET Cubes('Nope')", r -> Mono.just(FakeTm1.json(404,
				"{\"error\":{\"code\":\"278\",\"message\":\"'Nope' can not be found in collection of type 'Cube'.\"}}")));
		Tm1Session session = tm1.session();
		StepVerifier
			.create(session
				.execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none(), JsonNode.class)))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1NotFoundException.class, n -> {
				assertThat(n.status()).isEqualTo(404);
				assertThat(n.error().code()).isEqualTo("278");
				assertThat(n.url()).isEqualTo(FakeTm1.ROOT + "Cubes('Nope')");
			}))
			.verify();
	}

	@Test
	void readsAPlainTextErrorBodyAsTheMessage() {
		tm1.on("GET Cubes('x')",
				r -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
					.header(HttpHeaders.CONTENT_TYPE, "text/plain")
					.body("Error: Bad Request\r\nSomething TM1 says in plain text.\r\n")
					.build()));
		StepVerifier
			.create(tm1.session()
				.execute(Tm1Request.get(ODataPath.of("Cubes").key("x"), ODataQuery.none(), JsonNode.class)))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1ApiException.class,
					a -> assertThat(a.error().message()).isEqualTo("Something TM1 says in plain text.")))
			.verify();
	}

	@Test
	void readsTextAndNoContentAndSendsJsonBodies() {
		tm1.on("GET Configuration/ProductVersion/$value", r -> Mono.just(FakeTm1.text("11.8.03500.6")));
		tm1.on("POST Cubes('Sales')/tm1.Lock", r -> Mono.just(FakeTm1.status(204)));
		Tm1Session session = tm1.session();

		StepVerifier
			.create(session.execute(Tm1Request.text(ODataPath.of("Configuration").nav("ProductVersion").value())))
			.expectNext("11.8.03500.6")
			.verifyComplete();
		StepVerifier
			.create(session
				.execute(Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Lock"), null, Void.class)))
			.verifyComplete();

		assertThat(
				tm1.requests("GET Configuration/ProductVersion/$value").get(0).headers().getFirst(HttpHeaders.ACCEPT))
			.startsWith("text/plain");
		assertThat(tm1.requests("POST Cubes('Sales')/tm1.Lock").get(0).headers().getContentType())
			.hasToString("application/json");
	}

	@Test
	void streamsCollectionsAcrossNextLinks() {
		tm1.on("GET Dimensions?$select=Name", r -> Mono.just(FakeTm1.json(200,
				"{\"@odata.context\":\"$metadata#Dimensions(Name)\",\"value\":[{\"Name\":\"a\"},{\"Name\":\"b\"}],\"@odata.nextLink\":\"Dimensions?$skiptoken=2\"}")));
		tm1.on("GET Dimensions?$skiptoken=2", r -> Mono.just(FakeTm1.json(200, "{\"value\":[{\"Name\":\"c\"}]}")));
		Tm1Session session = tm1.session();

		StepVerifier
			.create(session
				.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), JsonNode.class))
				.map(d -> d.get("Name").asString()))
			.expectNext("a", "b", "c")
			.verifyComplete();
	}

	@Test
	void refusesToStreamASingleResponse() {
		StepVerifier.create(tm1.session().stream(SERVER)).expectError(IllegalArgumentException.class).verify();
	}

	@Test
	void refusesToExecuteACollectionWithoutSendingIt() {
		Tm1Request<JsonNode> list = Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class);
		StepVerifier.create(tm1.session().execute(list)).expectError(IllegalArgumentException.class).verify();
		assertThat(tm1.requests()).isEmpty();
	}

	@Test
	void reportsABodyThatIsntJsonAsTheCallersMistakeNotATransportFailure() {
		Tm1Request<Void> bad = Tm1Request.post(ODataPath.of("Cubes"), new Unwritable("x"), Void.class);
		StepVerifier.create(tm1.session().execute(bad))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
				.isNotInstanceOf(Tm1TransportException.class))
			.verify();
	}

	@Test
	void passesOtherFailuresThroughUnwrapped() {
		tm1.on("GET Server?$select=Name", r -> Mono.error(new IllegalStateException("a bug, not the network")));
		StepVerifier.create(tm1.session().execute(SERVER)).expectError(IllegalStateException.class).verify();
	}

	@Test
	void mapsConnectFailuresAndOtherTransportFailures() {
		tm1.on("GET Server?$select=Name", r -> Mono.error(new WebClientRequestException(new ConnectException("refused"),
				HttpMethod.GET, URI.create(FakeTm1.ROOT + "Server"), new HttpHeaders())));
		tm1.on("POST Processes('p')/tm1.ExecuteWithReturn", r -> Mono.error(new IOException("Connection reset")));
		Tm1Session session = tm1.session();

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1TransportException.class, t -> {
				assertThat(t.phase()).isEqualTo(Tm1TransportException.Phase.CONNECT);
				assertThat(t.mayHaveApplied()).isFalse();
			}))
			.verify();
		StepVerifier
			.create(session.execute(Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"),
					null, Void.class)))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1TransportException.class, t -> {
				assertThat(t.phase()).isEqualTo(Tm1TransportException.Phase.RESPONSE);
				assertThat(t.mayHaveApplied()).isTrue();
				assertThat(t.isRetryable()).as("a POST that may have run").isFalse();
			}))
			.verify();
	}

	@Test
	void sendsNothingOnCloseIfItNeverLoggedIn() {
		Tm1Session session = tm1.session();
		StepVerifier.create(session.close()).verifyComplete();
		assertThat(tm1.requests()).isEmpty();
		assertThat(session.isClosed()).isTrue();
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1SessionClosedException.class).verify();
		assertThat(tm1.requests()).isEmpty();
	}

	@Test
	void closesTheSessionOnceWithAJsonBodyAndRefusesRequestsAfterwards() {
		Tm1Session session = tm1.session();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();

		StepVerifier.create(Mono.when(session.close(), session.close())).verifyComplete();
		StepVerifier.create(session.close()).verifyComplete();

		List<ClientRequest> closes = tm1.requests(FakeTm1.CLOSE);
		assertThat(closes).hasSize(1);
		assertThat(FakeTm1.cookie(closes.get(0))).isEqualTo("c1");
		assertThat(closes.get(0).headers().getContentType()).hasToString("application/json");
		assertThat(closes.get(0).headers().getFirst(HttpHeaders.CONNECTION)).as("not reused after a close")
			.isEqualTo("close");
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1SessionClosedException.class).verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).as("a closed session doesn't log in again").hasSize(1);
	}

	@Test
	void waitsForALoginInProgressAndClosesIt() {
		Sinks.One<ClientResponse> login = Sinks.one();
		tm1.on(FakeTm1.LOGIN, r -> login.asMono());
		Tm1Session session = tm1.session();

		Disposable first = session.execute(SERVER).subscribe(v -> {
		}, e -> {
		});
		StepVerifier.create(session.close())
			.then(() -> assertThat(tm1.requests(FakeTm1.CLOSE)).isEmpty())
			.then(() -> login.tryEmitValue(FakeTm1.json(200, "{\"ID\":5}", "TM1SessionId=late; Path=/api/")))
			.verifyComplete();
		first.dispose();

		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(1);
		assertThat(FakeTm1.cookie(tm1.requests(FakeTm1.CLOSE).get(0))).isEqualTo("late");
	}

	@Test
	void finishesALoginWhoseRequestWasCancelledSoCloseCanEndIt() {
		Sinks.One<ClientResponse> login = Sinks.one();
		tm1.on(FakeTm1.LOGIN, r -> login.asMono());
		Tm1Session session = tm1.session();

		session.execute(SERVER).subscribe().dispose();
		login.tryEmitValue(FakeTm1.json(200, "{\"ID\":6}", "TM1SessionId=orphan; Path=/api/"));
		StepVerifier.create(session.close()).verifyComplete();

		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(1);
		assertThat(FakeTm1.cookie(tm1.requests(FakeTm1.CLOSE).get(0))).isEqualTo("orphan");
	}

	@Test
	void treatsA401OnCloseAsAlreadyClosed() {
		tm1.on(FakeTm1.CLOSE, r -> Mono.just(FakeTm1.status(401)));
		Tm1Session session = tm1.session();
		StepVerifier.create(session.connect().then(session.close())).verifyComplete();
	}

	@Test
	void observesLoginAndClose() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		Tm1Session session = tm1.session(Tm1Session.builder().observationRegistry(registry));
		StepVerifier.create(session.connect().then(session.close())).verifyComplete();

		TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("tm1.session.login")
			.that()
			.hasLowCardinalityKeyValue("tm1.reason", "initial")
			.hasBeenStarted()
			.hasBeenStopped();
		TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("tm1.session.close")
			.that()
			.hasBeenStopped();
	}

	@Test
	void closesTm112SessionsAtTheInstanceLevel() {
		String dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
		String instClose = "POST http://tm1.test/tm1/api/v1/ActiveSession/tm1s.Close";
		FakeTm1 v12 = new FakeTm1()
			.on("GET " + dbRoot + "ActiveSession?$select=ID",
					r -> Mono.just(FakeTm1.json(200, "{\"ID\":9}", "TM1SessionId=s9; Path=/; HttpOnly")))
			.on("GET " + dbRoot + "Server?$select=Name", r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")))
			.on(instClose, r -> Mono.just(FakeTm1.status(204, "TM1SessionId=s9; Path=/; Max-Age=0")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.basicAuth("admin", "apple")
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
		StepVerifier.create(session.close()).verifyComplete();

		assertThat(v12.requests(instClose)).hasSize(1);
		assertThat(FakeTm1.cookie(v12.requests(instClose).get(0))).isEqualTo("s9");
		assertThat(v12.requests("POST " + dbRoot + "ActiveSession/tm1.Close")).isEmpty();
	}

	@Test
	void readsTm112AuthErrorDetailsOnLogin401() {
		tm1.on(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.json(401,
				"{\"error\":\"invalid_token\",\"error_details\":\"Unable to validate authorization header\"}")));
		Tm1Session session = tm1.session();

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class, a -> {
				assertThat(a.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
				assertThat(a.error()).isNotNull();
				assertThat(a.error().code()).isEqualTo("invalid_token");
				assertThat(a.error().message()).isEqualTo("Unable to validate authorization header");
			}))
			.verify();
	}

	@Test
	void logsInWithBearerTokenAndRefreshesOnExpiry() {
		String dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
		String login = "GET " + dbRoot + "ActiveSession?$select=ID";
		String server = "GET " + dbRoot + "Server?$select=Name";
		AtomicInteger issued = new AtomicInteger();
		FakeTm1 v12 = new FakeTm1()
			.onEach(login, r -> Mono.just(FakeTm1.json(200, "{\"ID\":10}", "TM1SessionId=s10; Path=/; HttpOnly")),
					r -> Mono.just(FakeTm1.json(200, "{\"ID\":11}", "TM1SessionId=s11; Path=/; HttpOnly")))
			.onEach(server, r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")),
					r -> Mono.just(FakeTm1.status(401, "TM1SessionId=s10; Path=/; Max-Age=0")),
					r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.bearer(() -> Mono.fromSupplier(() -> "jwt-" + issued.incrementAndGet())))
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();

		List<ClientRequest> logins = v12.requests(login);
		assertThat(logins).hasSize(2);
		assertThat(FakeTm1.authorization(logins.get(0))).isEqualTo("Bearer jwt-1");
		assertThat(FakeTm1.authorization(logins.get(1))).isEqualTo("Bearer jwt-2");
		assertThat(FakeTm1.cookie(logins.get(1))).as("expired Max-Age=0 cookie is not sent back").isNull();
		List<ClientRequest> reads = v12.requests(server);
		assertThat(reads).hasSize(3);
		assertThat(FakeTm1.cookie(reads.get(0))).isEqualTo("s10");
		assertThat(FakeTm1.authorization(reads.get(0))).isNull();
		assertThat(FakeTm1.cookie(reads.get(2))).isEqualTo("s11");
	}

	@Test
	void logsInWithApplicationCredentialsAndSetsSessionContext() {
		String dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
		String authUrl = "POST http://tm1.test/tm1/auth/v1/session";
		String activeUrl = "GET " + dbRoot + "ActiveSession?$select=ID";
		String serverUrl = "GET " + dbRoot + "Server?$select=Name";
		String closeUrl = "POST http://tm1.test/tm1/api/v1/ActiveSession/tm1s.Close";
		FakeTm1 v12 = new FakeTm1()
			.on(authUrl, r -> Mono.just(FakeTm1.status(201, "TM1SessionId=app1; Path=/; HttpOnly")))
			.on(activeUrl, r -> Mono.just(FakeTm1.json(200, "{\"ID\":42}")))
			.on(serverUrl, r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")))
			.on(closeUrl, r -> Mono.just(FakeTm1.status(204, "TM1SessionId=app1; Path=/; Max-Age=0")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "analyst"))
			.sessionContext("reporting-service")
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER))
			.assertNext(n -> assertThat(n.path("Name").asString()).isEqualTo("sales"))
			.verifyComplete();
		StepVerifier.create(session.sessionId()).expectNext(42L).verifyComplete();
		StepVerifier.create(session.close()).verifyComplete();

		ClientRequest authReq = v12.requests(authUrl).get(0);
		assertThat(FakeTm1.authorization(authReq))
			.isEqualTo("Basic " + Base64.getEncoder().encodeToString("my-app:s3cr3t".getBytes(StandardCharsets.UTF_8)));
		assertThat(authReq.headers().getFirst(Connection.SESSION_CONTEXT)).isNull();
		assertThat(FakeTm1.bodyText(authReq)).isEqualTo("{\"User\":\"analyst\"}");

		ClientRequest activeReq = v12.requests(activeUrl).get(0);
		assertThat(FakeTm1.cookie(activeReq)).isEqualTo("app1");
		assertThat(FakeTm1.authorization(activeReq)).isNull();
		assertThat(activeReq.headers().getFirst(Connection.SESSION_CONTEXT)).isEqualTo("reporting-service");

		assertThat(v12.requests(closeUrl)).hasSize(1);
		assertThat(FakeTm1.cookie(v12.requests(closeUrl).get(0))).isEqualTo("app1");
	}

	@Test
	void failsFastWhenApplicationUserIsForbiddenInDatabase() {
		String dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
		FakeTm1 v12 = new FakeTm1()
			.on("POST http://tm1.test/tm1/auth/v1/session",
					r -> Mono.just(FakeTm1.status(201, "TM1SessionId=app2; Path=/; HttpOnly")))
			.on("GET " + dbRoot + "ActiveSession?$select=ID",
					r -> Mono.just(FakeTm1.json(403, "{\"error\":{\"code\":\"\",\"message\":\"Forbidden\"}}")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "ghost"))
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER)).expectError(Tm1ForbiddenException.class).verify();
		assertThat(v12.requests("GET " + dbRoot + "Server?$select=Name")).isEmpty();
	}

	@Test
	void reportsRefusedApplicationCredentials() {
		FakeTm1 v12 = new FakeTm1().on("POST http://tm1.test/tm1/auth/v1/session", r -> Mono.just(
				FakeTm1.json(401, "{\"error\":\"invalid_client\",\"error_details\":\"Invalid client credentials\"}")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "wrong", "analyst"))
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class, a -> {
				assertThat(a.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
				assertThat(a.error()).isNotNull();
				assertThat(a.error().code()).isEqualTo("invalid_client");
				assertThat(a.error().message()).isEqualTo("Invalid client credentials");
			}))
			.verify();
	}

	@Test
	void refusesApplicationAuthWithoutTm112DialectOrWithImpersonate() {
		assertThatIllegalStateException().isThrownBy(() -> Tm1Session.builder()
			.address("http://tm1.test:8010")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "analyst"))
			.build());
		assertThatIllegalStateException().isThrownBy(() -> Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "analyst"))
			.impersonate("someone-else")
			.build());
	}

	@Test
	void relogsInWithApplicationCredentialsAfterMaxAgeZero401() {
		String dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
		String authUrl = "POST http://tm1.test/tm1/auth/v1/session";
		String activeUrl = "GET " + dbRoot + "ActiveSession?$select=ID";
		String serverUrl = "GET " + dbRoot + "Server?$select=Name";
		FakeTm1 v12 = new FakeTm1()
			.onEach(authUrl, r -> Mono.just(FakeTm1.status(201, "TM1SessionId=a1; Path=/; HttpOnly")),
					r -> Mono.just(FakeTm1.status(201, "TM1SessionId=a2; Path=/; HttpOnly")))
			.onEach(activeUrl, r -> Mono.just(FakeTm1.json(200, "{\"ID\":1}")),
					r -> Mono.just(FakeTm1.json(200, "{\"ID\":2}")))
			.onEach(serverUrl, r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")),
					r -> Mono.just(FakeTm1.status(401, "TM1SessionId=a1; Path=/; Max-Age=0")),
					r -> Mono.just(FakeTm1.json(200, "{\"Name\":\"sales\"}")));

		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "analyst"))
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();

		assertThat(v12.requests(authUrl)).hasSize(2);
		assertThat(FakeTm1.cookie(v12.requests(authUrl).get(1))).isNull();
		assertThat(v12.requests(activeUrl)).hasSize(2);
		assertThat(FakeTm1.cookie(v12.requests(activeUrl).get(1))).isEqualTo("a2");
		assertThat(FakeTm1.cookie(v12.requests(serverUrl).get(2))).isEqualTo("a2");
		StepVerifier.create(session.sessionId()).expectNext(2L).verifyComplete();
	}

	@Test
	void reportsProtocolErrorWhenApplicationLoginSetsNoCookie() {
		FakeTm1 v12 = new FakeTm1().on("POST http://tm1.test/tm1/auth/v1/session", r -> Mono.just(FakeTm1.status(201)));
		Tm1Session session = Tm1Session.builder()
			.dialect(Tm1Dialect.v12("sales"))
			.address("http://tm1.test")
			.authenticator(Tm1Authenticator.application("my-app", "s3cr3t", "analyst"))
			.webClient(WebClient.builder().exchangeFunction(v12))
			.build();

		StepVerifier.create(session.execute(SERVER)).expectError(Tm1ProtocolException.class).verify();
	}

	/** A body Jackson can't write: reading its property fails. */
	record Unwritable(String name) {
		@Override
		public String name() {
			throw new UnsupportedOperationException("not readable");
		}
	}

}
