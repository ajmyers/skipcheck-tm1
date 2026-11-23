// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1TransportException;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

class Tm1TimeoutsTest {

	/**
	 * The request timeout in the tests against a slow server: ten times the pause between
	 * pieces.
	 */
	private static final Duration REQUEST = Duration.ofSeconds(1);

	private static final Duration PAUSE = Duration.ofMillis(100);

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"),
			ODataQuery.none().select("Name"), JsonNode.class);

	private final CountDownLatch done = new CountDownLatch(1);

	private final ExecutorService threads = Executors.newCachedThreadPool();

	private HttpServer server;

	@AfterEach
	void stop() {
		done.countDown();
		if (server != null) {
			server.stop(0);
		}
		threads.shutdownNow();
	}

	@Test
	void fillsInDefaults() {
		assertThat(Tm1Timeouts.DEFAULTS).isEqualTo(new Tm1Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30),
				Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(30)));
		Tm1Timeouts longRequests = Tm1Timeouts.builder().request(Duration.ofHours(1)).build();
		assertThat(longRequests.request()).isEqualTo(Duration.ofHours(1));
		assertThat(longRequests.login()).isEqualTo(Duration.ofSeconds(30));
		assertThat(longRequests.toBuilder().close(Duration.ofSeconds(1)).build().close())
			.isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void refusesTimeoutsThatArentPositive() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Timeouts.builder().login(Duration.ZERO).build());
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Timeouts.builder().connect(Duration.ofSeconds(-1)).build());
	}

	@Test
	void keepsReadingABodyThatArrivesSlowlyButSteadily() throws IOException {
		// Twice the request timeout in all, never pausing for more than a tenth of it
		Tm1Session session = slowTm1(out -> {
			out.write("{\"Name\":\"tm1\"".getBytes(StandardCharsets.UTF_8));
			for (int i = 0; i < 20; i++) {
				pause(out, PAUSE);
				out.write(' ');
			}
			out.write('}');
		});
		long start = System.nanoTime();
		StepVerifier.create(session.execute(SERVER))
			.assertNext(server -> assertThat(server.get("Name").asString()).isEqualTo("tm1"))
			.expectComplete()
			.verify(Duration.ofSeconds(20));
		assertThat(Duration.ofNanos(System.nanoTime() - start)).as("the body took longer than the timeout")
			.isGreaterThan(REQUEST);
	}

	@Test
	void failsABodyThatStallsForLongerThanTheRequestTimeout() throws IOException {
		Tm1Session session = slowTm1(out -> {
			out.write("{\"Name\":".getBytes(StandardCharsets.UTF_8));
			out.flush();
			try {
				done.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		StepVerifier.create(session.execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1TransportException.class,
					t -> assertThat(t.phase()).isEqualTo(Tm1TransportException.Phase.RESPONSE)))
			.verify(Duration.ofSeconds(20));
	}

	/**
	 * A session with a short request timeout, against a server that logs in and answers
	 * {@code GET Server} with a 200 whose body {@code body} writes.
	 */
	private Tm1Session slowTm1(Body body) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(threads);
		server.createContext("/api/v1/ActiveSession", exchange -> {
			if (exchange.getRequestURI().getPath().endsWith("tm1.Close")) {
				exchange.sendResponseHeaders(204, -1);
			}
			else {
				byte[] login = "{\"ID\":1}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.getResponseHeaders().add("Set-Cookie", "TM1SessionId=s1; Path=/api/; HttpOnly");
				exchange.sendResponseHeaders(200, login.length);
				exchange.getResponseBody().write(login);
			}
			exchange.close();
		});
		server.createContext("/api/v1/Server", exchange -> answer(exchange, body));
		server.start();
		return Tm1Session.builder()
			.baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/")
			.basicAuth("admin", "apple")
			.timeouts(Tm1Timeouts.builder().request(REQUEST).build())
			.build();
	}

	private static void answer(HttpExchange exchange, Body body) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		// Chunked, so each piece goes out as it's written
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			body.write(out);
		}
		catch (IOException e) {
			// The client gave up
		}
		exchange.close();
	}

	/** Sends what's written so far, then waits. */
	private static void pause(OutputStream out, Duration pause) throws IOException {
		out.flush();
		try {
			Thread.sleep(pause.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}

	private interface Body {

		void write(OutputStream out) throws IOException;

	}

}
