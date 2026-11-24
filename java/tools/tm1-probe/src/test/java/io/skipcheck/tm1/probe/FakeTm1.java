// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stand-in for TM1's REST API: answers each GET (path and decoded query, from the REST
 * root) with what a function gives, and runs a JSON {@code $batch} of GETs through the
 * same function.
 */
final class FakeTm1 implements AutoCloseable {

	/** An answer: a status and a JSON body, or null for none. */
	record Reply(int status, JsonNode body) {

		static Reply ok(String json) {
			return new Reply(200, ProbeClient.JSON.readTree(json));
		}

		static Reply refused() {
			return new Reply(400, ProbeClient.JSON.readTree("{\"error\": {\"code\": \"278\"}}"));
		}

	}

	private final HttpServer server;

	final AtomicInteger batches = new AtomicInteger();

	FakeTm1(Function<String, Reply> gets) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 0);
		server.createContext("/api/v1/", exchange -> {
			String target = URLDecoder.decode(exchange.getRequestURI().getRawPath().substring("/api/v1/".length())
					+ (exchange.getRequestURI().getRawQuery() == null ? ""
							: "?" + exchange.getRequestURI().getRawQuery()),
					StandardCharsets.UTF_8);
			if (exchange.getRequestMethod().equals("POST") && target.equals("$batch")) {
				batches.incrementAndGet();
				JsonNode body = ProbeClient.JSON.readTree(exchange.getRequestBody());
				ObjectNode out = ProbeClient.JSON.createObjectNode();
				ArrayNode responses = out.putArray("responses");
				for (JsonNode request : body.path("requests")) {
					Reply reply = gets.apply(request.path("url").asString());
					responses.addObject().put("id", request.path("id").asString()).put("status", reply.status());
				}
				send(exchange, new Reply(200, out));
			}
			else if (exchange.getRequestMethod().equals("GET")) {
				send(exchange, gets.apply(target));
			}
			else {
				send(exchange, new Reply(204, null));
			}
		});
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/";
	}

	private static void send(HttpExchange exchange, Reply reply) throws IOException {
		byte[] bytes = reply.body() == null ? new byte[0]
				: ProbeClient.JSON.writeValueAsString(reply.body()).getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
