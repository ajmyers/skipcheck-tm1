// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSocketFactory;

import io.skipcheck.tm1.client.LiveServer;
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

	private final HttpClient http;

	private final boolean verify;

	private final String root;

	private final String basic;

	RawTm1(LiveServer tm1) {
		this.root = tm1.getRestUrl();
		this.basic = tm1.getAuthorization();
		this.http = tm1.httpClient().version(HttpClient.Version.HTTP_1_1).build();
		this.verify = tm1.verifies();
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
	 * Waits until TM1 lists {@code expected} sessions labelled {@code context}, and
	 * returns the last count seen.
	 */
	int awaitSessionCount(String admin, String context, int expected, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		int count = sessionCount(admin, context);
		while (count != expected && System.nanoTime() < deadline) {
			pause();
			count = sessionCount(admin, context);
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

	/**
	 * A GET over a bare socket, with one header whose value goes out as exactly
	 * {@code value}'s bytes. The JDK's {@code HttpClient} writes a character outside
	 * ASCII as {@code ?}, so it can't show what TM1 does with one.
	 * @return the status, and the whole response as text
	 */
	Bytes getWithHeaderBytes(String path, String authorization, String header, byte[] value) {
		URI uri = URI.create(root + path);
		boolean tls = "https".equalsIgnoreCase(uri.getScheme());
		int port = uri.getPort() >= 0 ? uri.getPort() : tls ? 443 : 80;
		String head = "GET " + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
				+ " HTTP/1.1\r\nHost: " + uri.getHost() + ":" + port + "\r\nAuthorization: " + authorization
				+ "\r\nAccept: application/json\r\nConnection: close\r\n" + header + ": ";
		try (Socket socket = socket(uri.getHost(), port, tls)) {
			socket.setSoTimeout(30_000);
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(value);
			out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String text = new String(oneResponse(socket.getInputStream()), StandardCharsets.UTF_8);
			return new Bytes(Integer.parseInt(text.substring(9, 12)), text);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * A socket to the server, over TLS for an https URL, trusting any certificate when
	 * TM1_VERIFY is false.
	 */
	private Socket socket(String host, int port, boolean tls) throws IOException {
		if (!tls) {
			return new Socket(host, port);
		}
		SSLSocketFactory factory = verify ? (SSLSocketFactory) SSLSocketFactory.getDefault()
				: LiveServer.trustingContext().getSocketFactory();
		return factory.createSocket(host, port);
	}

	/**
	 * Reads one response and no further: TM1 keeps the connection open whatever the
	 * request's {@code Connection} header says, so reading to the end would wait forever.
	 */
	private static byte[] oneResponse(InputStream in) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (true) {
			String soFar = bytes.toString(StandardCharsets.ISO_8859_1);
			int headEnd = soFar.indexOf("\r\n\r\n");
			if (headEnd >= 0) {
				String head = soFar.substring(0, headEnd).toLowerCase(Locale.ROOT);
				int length = head.indexOf("content-length:");
				boolean done = head.contains("transfer-encoding: chunked") ? soFar.endsWith("0\r\n\r\n")
						: length < 0 || bytes.size() >= headEnd + 4
								+ Integer.parseInt(head.substring(length + 15).split("\r\n", 2)[0].strip());
				if (done) {
					return bytes.toByteArray();
				}
			}
			int read = in.read(buffer);
			if (read < 0) {
				return bytes.toByteArray();
			}
			bytes.write(buffer, 0, read);
		}
	}

	/**
	 * What {@link #getWithHeaderBytes} got: the status, and the whole response as text.
	 */
	record Bytes(int status, String text) {

		/** The session cookie TM1 set, if it opened a session. */
		Optional<String> cookie() {
			int at = text.indexOf(COOKIE + "=");
			return at < 0 ? Optional.empty() : Optional.of(text.substring(at, text.indexOf(';', at)));
		}

	}

	/** {@code Basic} credentials for a user. */
	static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * How many sessions labelled {@code context} (their {@code TM1-SessionContext}) TM1
	 * has open, seen from {@code admin}'s session. Leak checks count a label unique to
	 * the test: the server is shared, so the total moves for reasons the test doesn't
	 * control (closed sessions TM1 lists for a few seconds more, sessions every 401 and
	 * anonymous read opens).
	 */
	int sessionCount(String admin, String context) {
		int count = 0;
		for (JsonNode session : json(send(request("GET", "Sessions?$select=ID,Context").cookie(admin))).get("value")) {
			if (context.equals(session.path("Context").asString(""))) {
				count++;
			}
		}
		return count;
	}

	/** A label unique to one test, for {@link #sessionCount(String, String)}. */
	static String uniqueContext() {
		return "test-" + UUID.randomUUID();
	}

	/**
	 * How many sessions TM1 has open, those with no user included, for tests about those.
	 */
	int allSessionCount(String admin) {
		return json(send(request("GET", "Sessions?$select=ID").cookie(admin))).get("value").size();
	}

	Request request(String method, String path) {
		return new Request(method, path);
	}

	/**
	 * Sends a request. A GET or DELETE whose reused connection turns out to be closed,
	 * with no response at all, is sent once more: TM1 now and then closes a kept-alive
	 * connection, and the JDK client already retries a GET that way, but not other
	 * methods.
	 */
	HttpResponse<String> send(Request request) {
		try {
			return sendOnce(request);
		}
		catch (IllegalStateException e) {
			boolean noResponse = e.getCause() instanceof IOException io
					&& String.valueOf(io.getMessage()).contains("received no bytes");
			if (noResponse && (request.method.equals("GET") || request.method.equals("DELETE"))) {
				return sendOnce(request);
			}
			throw e;
		}
	}

	private HttpResponse<String> sendOnce(Request request) {
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
