// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static io.skipcheck.tm1.client.session.FakeTm1.json;
import static io.skipcheck.tm1.client.session.FakeTm1.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

import io.skipcheck.tm1.client.Tm1Request;
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

}
