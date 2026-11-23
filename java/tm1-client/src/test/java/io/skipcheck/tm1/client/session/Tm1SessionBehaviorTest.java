package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.JsonNode;

/**
 * Pins how TM1 handles sessions, which {@code Tm1Session}'s design relies on (plan §5,
 * §16.1).
 *
 * <p>
 * These tests talk to TM1 over bare HTTP ({@link RawTm1}), not through the client, so
 * they show what TM1 does rather than what the client does. If a TM1 version changes one
 * of these behaviors, its test fails, and the session code needs a version branch (plan
 * §16.7).
 */
@RequiresTm1
class Tm1SessionBehaviorTest {

	private RawTm1 tm1;

	@BeforeEach
	void connect(Tm1ServerContainer server) {
		tm1 = new RawTm1(server);
	}

	/**
	 * The client replays a request once after a 401 on an established session, POSTs
	 * included (plan §5). That's only safe if TM1 refuses the request before running it.
	 * Checked with a cookie-only request: a request that also sent credentials would open
	 * a new session and succeed, and prove nothing.
	 */
	@Test
	void refusesARequestOnAKilledSessionWithoutRunningIt() {
		String admin = tm1.login();
		String victim = tm1.login();
		String dimension = scratchName();
		String create = "{\"Name\":\"" + dimension + "\",\"Hierarchies\":[{\"Name\":\"" + dimension + "\"}]}";
		try {
			assertThat(tm1.kill(admin, tm1.sessionId(victim))).isEqualTo(204);

			HttpResponse<String> refused = tm1.send(tm1.request("POST", "Dimensions").cookie(victim).body(create));
			assertThat(refused.statusCode()).isEqualTo(401);
			assertThat(tm1.send(tm1.request("GET", "Dimensions('" + dimension + "')").cookie(admin)).statusCode())
				.as("the refused POST must not have created the dimension")
				.isEqualTo(404);

			// Log in again, with the cookie the 401 handed out (see the next test), and
			// replay
			String relogged = RawTm1.cookie(refused).orElseThrow();
			assertThat(tm1.send(tm1.request("GET", "ActiveSession?$select=ID").cookie(relogged).basic()).statusCode())
				.isEqualTo(200);
			assertThat(tm1.send(tm1.request("POST", "Dimensions").cookie(relogged).body(create)).statusCode())
				.isEqualTo(201);
			assertThat(tm1
				.send(tm1.request("GET", "Dimensions/$count?$filter=Name%20eq%20'" + dimension + "'").cookie(admin))
				.body()).isEqualTo("1");
			tm1.close(relogged);
		}
		finally {
			tm1.send(tm1.request("DELETE", "Dimensions('" + dimension + "')").cookie(admin));
			tm1.close(admin);
		}
	}

	/**
	 * Every 401 opens a TM1 session with no user, and sets its cookie. Sending that
	 * cookie with the credentials logs that same session in, instead of opening another.
	 * So the client logs in again with the cookie from the 401, and a re-login doesn't
	 * leave a session behind.
	 */
	@Test
	void opensASessionOnEvery401AndLogsThatSessionInWhenItsCookieIsSentWithCredentials() {
		String admin = tm1.login();
		try {
			int before = tm1.allSessionCount(admin);
			HttpResponse<String> anonymous = tm1.send(tm1.request("GET", "ActiveSession?$select=ID"));
			assertThat(anonymous.statusCode()).isEqualTo(401);
			assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"TM1\"");
			String cookie = RawTm1.cookie(anonymous).orElseThrow();
			assertThat(tm1.allSessionCount(admin)).as("sessions after one 401").isEqualTo(before + 1);

			HttpResponse<String> login = tm1
				.send(tm1.request("GET", "ActiveSession?$select=ID").cookie(cookie).basic());
			assertThat(login.statusCode()).isEqualTo(200);
			assertThat(RawTm1.cookie(login)).as("no new cookie: the same session was logged in").isEmpty();
			assertThat(tm1.allSessionCount(admin)).as("sessions after logging in with the 401's cookie")
				.isEqualTo(before + 1);

			tm1.close(cookie);
			assertThat(tm1.allSessionCount(admin)).isEqualTo(before);
		}
		finally {
			tm1.close(admin);
		}
	}

	/**
	 * {@code Server} can be read with no credentials, but even that opens a session,
	 * which then sits there with no user until it times out. So the client never reads
	 * anything, the version included, outside its own session.
	 */
	@Test
	void opensASessionForAnAnonymousReadOfServer() {
		String admin = tm1.login();
		try {
			int before = tm1.allSessionCount(admin);
			HttpResponse<String> server = tm1.send(tm1.request("GET", "Server?$select=Name"));
			assertThat(server.statusCode()).isEqualTo(200);
			assertThat(tm1.allSessionCount(admin)).isEqualTo(before + 1);
			String cookie = RawTm1.cookie(server).orElseThrow();
			tm1.send(tm1.request("GET", "ActiveSession?$select=ID").cookie(cookie).basic());
			tm1.close(cookie);
		}
		finally {
			tm1.close(admin);
		}
	}

	/**
	 * A closed session is refused at once, but once it has served requests on several
	 * connections, TM1 keeps listing it in {@code Sessions} for several seconds (5 to 9 s
	 * observed), until it has ended the threads that served it. So a check that no
	 * session is left behind has to wait for the count to settle.
	 */
	@Test
	void refusesAClosedSessionAtOnceButListsItForSecondsMore() throws Exception {
		String admin = tm1.login();
		String cookie = tm1.login();
		long id = tm1.sessionId(cookie);
		try {
			List<CompletableFuture<HttpResponse<String>>> reads = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				reads.add(tm1.sendAsync(tm1.request("GET", "Server?$select=Name").cookie(cookie)));
			}
			for (CompletableFuture<HttpResponse<String>> read : reads) {
				assertThat(read.get().statusCode()).isEqualTo(200);
			}
			assertThat(tm1.send(tm1.request("POST", "ActiveSession/tm1.Close").cookie(cookie).body("{}")).statusCode())
				.isEqualTo(204);
			assertThat(tm1.send(tm1.request("GET", "Sessions(" + id + ")").cookie(admin)).statusCode())
				.as("still listed just after close")
				.isEqualTo(200);
			assertThat(tm1.awaitGone(admin, id, Duration.ofSeconds(30))).as("gone within 30 s").isTrue();
		}
		finally {
			tm1.close(admin);
		}
	}

	/**
	 * Closing a session expires its cookie, and later requests with that cookie get a
	 * 401.
	 */
	@Test
	void expiresTheCookieOnCloseAndRefusesItAfterwards() {
		String cookie = tm1.login();
		HttpResponse<String> close = tm1.send(tm1.request("POST", "ActiveSession/tm1.Close").cookie(cookie).body("{}"));
		assertThat(close.statusCode()).isEqualTo(204);
		assertThat(close.headers().allValues("set-cookie"))
			.anySatisfy(c -> assertThat(c).startsWith("TM1SessionId=").contains("Max-Age=0"));

		HttpResponse<String> after = tm1.send(tm1.request("GET", "ActiveSession?$select=ID").cookie(cookie));
		assertThat(after.statusCode()).isEqualTo(401);
		// That 401 opened a session too; log it in and close it so the test leaves
		// nothing behind
		String leftover = RawTm1.cookie(after).orElseThrow();
		tm1.send(tm1.request("GET", "ActiveSession?$select=ID").cookie(leftover).basic());
		tm1.close(leftover);
	}

	/**
	 * Requests on one session run in parallel, not one at a time: three one-second
	 * process runs finish in about one second. So one shared session doesn't serialize
	 * the client's traffic.
	 */
	@Test
	void runsConcurrentRequestsOnOneSessionInParallel() throws Exception {
		String cookie = tm1.login();
		String process = scratchName();
		try {
			assertThat(tm1
				.send(tm1.request("POST", "Processes")
					.cookie(cookie)
					.body("{\"Name\":\"" + process + "\",\"PrologProcedure\":\"Sleep(1000);\"}"))
				.statusCode()).isEqualTo(201);

			long start = System.nanoTime();
			List<CompletableFuture<HttpResponse<String>>> runs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				runs.add(tm1.sendAsync(tm1.request("POST", "Processes('" + process + "')/tm1.ExecuteWithReturn")
					.cookie(cookie)
					.body("{}")));
			}
			for (CompletableFuture<HttpResponse<String>> run : runs) {
				assertThat(RawTm1.json(run.get()).get("ProcessExecuteStatusCode").asString())
					.isEqualTo("CompletedSuccessfully");
			}
			assertThat(Duration.ofNanos(System.nanoTime() - start)).as("three 1 s runs on one session")
				.isLessThan(Duration.ofMillis(2500));
		}
		finally {
			tm1.send(tm1.request("DELETE", "Processes('" + process + "')").cookie(cookie));
			tm1.close(cookie);
		}
	}

	/**
	 * {@code TM1-SessionContext} labels the whole session, and the last request to send
	 * it wins. So it can't be a per-request setting on views that share one session:
	 * they'd keep relabelling each other's session.
	 */
	@Test
	void labelsTheSessionWithTheLastSessionContextSent() {
		HttpResponse<String> login = tm1
			.send(tm1.request("GET", "ActiveSession?$select=Context").basic().header("TM1-SessionContext", "first"));
		String cookie = RawTm1.cookie(login).orElseThrow();
		try {
			assertThat(RawTm1.json(login).get("Context").asString()).isEqualTo("first");
			tm1.send(tm1.request("GET", "Server?$select=Name").cookie(cookie).header("TM1-SessionContext", "second"));
			assertThat(RawTm1.json(tm1.send(tm1.request("GET", "ActiveSession?$select=Context").cookie(cookie)))
				.get("Context")
				.asString()).isEqualTo("second");
		}
		finally {
			tm1.close(cookie);
		}
	}

	/**
	 * A create answers 201 with a relative {@code Location} and an {@code ETag}. With
	 * {@code Prefer: return=minimal} it answers 204 with {@code Location} and
	 * {@code OData-EntityId}.
	 */
	@Test
	void answersACreateWithItsLocation() {
		String cookie = tm1.login();
		String full = scratchName();
		String minimal = scratchName();
		try {
			HttpResponse<String> created = tm1
				.send(tm1.request("POST", "Dimensions").cookie(cookie).body(dimension(full)));
			assertThat(created.statusCode()).isEqualTo(201);
			assertThat(created.headers().firstValue("Location")).hasValue("Dimensions('" + full + "')");
			assertThat(created.headers().firstValue("ETag")).isPresent();

			HttpResponse<String> bare = tm1.send(tm1.request("POST", "Dimensions")
				.cookie(cookie)
				.header("Prefer", "return=minimal")
				.body(dimension(minimal)));
			assertThat(bare.statusCode()).isEqualTo(204);
			assertThat(bare.headers().firstValue("Location")).hasValue("Dimensions('" + minimal + "')");
			assertThat(bare.headers().firstValue("OData-EntityId")).hasValue("Dimensions('" + minimal + "')");
		}
		finally {
			tm1.send(tm1.request("DELETE", "Dimensions('" + full + "')").cookie(cookie));
			tm1.send(tm1.request("DELETE", "Dimensions('" + minimal + "')").cookie(cookie));
			tm1.close(cookie);
		}
	}

	/**
	 * {@code odata.metadata=none} drops {@code @odata.etag} but still sends
	 * {@code @odata.context}.
	 */
	@Test
	void dropsTheEtagButNotTheContextWithMetadataNone() {
		String cookie = tm1.login();
		String name = scratchName();
		try {
			tm1.send(tm1.request("POST", "Dimensions").cookie(cookie).body(dimension(name)));
			JsonNode minimal = RawTm1.json(tm1.send(tm1.request("GET", "Dimensions('" + name + "')").cookie(cookie)));
			JsonNode none = RawTm1.json(tm1.send(tm1.request("GET", "Dimensions('" + name + "')")
				.cookie(cookie)
				.header("Accept", "application/json;odata.metadata=none")));
			assertThat(minimal.has("@odata.etag")).isTrue();
			assertThat(none.has("@odata.etag")).isFalse();
			assertThat(none.has("@odata.context")).isTrue();
		}
		finally {
			tm1.send(tm1.request("DELETE", "Dimensions('" + name + "')").cookie(cookie));
			tm1.close(cookie);
		}
	}

	private static String dimension(String name) {
		return "{\"Name\":\"" + name + "\",\"Hierarchies\":[{\"Name\":\"" + name + "\"}]}";
	}

	private static String scratchName() {
		return "zz_session_" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * TM1 closes a kept-alive connection that's idle for about 90 seconds, though
	 * {@code IdleConnectionTimeOut} is 0, and a request sent on it as it closes gets no
	 * response at all (#52). The default connector closes idle connections after 60
	 * seconds ({@code Tm1Session.MAX_IDLE}) so that never happens. Busy connections
	 * aren't closed: 2000 requests on one were fine.
	 */
	@Test
	void closesAConnectionIdleForNinetySeconds(Tm1ServerContainer server) throws InterruptedException {
		ConnectionProvider one = ConnectionProvider.builder("idle-probe").maxConnections(1).build();
		CompletableFuture<Long> closedAfter = new CompletableFuture<>();
		long[] used = { System.nanoTime() };
		HttpClient http = HttpClient.create(one)
			.doOnChannelInit((observer, channel, address) -> channel.closeFuture()
				.addListener(f -> closedAfter.complete((System.nanoTime() - used[0]) / 1_000_000_000L)))
			.headers(h -> h.set("Authorization", server.getAuthorization()));
		try {
			assertThat(http.get()
				.uri(server.getRestUrl() + "Server?%24select=Name")
				.responseSingle((response, body) -> body.asString().thenReturn(response.status().code()))
				.block(Duration.ofSeconds(30))).isEqualTo(200);
			used[0] = System.nanoTime();
			assertThat(closedAfter.orTimeout(150, TimeUnit.SECONDS).join()).as("seconds idle before TM1 closed it")
				.isBetween(80L, 100L);
			assertThat(Tm1Session.MAX_IDLE).isLessThan(Duration.ofSeconds(80));
		}
		finally {
			one.dispose();
		}
	}

}
