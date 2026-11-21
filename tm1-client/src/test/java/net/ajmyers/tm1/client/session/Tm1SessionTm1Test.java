package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * {@link Tm1Session} against a real TM1: logging in, requests, and leaving no session
 * behind.
 */
@RequiresTm1
class Tm1SessionTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"),
			ODataQuery.none().select("Name", "ProductVersion"), JsonNode.class);

	private Tm1ServerContainer server;

	private RawTm1 raw;

	private String admin;

	@BeforeEach
	void connect(Tm1ServerContainer server) {
		this.server = server;
		this.raw = new RawTm1(server);
		this.admin = raw.login();
	}

	@AfterEach
	void disconnect() {
		raw.close(admin);
	}

	@Test
	void readsTheServerAndClosesItsSessionLeavingNoneBehind() {
		int before = raw.sessionCount(admin);
		Tm1Session session = session();

		JsonNode tm1 = session.execute(SERVER).block(WAIT);
		assertThat(tm1.get("ProductVersion").asString()).isEqualTo(server.getTm1Version().toString());
		long id = session.sessionId().block(WAIT);
		assertThat(raw.sessionCount(admin)).isEqualTo(before + 1);

		session.close().block(WAIT);
		assertThat(raw.awaitGone(admin, id, WAIT)).isTrue();
		assertThat(raw.awaitSessionCount(admin, before, WAIT)).isEqualTo(before);
	}

	@Test
	void sharesOneLoginBetweenConcurrentFirstRequests() {
		int before = raw.sessionCount(admin);
		Tm1Session session = session();
		List<JsonNode> results = Flux.range(0, 8).flatMap(i -> session.execute(SERVER)).collectList().block(WAIT);
		assertThat(results).hasSize(8);
		assertThat(raw.sessionCount(admin)).isEqualTo(before + 1);
		session.close().block(WAIT);
		assertThat(raw.awaitSessionCount(admin, before, WAIT)).isEqualTo(before);
	}

	@Test
	void leavesNoSessionWhenCloseRacesTheFirstRequest() {
		int before = raw.sessionCount(admin);
		Tm1Session session = session();
		Mono.when(session.execute(SERVER).onErrorResume(e -> Mono.empty()), session.close()).block(WAIT);
		assertThat(raw.awaitSessionCount(admin, before, WAIT)).isEqualTo(before);
	}

	/**
	 * The replay the client relies on, end to end: the session is killed from another
	 * session, then a POST through the client logs in again and creates the dimension
	 * exactly once, and the session TM1 opened for the 401 is the one logged in, so
	 * nothing is left behind.
	 */
	@Test
	void logsInAgainAfterItsSessionIsKilledAndRunsThePostOnce() {
		int before = raw.sessionCount(admin);
		Tm1Session session = session();
		String name = "zz_session_" + UUID.randomUUID().toString().substring(0, 8);
		try {
			long first = session.sessionId().block(WAIT);
			assertThat(raw.kill(admin, first)).isEqualTo(204);

			Map<String, Object> dimension = Map.of("Name", name, "Hierarchies", List.of(Map.of("Name", name)));
			session.execute(Tm1Request.post(ODataPath.of("Dimensions"), dimension, Void.class)).block(WAIT);

			assertThat(
					raw.send(raw.request("GET", "Dimensions/$count?$filter=Name%20eq%20'" + name + "'").cookie(admin))
						.body())
				.isEqualTo("1");
			assertThat(session.sessionId().block(WAIT)).isNotEqualTo(first);
		}
		finally {
			raw.send(raw.request("DELETE", "Dimensions('" + name + "')").cookie(admin));
			session.close().block(WAIT);
		}
		assertThat(raw.awaitSessionCount(admin, before, WAIT)).isEqualTo(before);
	}

	@Test
	void labelsTheSessionWithItsContext() {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.sessionContext("tm1-client test")
			.build();
		try {
			JsonNode active = session
				.execute(Tm1Request.get(ODataPath.of("ActiveSession"), ODataQuery.none().select("Context"),
						JsonNode.class))
				.block(WAIT);
			assertThat(active.get("Context").asString()).isEqualTo("tm1-client test");
		}
		finally {
			session.close().block(WAIT);
		}
	}

	/**
	 * TM1 reads {@code !sandbox} on any request: a sandbox that exists works, one that
	 * doesn't fails.
	 */
	@Test
	void runsRequestsInTheViewsSandbox() {
		Tm1Session session = session();
		String sandbox = "zz_session_" + UUID.randomUUID().toString().substring(0, 8);
		try {
			session.execute(Tm1Request.post(ODataPath.of("Sandboxes"), Map.of("Name", sandbox), Void.class))
				.block(WAIT);
			assertThat(session.withSandbox(sandbox).execute(SERVER).block(WAIT)).isNotNull();
			Object missing = session.withSandbox(sandbox + "_missing")
				.execute(SERVER)
				.map(n -> (Object) n)
				.onErrorResume(e -> Mono.just(e))
				.block(WAIT);
			assertThat(missing).isInstanceOf(net.ajmyers.tm1.client.error.Tm1ApiException.class);
		}
		finally {
			session.execute(Tm1Request.delete(ODataPath.of("Sandboxes").key(sandbox)))
				.onErrorResume(e -> Mono.empty())
				.block(WAIT);
			session.close().block(WAIT);
		}
	}

	@Test
	void streamsACollection() {
		Tm1Session session = session();
		try {
			List<String> names = session
				.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), JsonNode.class))
				.map(d -> d.get("Name").asString())
				.collectList()
				.block(WAIT);
			assertThat(names).isNotEmpty().allSatisfy(n -> assertThat(n).isNotBlank());
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void reportsAMissingObjectAsNotFound() {
		Tm1Session session = session();
		try {
			Mono<JsonNode> missing = session.execute(
					Tm1Request.get(ODataPath.of("Cubes").key("zz_no_such_cube"), ODataQuery.none(), JsonNode.class));
			assertThat(missing.map(n -> (Object) n).onErrorResume(e -> Mono.just(e)).block(WAIT))
				.isInstanceOf(Tm1NotFoundException.class);
		}
		finally {
			session.close().block(WAIT);
		}
	}

	private Tm1Session session() {
		return Tm1Session.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

}
