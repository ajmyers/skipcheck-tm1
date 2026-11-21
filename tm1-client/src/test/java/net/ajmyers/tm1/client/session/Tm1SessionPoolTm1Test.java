package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** {@link Tm1SessionPool} against a real TM1: identities, eviction and close. */
@RequiresTm1
class Tm1SessionPoolTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final Tm1Request<JsonNode> ACTIVE_USER = Tm1Request.get(ODataPath.of("ActiveUser"),
			ODataQuery.none().select("Name"), JsonNode.class);

	private RawTm1 raw;

	private String admin;

	private String alice;

	private String bob;

	private String context;

	private Tm1SessionPool pool;

	@BeforeEach
	void setUp(Tm1ServerContainer server) {
		raw = new RawTm1(server);
		admin = raw.login();
		context = RawTm1.uniqueContext();
		String run = UUID.randomUUID().toString().substring(0, 6);
		alice = "zz_alice_" + run;
		bob = "zz_bob_" + run;
		raw.createUser(admin, alice, "a");
		raw.createUser(admin, bob, "b");
		pool = Tm1SessionPool
			.builder(Tm1Session.builder()
				.baseUrl(server.getRestUrl())
				.basicAuth(server.getUsername(), server.getPassword())
				.sessionContext(context))
			.idleTimeout(Duration.ofSeconds(1))
			.closeGrace(Duration.ZERO)
			.sweepInterval(Duration.ofHours(1))
			.build();
	}

	@AfterEach
	void tearDown() {
		pool.close().block(WAIT);
		raw.deleteUser(admin, alice);
		raw.deleteUser(admin, bob);
		raw.close(admin);
	}

	@Test
	void runsEachIdentityAsItself() {
		assertThat(name(pool.session(Tm1Identity.impersonating(alice)))).isEqualTo(alice);
		assertThat(name(pool.session(Tm1Identity.impersonating(bob)))).isEqualTo(bob);
		assertThat(name(pool.session(Tm1Identity.withCredentials(bob, Tm1Authenticator.basic(bob, "b")))))
			.isEqualTo(bob);
		assertThat(name(pool.session())).isEqualTo("Admin");
		assertThat(pool.stats().held()).isEqualTo(4);
	}

	@Test
	void closesEvictedSessionsOnTm1() throws InterruptedException {
		Tm1Session session = pool.session(Tm1Identity.impersonating(alice));
		assertThat(name(session)).isEqualTo(alice);
		long id = session.sessionId().block(WAIT);

		Thread.sleep(1100);
		pool.evictExpired();
		assertThat(raw.awaitGone(admin, id, WAIT)).isTrue();
		assertThat(pool.stats().closed()).isEqualTo(1);
	}

	@Test
	void leavesNoSessionBehindWhenClosed() {
		name(pool.session(Tm1Identity.impersonating(alice)));
		name(pool.session(Tm1Identity.impersonating(bob)));
		pool.close().block(WAIT);
		assertThat(raw.awaitSessionCount(admin, context, 0, WAIT)).isZero();
	}

	/** Asking for an administrator fails; nothing runs as the pool's own user instead. */
	@Test
	void neverFallsBackToAnotherIdentity() {
		Object outcome = pool.session(Tm1Identity.impersonating("Admin"))
			.execute(ACTIVE_USER)
			.map(n -> (Object) n)
			.onErrorResume(e -> Mono.just(e))
			.block(WAIT);
		assertThat(outcome).isInstanceOfSatisfying(Tm1ApiException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED));
	}

	private static String name(Tm1Session session) {
		return session.execute(ACTIVE_USER).block(WAIT).get("Name").asString();
	}

}
