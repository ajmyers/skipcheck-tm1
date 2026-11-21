package net.ajmyers.tm1.client.session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bare HTTP to TM1, for tests that pin how TM1 itself behaves, independent of the client.
 *
 * <p>
 * Uses the JDK client with no cookie handler, so a request carries exactly the
 * credentials and cookie it's given. That matters: a request with Basic credentials and
 * no cookie silently opens a new TM1 session, which would hide what a cookie-only request
 * does.
 */
final class RawTm1 {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String COOKIE = "TM1SessionId";

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final String root;

	private final String basic;

	RawTm1(Tm1ServerContainer tm1) {
		this.root = tm1.getRestUrl();
		this.basic = "Basic " + Base64.getEncoder()
			.encodeToString((tm1.getUsername() + ":" + tm1.getPassword()).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Logs in with Basic credentials and no cookie, and returns the new session's cookie.
	 */
	String login() {
		return cookie(send(request("GET", "ActiveSession?$select=ID").basic())).orElseThrow();
	}

	/** The ID of the session behind a cookie. */
	long sessionId(String cookie) {
		return json(send(request("GET", "ActiveSession?$select=ID").cookie(cookie))).get("ID").asLong();
	}

	/** Closes the session behind a cookie. */
	void close(String cookie) {
		send(request("POST", "ActiveSession/tm1.Close").cookie(cookie).body("{}"));
	}

	/**
	 * Kills another session by ID, through {@code Sessions(id)/tm1.Close}, from
	 * {@code admin}'s session.
	 */
	int kill(String admin, long id) {
		return send(request("POST", "Sessions(" + id + ")/tm1.Close").cookie(admin).body("{}")).statusCode();
	}

	/**
	 * Waits until session {@code id} is no longer listed, since TM1 takes a few seconds
	 * to drop a closed session. Whether it went within {@code timeout}.
	 */
	boolean awaitGone(String admin, long id, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (send(request("GET", "Sessions(" + id + ")?$select=ID").cookie(admin)).statusCode() == 200) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			pause();
		}
		return true;
	}

	/**
	 * Waits until TM1 lists {@code expected} sessions, and returns the last count seen.
	 */
	int awaitSessionCount(String admin, int expected, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		int count = sessionCount(admin);
		while (count != expected && System.nanoTime() < deadline) {
			pause();
			count = sessionCount(admin);
		}
		return count;
	}

	private static void pause() {
		try {
			Thread.sleep(250);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Creates a TM1 user with no groups (so not an administrator), from {@code admin}'s
	 * session.
	 */
	void createUser(String admin, String name, String password) {
		HttpResponse<String> created = send(request("POST", "Users").cookie(admin)
			.body("{\"Name\":\"" + name + "\",\"Password\":\"" + password + "\"}"));
		if (created.statusCode() != 201) {
			throw new IllegalStateException(
					"Couldn't create user " + name + ": " + created.statusCode() + " " + created.body());
		}
	}

	/** Deletes a TM1 user, if it exists. */
	void deleteUser(String admin, String name) {
		send(request("DELETE", "Users('" + name + "')").cookie(admin));
	}

	/** {@code Basic} credentials for a user. */
	static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	/** How many sessions TM1 has open, seen from {@code admin}'s session. */
	int sessionCount(String admin) {
		return json(send(request("GET", "Sessions?$select=ID").cookie(admin))).get("value").size();
	}

	Request request(String method, String path) {
		return new Request(method, path);
	}

	HttpResponse<String> send(Request request) {
		try {
			return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new IllegalStateException(request + " failed", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(request + " interrupted", e);
		}
	}

	CompletableFuture<HttpResponse<String>> sendAsync(Request request) {
		return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** The {@code TM1SessionId} cookie a response sets, as {@code name=value}. */
	static Optional<String> cookie(HttpResponse<?> response) {
		return response.headers()
			.allValues("set-cookie")
			.stream()
			.filter(c -> c.startsWith(COOKIE + "="))
			.map(c -> c.split(";", 2)[0])
			.findFirst();
	}

	static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	/**
	 * One request: method, path relative to the service root, and optional credentials,
	 * cookie, headers and JSON body.
	 */
	final class Request {

		private final String method;

		private final String path;

		private final Map<String, String> headers = new LinkedHashMap<>();

		private String body;

		private Request(String method, String path) {
			this.method = method;
			this.path = path;
		}

		Request basic() {
			return header("Authorization", basic);
		}

		Request cookie(String cookie) {
			return header("Cookie", cookie);
		}

		Request header(String name, String value) {
			headers.put(name, value);
			return this;
		}

		Request body(String json) {
			this.body = json;
			return header("Content-Type", "application/json");
		}

		private HttpRequest build() {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + path));
			headers.forEach(builder::header);
			return builder
				.method(method,
						body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
				.build();
		}

		@Override
		public String toString() {
			return method + " " + path;
		}

	}

}
