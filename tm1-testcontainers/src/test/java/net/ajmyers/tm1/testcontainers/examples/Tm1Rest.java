package net.ajmyers.tm1.testcontainers.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * A minimal TM1 REST session for the examples, standing in for tm1-client until it
 * exists. In your own tests you'd build a {@code Tm1Client} from
 * {@link Tm1ServerContainer#getRestUrl()} and the container's login instead.
 *
 * <p>
 * It logs in once, reuses the session cookie, and closes the session when done, since TM1
 * opens a new session for every request that doesn't send one back.
 */
final class Tm1Rest implements AutoCloseable {

	private static final Pattern COUNT = Pattern.compile("^\\d+$");

	private final HttpClient http = HttpClient.newHttpClient();

	private final String baseUrl;

	private final String authorization;

	private String cookie;

	Tm1Rest(Tm1ServerContainer tm1) {
		this(tm1, tm1.getAuthorization());
	}

	/**
	 * A session with its own login, e.g. a Cognos user's
	 * {@code CognosContainer.camAuthorization}.
	 */
	Tm1Rest(Tm1ServerContainer tm1, String authorization) {
		this.baseUrl = tm1.getRestUrl();
		this.authorization = authorization;
	}

	/**
	 * GETs a path under the service root and returns the body. Fails on anything but 200.
	 */
	String get(String path) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
		request.header(cookie == null ? "Authorization" : "Cookie", cookie == null ? authorization : cookie);
		HttpResponse<String> response = send(request.build());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(
					"GET " + path + " returned " + response.statusCode() + ": " + response.body());
		}
		if (cookie == null) {
			cookie = response.headers()
				.allValues("Set-Cookie")
				.stream()
				.map(c -> c.split(";", 2)[0].strip())
				.filter(c -> c.startsWith("TM1SessionId="))
				.findFirst()
				.orElse(null);
		}
		return response.body();
	}

	/**
	 * POSTs a JSON body to a path under the service root and returns the response,
	 * whatever its status.
	 */
	HttpResponse<String> post(String path, String json) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json));
		if (cookie == null) {
			get("ActiveUser?$select=Name"); // logs in and keeps the session cookie
		}
		return send(request.header("Cookie", cookie).build());
	}

	/** The number of cubes, excluding control cubes (whose names start with '}'). */
	int modelCubeCount() {
		String count = get("Cubes/$count?$filter=not%20startswith(Name,'%7D')");
		Matcher m = COUNT.matcher(count.strip());
		if (!m.matches()) {
			throw new IllegalStateException("Not a count: " + count);
		}
		return Integer.parseInt(count.strip());
	}

	@Override
	public void close() {
		if (cookie != null) {
			send(HttpRequest.newBuilder(URI.create(baseUrl + "ActiveSession/tm1.Close"))
				.header("Cookie", cookie)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build());
		}
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
