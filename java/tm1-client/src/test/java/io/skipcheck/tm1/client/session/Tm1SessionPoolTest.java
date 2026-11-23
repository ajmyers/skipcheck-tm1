// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.JsonNode;

/** The pool's bookkeeping, on virtual time against {@link FakeTm1}. */
class Tm1SessionPoolTest {

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(),
			JsonNode.class);

	private final FakeTm1 tm1 = new FakeTm1().on("GET Server", r -> Mono.just(FakeTm1.json(200, "{}")));

	private final VirtualTimeScheduler time = VirtualTimeScheduler.create();

	private final TestObservationRegistry observations = TestObservationRegistry.create();

	private Tm1SessionPool pool = pool(b -> b);

	@AfterEach
	void closePool() {
		pool.close().block(Duration.ofSeconds(5));
		time.dispose();
	}

	@Test
	void keepsOneSessionPerIdentityMatchingNamesAsTm1Does() {
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		assertThat(pool.session(Tm1Identity.impersonating("ALICE"))).isSameAs(alice);
		assertThat(pool.session(Tm1Identity.impersonating("al ice"))).isSameAs(alice);
		assertThat(pool.session(Tm1Identity.impersonating("bob"))).isNotSameAs(alice);
		assertThat(pool.session()).isNotSameAs(alice);
		assertThat(pool.stats().held()).isEqualTo(3);
		assertThat(tm1.requests()).as("getting a session sends nothing").isEmpty();
	}

	@Test
	void logsEachIdentityInAsItself() {
		pool.session(Tm1Identity.impersonating("alice")).execute(SERVER).block();
		pool.session().execute(SERVER).block();
		pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c")))
			.execute(SERVER)
			.block();

		List<ClientRequest> logins = tm1.requests(FakeTm1.LOGIN);
		assertThat(logins).extracting(r -> r.headers().getFirst("TM1-Impersonate"))
			.containsExactly("alice", null, null);
		assertThat(logins).extracting(FakeTm1::authorization)
			.containsExactly("Basic YWRtaW46YXBwbGU=", "Basic YWRtaW46YXBwbGU=", "Basic Y2Fyb2w6Yw==");
	}

	@Test
	void evictsAnIdleSessionAndClosesItAfterTheGracePeriod() {
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		alice.execute(SERVER).block();

		time.advanceTimeBy(Duration.ofMinutes(10));
		Tm1Session fresh = pool.session(Tm1Identity.impersonating("alice"));
		assertThat(fresh).isNotSameAs(alice);
		assertThat(pool.stats().evictedIdle()).isEqualTo(1);
		assertThat(alice.isClosed()).as("still open during the grace period").isFalse();

		time.advanceTimeBy(Duration.ofSeconds(30));
		assertThat(alice.isClosed()).isTrue();
		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(1);
		assertThat(pool.stats().closed()).isEqualTo(1);
		assertThat(pool.stats().closing()).isZero();
	}

	@Test
	void keepsASessionThatsInUse() {
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		for (int i = 0; i < 5; i++) {
			time.advanceTimeBy(Duration.ofMinutes(9));
			assertThat(pool.session(Tm1Identity.impersonating("alice"))).isSameAs(alice);
		}
	}

	@Test
	void givesDifferentCredentialsForTheSameNameTheirOwnSession() {
		Tm1Session carol = pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c")));
		assertThat(pool.session(Tm1Identity.withCredentials("Carol", Tm1Authenticator.basic("carol", "c"))))
			.as("the same credentials, made again")
			.isSameAs(carol);
		carol.execute(SERVER).block();

		Tm1Session other = pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "wrong")));
		assertThat(other).as("never the session someone else's credentials opened").isNotSameAs(carol);
		other.execute(SERVER).block();
		assertThat(tm1.requests(FakeTm1.LOGIN)).extracting(FakeTm1::authorization)
			.as("each logs in with what it was given")
			.containsExactly("Basic Y2Fyb2w6Yw==", "Basic Y2Fyb2w6d3Jvbmc=");
	}

	@Test
	void sharesASessionForTheSameAuthenticatorWhenItsCredentialsCantBeCompared() {
		Tm1Authenticator passport = Tm1Authenticator.camPassport(() -> Mono.just("p1"));
		Tm1Session dave = pool.session(Tm1Identity.withCredentials("dave", passport));
		assertThat(pool.session(Tm1Identity.withCredentials("dave", passport))).isSameAs(dave);
		assertThat(
				pool.session(Tm1Identity.withCredentials("dave", Tm1Authenticator.camPassport(() -> Mono.just("p1")))))
			.isNotSameAs(dave);
		assertThat(Tm1Authenticator.camPassport("p1")).isEqualTo(Tm1Authenticator.camPassport("p1"))
			.isNotEqualTo(Tm1Authenticator.camPassport("p2"));
		assertThat(Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "d"))
			.isEqualTo(Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "d"))
			.isNotEqualTo(Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "other"));
	}

	@Test
	void keepsASessionWhileARequestIsRunningOnIt() {
		Sinks.One<ClientResponse> answer = Sinks.one();
		tm1.on("GET Server", r -> answer.asMono());
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		AtomicReference<JsonNode> result = new AtomicReference<>();
		alice.execute(SERVER).subscribe(result::set);

		time.advanceTimeBy(Duration.ofMinutes(25));
		assertThat(pool.stats().evictedIdle()).as("a running request is use").isZero();
		assertThat(alice.isClosed()).isFalse();

		answer.tryEmitValue(FakeTm1.json(200, "{}"));
		assertThat(result.get()).isNotNull();
		time.advanceTimeBy(Duration.ofMinutes(12));
		assertThat(pool.stats().evictedIdle()).as("idle once it has finished").isEqualTo(1);
		assertThat(alice.isClosed()).isTrue();
	}

	@Test
	void closesAnEvictedSessionOnlyOnceItsRequestsHaveFinished() {
		pool.close().block();
		pool = pool(b -> b.maxAge(Duration.ofMinutes(30)));
		Sinks.One<ClientResponse> answer = Sinks.one();
		tm1.on("GET Server", r -> answer.asMono());
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		AtomicReference<JsonNode> result = new AtomicReference<>();
		alice.execute(SERVER).subscribe(result::set);

		time.advanceTimeBy(Duration.ofMinutes(40));
		assertThat(pool.stats().evictedAge()).as("out of the pool at its age").isEqualTo(1);
		assertThat(alice.isClosed()).as("but open for the request still running").isFalse();

		answer.tryEmitValue(FakeTm1.json(200, "{}"));
		assertThat(result.get()).isNotNull();
		time.advanceTimeBy(Duration.ofSeconds(31));
		assertThat(alice.isClosed()).isTrue();
	}

	@Test
	void evictsASessionPastItsMaximumAgeEvenIfInUse() {
		pool.close().block();
		pool = pool(b -> b.maxAge(Duration.ofMinutes(30)));
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		for (int i = 0; i < 3; i++) {
			time.advanceTimeBy(Duration.ofMinutes(9));
			pool.session(Tm1Identity.impersonating("alice"));
		}
		time.advanceTimeBy(Duration.ofMinutes(4));
		assertThat(pool.session(Tm1Identity.impersonating("alice"))).isNotSameAs(alice);
		assertThat(pool.stats().evictedAge()).isEqualTo(1);
	}

	@Test
	void evictsTheLeastRecentlyUsedSessionWhenFull() {
		pool.close().block();
		pool = pool(b -> b.maxSize(2));
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		Tm1Session bob = pool.session(Tm1Identity.impersonating("bob"));
		pool.session(Tm1Identity.impersonating("alice"));
		pool.session(Tm1Identity.impersonating("carol"));

		assertThat(pool.stats().held()).isEqualTo(2);
		assertThat(pool.stats().evictedSize()).isEqualTo(1);
		assertThat(pool.session(Tm1Identity.impersonating("alice"))).isSameAs(alice);
		assertThat(pool.session(Tm1Identity.impersonating("bob"))).isNotSameAs(bob);
	}

	@Test
	void sweepsExpiredSessionsOnItsOwn() {
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		alice.execute(SERVER).block();
		time.advanceTimeBy(Duration.ofMinutes(11));
		assertThat(pool.stats().held()).isZero();
		time.advanceTimeBy(Duration.ofSeconds(30));
		assertThat(alice.isClosed()).isTrue();
	}

	@Test
	void closesEverySessionAtOnceIncludingThoseInTheirGracePeriod() {
		Tm1Session alice = pool.session(Tm1Identity.impersonating("alice"));
		alice.execute(SERVER).block();
		time.advanceTimeBy(Duration.ofMinutes(10));
		Tm1Session fresh = pool.session(Tm1Identity.impersonating("alice"));
		fresh.execute(SERVER).block();

		pool.close().block(Duration.ofSeconds(5));
		assertThat(alice.isClosed()).isTrue();
		assertThat(fresh.isClosed()).isTrue();
		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(2);
		assertThatThrownBy(() -> pool.session()).isInstanceOf(Tm1SessionClosedException.class);
	}

	@Test
	void observesEvictionsWithTheirReason() {
		pool.session(Tm1Identity.impersonating("alice"));
		time.advanceTimeBy(Duration.ofMinutes(10));
		pool.session(Tm1Identity.impersonating("alice"));
		TestObservationRegistryAssert.assertThat(observations)
			.hasObservationWithNameEqualTo("tm1.session.pool.evict")
			.that()
			.hasLowCardinalityKeyValue("tm1.reason", "idle");
	}

	@Test
	void refusesIdentitiesTm1WouldMisread() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Identity.impersonating(" "));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Identity.withCredentials("", Tm1Authenticator.basic("x", "y")));
		assertThat(Tm1Identity.impersonating("Alice")).isEqualTo(Tm1Identity.impersonating("alice"))
			.isNotEqualTo(Tm1Identity.withCredentials("alice", Tm1Authenticator.basic("alice", "a")));
		assertThat(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "secret")).toString())
			.doesNotContain("secret");
	}

	private Tm1SessionPool pool(UnaryOperator<Tm1SessionPool.Builder> settings) {
		Tm1Session.Builder sessions = Tm1Session.builder()
			.baseUrl(FakeTm1.ROOT)
			.basicAuth("admin", "apple")
			.observationRegistry(observations)
			.webClient(WebClient.builder().exchangeFunction(tm1));
		return settings.apply(Tm1SessionPool.builder(sessions).scheduler(time)).build();
	}

}
