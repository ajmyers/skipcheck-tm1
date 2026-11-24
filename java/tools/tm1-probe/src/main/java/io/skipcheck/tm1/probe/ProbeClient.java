// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The probe's own HTTP client: the JDK's, with TM1's session cookie kept, so nothing of
 * the client under test is in the way of what the server says.
 */
public final class ProbeClient implements AutoCloseable {

	/** The most requests TM1 takes in one {@code $batch}. */
	public static final int BATCH_LIMIT = 1000;

	static final JsonMapper JSON = JsonMapper.builder().build();

	private final String root;

	private final String authorization;

	private final HttpClient http;

	/**
	 * A client for one server.
	 * @param restUrl the REST root, such as {@code http://localhost:8010/api/v1/}
	 * @param authorization the {@code Authorization} header to log in with
	 */
	public ProbeClient(String restUrl, String authorization) {
		this.root = restUrl.endsWith("/") ? restUrl : restUrl + "/";
		this.authorization = Objects.requireNonNull(authorization, "authorization");
		this.http = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	/**
	 * An answer: its status and its JSON body, or null when there's none.
	 *
	 * @param status the HTTP status
	 * @param body the body, or null
	 */
	public record Answer(int status, @Nullable JsonNode body) {

		/**
		 * Whether the status is 2xx.
		 * @return whether it succeeded
		 */
		public boolean ok() {
			return status >= 200 && status < 300;
		}

	}

	/**
	 * Sends a request.
	 * @param method the HTTP method
	 * @param path the path from the REST root, such as {@code Cubes('c')}
	 * @param body the JSON body, or null
	 * @return the answer
	 */
	public Answer send(String method, String path, @Nullable JsonNode body) {
		HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(root + encode(path)))
			.method(method, publisher)
			.header("Authorization", authorization)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.timeout(Duration.ofMinutes(5))
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			String text = response.body();
			JsonNode json = null;
			if (text != null && !text.isBlank()) {
				try {
					json = JSON.readTree(text);
				}
				catch (RuntimeException notJson) {
					json = JSON.getNodeFactory().stringNode(text);
				}
			}
			return new Answer(response.statusCode(), json);
		}
		catch (IOException e) {
			throw new UncheckedIOException(method + " " + path, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted: " + method + " " + path, e);
		}
	}

	/**
	 * Reads a resource that isn't JSON, such as {@code $metadata}.
	 * @param path the path from the REST root
	 * @return the body
	 * @throws IllegalStateException if the server doesn't answer 200
	 */
	public String text(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(root + encode(path)))
			.header("Authorization", authorization)
			.timeout(Duration.ofMinutes(5))
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("GET " + path + " answered " + response.statusCode());
			}
			return response.body();
		}
		catch (IOException e) {
			throw new UncheckedIOException("GET " + path, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted: GET " + path, e);
		}
	}

	/**
	 * Sends a GET.
	 * @param path the path from the REST root
	 * @return the answer
	 */
	public Answer get(String path) {
		return send("GET", path, null);
	}

	/**
	 * Sends GETs in one JSON {@code $batch}, which TM1 runs in order.
	 * @param paths at most {@value #BATCH_LIMIT} paths from the REST root
	 * @return each one's status, in the order of the paths
	 */
	public List<Integer> batchGetStatuses(List<String> paths) {
		if (paths.size() > BATCH_LIMIT) {
			throw new IllegalArgumentException("TM1 takes at most " + BATCH_LIMIT + " requests in a batch");
		}
		ObjectNode body = JSON.createObjectNode();
		ArrayNode requests = body.putArray("requests");
		for (int i = 0; i < paths.size(); i++) {
			requests.addObject().put("id", Integer.toString(i)).put("method", "GET").put("url", paths.get(i));
		}
		Answer answer = send("POST", "$batch", body);
		JsonNode responses = answer.body() == null ? null : answer.body().get("responses");
		if (!answer.ok() || responses == null) {
			throw new IllegalStateException("$batch failed with " + answer.status() + ": " + answer.body());
		}
		List<Integer> statuses = new ArrayList<>(Collections.nCopies(paths.size(), 0));
		for (JsonNode response : responses) {
			statuses.set(Integer.parseInt(response.path("id").asString()), response.path("status").asInt());
		}
		return statuses;
	}

	/** Ends the TM1 session. */
	@Override
	public void close() {
		send("POST", "ActiveSession/tm1.Close", null);
	}

	/**
	 * Encodes what a URI can't hold as is (spaces and the like), keeping OData's syntax.
	 */
	static String encode(String path) {
		StringBuilder out = new StringBuilder();
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if (c > 0x20 && c < 0x7f && "\"<>\\^`{|}".indexOf(c) < 0) {
				out.append((char) c);
			}
			else {
				out.append('%')
					.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			}
		}
		return out.toString();
	}

}
