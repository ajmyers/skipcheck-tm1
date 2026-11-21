package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

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

/** {@link Tm1Session} against a real TM1: logging in, requests, and leaving no session behind. */
@RequiresTm1
class Tm1SessionTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);
	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"),
			JsonNode.class);

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

	@Test
	void streamsACollection() {
		Tm1Session session = session();
		try {
			List<String> names = session.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), JsonNode.class))
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
			Mono<JsonNode> missing = session.execute(Tm1Request.get(ODataPath.of("Cubes").key("zz_no_such_cube"), ODataQuery.none(), JsonNode.class));
			assertThat(missing.map(n -> (Object) n).onErrorResume(e -> Mono.just(e)).block(WAIT)).isInstanceOf(Tm1NotFoundException.class);
		}
		finally {
			session.close().block(WAIT);
		}
	}

	private Tm1Session session() {
		return Tm1Session.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
	}
}
