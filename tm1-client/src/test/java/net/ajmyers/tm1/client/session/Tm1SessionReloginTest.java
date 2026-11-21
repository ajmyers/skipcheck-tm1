package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.client.error.Tm1AuthenticationException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/** How {@link Tm1Session} handles a session that expires or is killed on the server. */
class Tm1SessionReloginTest {

	private static final String CREATE_KEY = "POST Dimensions";

	private static final Tm1Request<JsonNode> CREATE = Tm1Request.post(ODataPath.of("Dimensions"), Map.of("Name", "d"),
			JsonNode.class);

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(),
			JsonNode.class);

	private final FakeTm1 tm1 = new FakeTm1();

	@Test
	void logsInAgainWithTheCookieFromThe401AndReplaysThePostOnce() {
		tm1.onEach(CREATE_KEY, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=fresh; Path=/api/; HttpOnly")),
				r -> Mono.just(FakeTm1.json(201, "{\"Name\":\"d\"}")));
		Tm1Session session = tm1.session();

		StepVerifier.create(session.connect().then(session.execute(CREATE))).expectNextCount(1).verifyComplete();

		List<ClientRequest> logins = tm1.requests(FakeTm1.LOGIN);
		assertThat(logins).hasSize(2);
		assertThat(FakeTm1.cookie(logins.get(1))).as("the re-login logs in the session TM1 opened for the 401")
			.isEqualTo("fresh");
		assertThat(FakeTm1.authorization(logins.get(1))).isNotNull();
		List<ClientRequest> creates = tm1.requests(CREATE_KEY);
		assertThat(creates).hasSize(2);
		assertThat(FakeTm1.cookie(creates.get(0))).isEqualTo("c1");
		assertThat(FakeTm1.cookie(creates.get(1))).isEqualTo("fresh");
		assertThat(FakeTm1.authorization(creates.get(1))).isNull();
		StepVerifier.create(session.sessionId()).expectNext(2L).verifyComplete();
	}

	@Test
	void sharesOneReloginBetweenRequestsThatSawTheSameExpiry() {
		Sinks.One<ClientResponse> relogin = Sinks.one();
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.json(200, "{\"ID\":1}", "TM1SessionId=old; Path=/api/")),
				r -> relogin.asMono());
		tm1.on("GET Server",
				r -> "old".equals(FakeTm1.cookie(r))
						? Mono.just(FakeTm1.status(401, "TM1SessionId=anon" + System.nanoTime()))
						: Mono.just(FakeTm1.json(200, "{}")));
		Tm1Session session = tm1.session();
		session.connect().block();

		StepVerifier.create(Flux.merge(session.execute(SERVER), session.execute(SERVER), session.execute(SERVER)))
			.then(() -> relogin.tryEmitValue(FakeTm1.json(200, "{\"ID\":2}", "TM1SessionId=new; Path=/api/")))
			.expectNextCount(3)
			.verifyComplete();
		assertThat(tm1.requests(FakeTm1.LOGIN)).hasSize(2);
	}

	@Test
	void givesUpIfTheReplayIsRefusedTooWithoutLoggingInAgain() {
		tm1.on("GET Server", r -> Mono.just(FakeTm1.status(401, "TM1SessionId=anon; Path=/api/")));
		Tm1Session session = tm1.session();

		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class,
					a -> assertThat(a.kind()).isEqualTo(Tm1ErrorKind.UNAUTHENTICATED)))
			.verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).hasSize(2);
		assertThat(tm1.requests("GET Server")).hasSize(2);
	}

	@Test
	void reportsRefusedCredentialsOnReloginWithoutReplaying() {
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.json(200, "{\"ID\":1}", "TM1SessionId=c1; Path=/api/")),
				r -> Mono.just(FakeTm1.status(401)));
		tm1.on(CREATE_KEY, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=anon; Path=/api/")));
		Tm1Session session = tm1.session();

		StepVerifier.create(session.connect().then(session.execute(CREATE)))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class,
					a -> assertThat(a.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED)))
			.verify();
		assertThat(tm1.requests(CREATE_KEY)).as("the POST is sent once").hasSize(1);
	}

	@Test
	void closesASessionLoggedInAgainRatherThanTheExpiredOne() {
		tm1.onEach(CREATE_KEY, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=fresh; Path=/api/")),
				r -> Mono.just(FakeTm1.json(201, "{}")));
		Tm1Session session = tm1.session();
		StepVerifier.create(session.connect().then(session.execute(CREATE)).then(session.close())).verifyComplete();

		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(1);
		assertThat(FakeTm1.cookie(tm1.requests(FakeTm1.CLOSE).get(0))).isEqualTo("fresh");
	}

	@Test
	void doesntLogInAgainOnceClosed() {
		Sinks.One<ClientResponse> response = Sinks.one();
		tm1.on("GET Server", r -> response.asMono());
		Tm1Session session = tm1.session();
		session.connect().block();

		StepVerifier.create(session.execute(SERVER))
			.then(() -> session.close().block())
			.then(() -> response.tryEmitValue(FakeTm1.status(401, "TM1SessionId=anon; Path=/api/")))
			.expectError(Tm1SessionClosedException.class)
			.verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).hasSize(1);
	}

	@Test
	void asksTheAuthenticatorForFreshCredentialsOnEachLogin() {
		AtomicInteger issued = new AtomicInteger();
		tm1.onEach(CREATE_KEY, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=fresh; Path=/api/")),
				r -> Mono.just(FakeTm1.json(201, "{}")));
		Tm1Session session = tm1.sessionWithOwnCredentials(Tm1Session.builder()
			.authenticator(Tm1Authenticator
				.camPassport(() -> Mono.fromSupplier(() -> "passport" + issued.incrementAndGet()))));
		StepVerifier.create(session.connect().then(session.execute(CREATE))).expectNextCount(1).verifyComplete();

		List<ClientRequest> logins = tm1.requests(FakeTm1.LOGIN);
		assertThat(FakeTm1.authorization(logins.get(0))).isEqualTo("CAMPassport passport1");
		assertThat(FakeTm1.authorization(logins.get(1))).isEqualTo("CAMPassport passport2");
	}

	@Test
	void passesAnAuthenticatorFailureThroughWithoutSendingALogin() {
		IllegalStateException noPassport = new IllegalStateException("Cognos is down");
		Tm1Session session = tm1.sessionWithOwnCredentials(
				Tm1Session.builder().authenticator(Tm1Authenticator.camPassport(() -> Mono.error(noPassport))));
		StepVerifier.create(session.connect()).expectErrorSatisfies(e -> assertThat(e).isSameAs(noPassport)).verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).isEmpty();
	}

	@Test
	void observesTheReloginWithItsReason() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		tm1.onEach(CREATE_KEY, r -> Mono.just(FakeTm1.status(401, "TM1SessionId=fresh; Path=/api/")),
				r -> Mono.just(FakeTm1.json(201, "{}")));
		Tm1Session session = tm1.session(Tm1Session.builder().observationRegistry(registry));
		StepVerifier.create(session.connect().then(session.execute(CREATE))).expectNextCount(1).verifyComplete();

		TestObservationRegistryAssert.assertThat(registry)
			.hasNumberOfObservationsWithNameEqualTo("tm1.session.login", 2)
			.hasAnObservationWithAKeyValue("tm1.reason", "relogin");
	}

}
