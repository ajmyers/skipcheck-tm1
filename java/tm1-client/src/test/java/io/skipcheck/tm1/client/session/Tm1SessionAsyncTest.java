// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static io.skipcheck.tm1.client.session.FakeTm1.json;
import static io.skipcheck.tm1.client.session.FakeTm1.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import io.skipcheck.tm1.client.error.Tm1ServerException;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/**
 * Tm1Session.withAsync: Prefer: respond-async, polling the monitor, and deleting it on
 * cancel.
 */
class Tm1SessionAsyncTest {

	private static final String RUN = "POST Processes('p')/tm1.ExecuteWithReturn";

	private static final String MONITOR = "GET _async('a1')";

	private static final Tm1Request<JsonNode> REQUEST = Tm1Request
		.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null, JsonNode.class);

	private static ClientResponse accepted() {
		return ClientResponse.create(HttpStatus.ACCEPTED).header("Location", "../_async('a1')").build();
	}

	private static ClientResponse done(String statusLine, String body) {
		String message = "HTTP/1.1 " + statusLine + "\r\nContent-Type: application/json\r\nOData-Version: 4.0\r\n\r\n"
				+ body;
		return ClientResponse.create(HttpStatus.OK)
			.header("Content-Type", "application/http")
			.body(new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
			.build();
	}

	@Test
	void pollsTheMonitorUntilTheResultIsThere() {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.onEach(MONITOR, request -> Mono.just(status(202)), request -> Mono.just(status(202)), request -> Mono
				.just(done("201 Created", "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")));
		Tm1Session session = tm1.session().withAsync(Duration.ofMillis(10));

		StepVerifier.create(session.execute(REQUEST))
			.assertNext(result -> assertThat(result.path("ProcessExecuteStatusCode").asString())
				.isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		assertThat(tm1.requests(RUN)).singleElement()
			.satisfies(run -> assertThat(run.headers().getFirst("Prefer")).isEqualTo("respond-async"));
		assertThat(tm1.requests(MONITOR)).hasSize(3);
		assertThat(tm1.requests("DELETE _async('a1')")).as("not deleted once done").isEmpty();
	}

	@Test
	void raisesTheErrorTheWorkEndedWith() {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR, request -> Mono.just(done("500 Internal Server Error",
					"{\"error\":{\"code\":\"174\",\"message\":\"TM1UserException: Cancel\"}}")));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
			.expectErrorSatisfies(
					e -> assertThat(e).isInstanceOf(Tm1ServerException.class).hasMessageContaining("Cancel"))
			.verify();
	}

	@Test
	void readsAnAnswerTm1GaveAtOnce() {
		FakeTm1 tm1 = new FakeTm1().on(RUN,
				request -> Mono.just(json(201, "{\"ProcessExecuteStatusCode\":\"Aborted\"}")));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
			.assertNext(result -> assertThat(result.path("ProcessExecuteStatusCode").asString()).isEqualTo("Aborted"))
			.verifyComplete();
		assertThat(tm1.requests(RUN)).as("sent once").hasSize(1);
	}

	@Test
	void deletesTheMonitorWhenCancelled() throws InterruptedException {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR, request -> Mono.just(status(202)))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)));
		Disposable running = tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST).subscribe();
		Thread.sleep(200);
		running.dispose();
		Thread.sleep(200);

		assertThat(tm1.requests(MONITOR)).isNotEmpty();
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);
	}

	@Test
	void readsAResultBiggerThanTheCodecsBufferLimit() {
		// Spring's codecs refuse to buffer more than 256 KB unless told otherwise
		String big = "x".repeat(300_000);
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR, request -> Mono.just(done("200 OK", "{\"Text\":\"" + big + "\"}")));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
			.assertNext(result -> assertThat(result.path("Text").asString()).hasSize(300_000))
			.verifyComplete();
	}

	@Test
	void readsABigCollectionTm1GaveAtOnce() {
		String big = "x".repeat(300_000);
		FakeTm1 tm1 = new FakeTm1().on("GET Cubes",
				request -> Mono.just(json(200, "{\"value\":[{\"Name\":\"" + big + "\"}]}")));

		StepVerifier
			.create(tm1.session()
				.withAsync(Duration.ofMillis(10))
				.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class)))
			.assertNext(cube -> assertThat(cube.path("Name").asString()).hasSize(300_000))
			.verifyComplete();
	}

	@Test
	void followsANextLinkInAResult() {
		FakeTm1 tm1 = new FakeTm1()
			.on("GET Cubes",
					request -> Mono
						.just(json(200, "{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Cubes?$skiptoken=2\"}")))
			.on("GET Cubes?$skiptoken=2", request -> Mono.just(json(200, "{\"value\":[{\"Name\":\"b\"}]}")));

		StepVerifier
			.create(tm1.session()
				.withAsync(Duration.ofMillis(10))
				.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class))
				.map(cube -> cube.path("Name").asString()))
			.expectNext("a", "b")
			.verifyComplete();
	}

	@Test
	void readsABigErrorBody() {
		String big = "x".repeat(300_000);
		FakeTm1 tm1 = new FakeTm1().on(RUN,
				request -> Mono.just(json(500, "{\"error\":{\"code\":\"174\",\"message\":\"" + big + "\"}}")));

		StepVerifier.create(tm1.session().execute(REQUEST)).expectError(Tm1ServerException.class).verify();
	}

	@Test
	void unwrapsAnApplicationHttpMessage() {
		AsyncResult result = AsyncResult
			.parse("HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
		assertThat(result.status()).isEqualTo(201);
		assertThat(new String(result.body(), StandardCharsets.US_ASCII)).isEqualTo("{}");
	}

	@Test
	void decompressesAGzippedInnerBody() throws Exception {
		ByteArrayOutputStream zipped = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) {
			gzip.write("{\"Name\":\"tm1\"}".getBytes(StandardCharsets.UTF_8));
		}
		byte[] head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: application/json\r\n\r\n"
			.getBytes(StandardCharsets.US_ASCII);
		byte[] message = new byte[head.length + zipped.size()];
		System.arraycopy(head, 0, message, 0, head.length);
		System.arraycopy(zipped.toByteArray(), 0, message, head.length, zipped.size());
		assertThat(new String(AsyncResult.parse(message).body(), StandardCharsets.UTF_8))
			.isEqualTo("{\"Name\":\"tm1\"}");
	}

	@Test
	void readsTm112AsyncResultHeaderAndDeletesMonitor() {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.onEach(MONITOR, request -> Mono.just(status(202)),
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json; charset=utf-8")
						.header("Asyncresult", " 201 ")
						.body("{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")
						.build()))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
			.assertNext(result -> assertThat(result.path("ProcessExecuteStatusCode").asString())
				.isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		assertThat(tm1.requests(MONITOR)).hasSize(2);
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);
	}

	@Test
	void readsTm112AsyncNoContentResult() {
		Tm1Request<Void> runNoContent = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.Execute"), null,
				Void.class);
		FakeTm1 tm1 = new FakeTm1().on("POST Processes('p')/tm1.Execute", request -> Mono.just(accepted()))
			.on(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK).header("Asyncresult", "204").build()))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(runNoContent)).verifyComplete();
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);
	}

	@Test
	void raisesTm112AsyncErrorFromAsyncresultHeader() {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json; charset=utf-8")
						.header("Asyncresult", "400")
						.body("{\"error\":{\"code\":\"501\",\"message\":\"Bad MDX\"}}")
						.build()))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)));

		StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1BadRequestException.class, bad -> {
				assertThat(bad.error()).isNotNull();
				assertThat(bad.error().code()).isEqualTo("501");
				assertThat(bad.error().message()).isEqualTo("Bad MDX");
			}))
			.verify();
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);
	}

	@Test
	void ignoresCleanupFailureAfterReadingTm112AsyncResult() {
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json")
						.header("Asyncresult", "201")
						.body("{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")
						.build()))
			.onEach("DELETE _async('a1')", request -> Mono.just(status(500)),
					request -> Mono.error(new IOException("connection reset")));

		Tm1Session asyncSession = tm1.session().withAsync(Duration.ofMillis(10));
		StepVerifier.create(asyncSession.execute(REQUEST))
			.assertNext(result -> assertThat(result.path("ProcessExecuteStatusCode").asString())
				.isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		StepVerifier.create(asyncSession.execute(REQUEST))
			.assertNext(result -> assertThat(result.path("ProcessExecuteStatusCode").asString())
				.isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(2);
	}

	@Test
	void rejectsInvalidAsyncresultHeader() {
		for (String bad : List.of("", "   ", "not-a-status", "+200", "-200", "99", "600", "0200",
				"99999999999999999999")) {
			FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
				.on(MONITOR,
						request -> Mono.just(ClientResponse.create(HttpStatus.OK)
							.header("Content-Type", "application/json")
							.header("Asyncresult", bad)
							.body("{}")
							.build()));

			StepVerifier.create(tm1.session().withAsync(Duration.ofMillis(10)).execute(REQUEST))
				.expectError(Tm1ProtocolException.class)
				.verify();
			assertThat(tm1.requests("DELETE _async('a1')")).as("bad Asyncresult '%s'", bad).isEmpty();
		}
	}

	@Test
	void sendsPreferWaitAndHandlesImmediateOrDeferredCompletion() {
		FakeTm1 tm1 = new FakeTm1()
			.onEach(RUN, request -> Mono.just(json(201, "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")),
					request -> Mono.just(json(201, "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")),
					request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED)
						.header("Location", "../_async('a1')")
						.header("Preference-Applied", "respond-async, wait=2")
						.build()),
					request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED)
						.header("Location", "../_async('a1')")
						.header("Preference-Applied", "respond-async")
						.build()))
			.onEach(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json")
						.header("Asyncresult", "201")
						.body("{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")
						.build()),
					request -> Mono
						.just(done("201 Created", "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)));

		Tm1Session base = tm1.session();
		assertThatIllegalArgumentException().isThrownBy(() -> base.withAsync(Duration.ZERO, Duration.ofSeconds(1)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base.withAsync(Duration.ofMillis(10), Duration.ofSeconds(-1)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base.withAsync(Duration.ofMillis(10), Duration.ofMillis(500)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base.withAsync(Duration.ofMillis(10), Duration.ofMillis(1500)));

		Tm1Session zeroWait = base.withAsync(Duration.ofMillis(10), Duration.ZERO);
		assertThat(zeroWait.asyncWait()).contains(Duration.ZERO);
		assertThat(zeroWait.withSandbox("sb").asyncWait()).contains(Duration.ZERO);
		assertThat(zeroWait.withSandbox("sb").withoutSandbox().asyncWait()).contains(Duration.ZERO);
		assertThat(zeroWait.withChangeSet("cs1").asyncWait()).contains(Duration.ZERO);
		assertThat(zeroWait.withChangeSet("cs1").withoutChangeSet().asyncWait()).contains(Duration.ZERO);
		assertThat(zeroWait.batch().session().asyncWait()).isEmpty();
		assertThat(zeroWait.withAsync(Duration.ofMillis(10)).asyncWait()).isEmpty();
		assertThat(zeroWait.withoutAsync().asyncWait()).isEmpty();

		Tm1Client waitedClient = Tm1Client.of(base).withAsync(Duration.ofMillis(10), Duration.ofSeconds(2));
		Tm1Session waited = waitedClient.session();
		assertThat(waited.asyncWait()).contains(Duration.ofSeconds(2));

		// wait=0 on the wire
		StepVerifier.create(zeroWait.execute(REQUEST))
			.assertNext(
					r -> assertThat(r.path("ProcessExecuteStatusCode").asString()).isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		// Immediate completion within wait: zero polls
		StepVerifier.create(waited.execute(REQUEST))
			.assertNext(
					r -> assertThat(r.path("ProcessExecuteStatusCode").asString()).isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		// Deferred completion on TM1 12 (Preference-Applied includes wait=2)
		StepVerifier.create(waited.execute(REQUEST))
			.assertNext(
					r -> assertThat(r.path("ProcessExecuteStatusCode").asString()).isEqualTo("CompletedSuccessfully"))
			.verifyComplete();
		// Graceful degradation on TM1 11 (Preference-Applied omits wait=)
		StepVerifier.create(waited.execute(REQUEST))
			.assertNext(
					r -> assertThat(r.path("ProcessExecuteStatusCode").asString()).isEqualTo("CompletedSuccessfully"))
			.verifyComplete();

		assertThat(tm1.requests(RUN)).extracting(r -> r.headers().getFirst("Prefer"))
			.containsExactly("respond-async, wait=0", "respond-async, wait=2", "respond-async, wait=2",
					"respond-async, wait=2");
		assertThat(tm1.requests(MONITOR)).hasSize(2);
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);

		// Preference-Applied wait token parsing edge cases
		assertThat(Connection.hasWaitApplied(List.of("respond-async, wait=2"))).isTrue();
		assertThat(Connection.hasWaitApplied(List.of("respond-async", "wait = 5; foo=bar"))).isTrue();
		assertThat(Connection.hasWaitApplied(List.of("respond-async, wait="))).isFalse();
		assertThat(Connection.hasWaitApplied(List.of("respond-async, foo=\"a, wait=1\""))).isFalse();
		assertThat(Connection.hasWaitApplied(List.of())).isFalse();
	}

	@Test
	void streamsTm112AsyncCollectionWithWaitAndFollowsNextLink() {
		FakeTm1 tm1 = new FakeTm1()
			.on("GET Cubes",
					request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED)
						.header("Location", "_async('a1')")
						.header("Preference-Applied", "respond-async, wait=2")
						.build()))
			.on(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json")
						.header("Asyncresult", "200")
						.body("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Cubes?$skiptoken=2\"}")
						.build()))
			.on("DELETE _async('a1')", request -> Mono.just(status(204)))
			.on("GET Cubes?$skiptoken=2", request -> Mono.just(json(200, "{\"value\":[{\"Name\":\"b\"}]}")));

		StepVerifier
			.create(tm1.session()
				.withAsync(Duration.ofMillis(10), Duration.ofSeconds(2))
				.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class))
				.map(cube -> cube.path("Name").asString()))
			.expectNext("a", "b")
			.verifyComplete();

		assertThat(tm1.requests("GET Cubes")).singleElement()
			.satisfies(r -> assertThat(r.headers().getFirst("Prefer")).isEqualTo("respond-async, wait=2"));
		assertThat(tm1.requests("GET Cubes?$skiptoken=2")).singleElement()
			.satisfies(r -> assertThat(r.headers().getFirst("Prefer")).isNull());
		assertThat(tm1.requests("DELETE _async('a1')")).hasSize(1);
	}

	@Test
	void mergesPreferHeaderAndRespectsApplicationHttpContentType() {
		String message = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n\r\n{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}";
		FakeTm1 tm1 = new FakeTm1().on(RUN, request -> Mono.just(accepted()))
			.on(MONITOR,
					request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/http; msgtype=response")
						.header("Asyncresult", "201")
						.body(message)
						.build()));

		Tm1Request<JsonNode> withExistingPrefer = REQUEST.withHeader("prefer", "odata.maxpagesize=50, wait=9");
		StepVerifier
			.create(tm1.session().withAsync(Duration.ofMillis(10), Duration.ofSeconds(2)).execute(withExistingPrefer))
			.assertNext(
					r -> assertThat(r.path("ProcessExecuteStatusCode").asString()).isEqualTo("CompletedSuccessfully"))
			.verifyComplete();

		assertThat(tm1.requests(RUN)).singleElement()
			.satisfies(r -> assertThat(r.headers().get("Prefer"))
				.containsExactly("odata.maxpagesize=50, respond-async, wait=2"));
		assertThat(tm1.requests("DELETE _async('a1')")).as("application/http monitor is not deleted").isEmpty();
	}

}
