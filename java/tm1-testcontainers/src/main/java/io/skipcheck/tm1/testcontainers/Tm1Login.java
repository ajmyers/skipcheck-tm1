// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A user's login to a TM1 v12 instance, through one of the instance's applications
 * ("service-to-service"): TM1 v12 has no passwords of its own, so the application's
 * client ID and secret vouch for the user's name.
 *
 * <p>
 * A login belongs to an <b>instance</b>: its session serves every database there. Whether
 * the user may do anything in a database depends on the user existing in it.
 *
 * <p>
 * Get one from {@link Tm1Database#login()} or {@link Tm1Database#login(String)}, then
 * either let it log in for you ({@link #authorize} or {@link #cookie()}), or hand
 * {@link #getClientId()}, {@link #getClientSecret()} and {@link #getUser()} to a client
 * that logs in itself.
 */
public final class Tm1Login {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

	private final String authUrl;

	private final String instance;

	private final String clientId;

	private final String clientSecret;

	private final String user;

	private @Nullable String cookie;

	Tm1Login(String authUrl, String instance, String clientId, String clientSecret, String user) {
		this.authUrl = authUrl;
		this.instance = instance;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.user = Objects.requireNonNull(user, "user");
	}

	/**
	 * The instance this login is for.
	 * @return the instance's name
	 */
	public String getInstance() {
		return instance;
	}

	/**
	 * The TM1 user this logs in as.
	 * @return the user's name
	 */
	public String getUser() {
		return user;
	}

	/**
	 * The application's client ID.
	 * @return the client ID
	 */
	public String getClientId() {
		return clientId;
	}

	/**
	 * The application's client secret.
	 * @return the client secret
	 */
	public String getClientSecret() {
		return clientSecret;
	}

	/**
	 * Where a session or a token is asked for, as seen from the host, e.g.
	 * {@code http://localhost:54321/tm1/auth/v1/}.
	 * @return the instance's authentication root, ending in {@code /}
	 */
	public String getAuthUrl() {
		return authUrl;
	}

	/**
	 * The session cookie, {@code TM1SessionId=...}, for a {@code Cookie} header. Logs in
	 * the first time, with the application's credentials as Basic authentication, and
	 * keeps the session.
	 * @return the cookie, name and value
	 * @throws IllegalStateException if TM1 refuses the login
	 */
	public synchronized String cookie() {
		if (cookie == null) {
			String basic = Base64.getEncoder()
				.encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
			cookie = session("Basic " + basic);
		}
		return cookie;
	}

	/**
	 * A bearer token for the application, from TM1's token endpoint (the OAuth 2.0
	 * client-credentials grant). It names no user: send it as
	 * {@code Authorization: Bearer} with the user's name to get a session, as
	 * {@link #session(String)} does.
	 * @return the access token, a JWT
	 * @throws IllegalStateException if TM1 refuses the application's credentials
	 */
	public String token() {
		String body = "{\"grant_type\":\"client_credentials\",\"client_id\":\"" + json(clientId)
				+ "\",\"client_secret\":\"" + json(clientSecret) + "\"}";
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(authUrl + "token"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)));
		Matcher token = TOKEN.matcher(response.body());
		if (response.statusCode() / 100 != 2 || !token.find()) {
			throw new IllegalStateException("TM1 gave no token for the application of instance '" + instance
					+ "' (HTTP " + response.statusCode() + "): " + response.body());
		}
		return token.group(1);
	}

	/**
	 * Logs the user in with an {@code Authorization} header of your own, such as
	 * {@code "Bearer " + token()}, and returns the new session's cookie. Each call makes
	 * a session; {@link #cookie()} keeps one.
	 * @param authorization the {@code Authorization} header's value
	 * @return the session cookie, {@code TM1SessionId=...}
	 * @throws IllegalStateException if TM1 refuses the login
	 */
	public String session(String authorization) {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(authUrl + "session"))
			.header("Authorization", Objects.requireNonNull(authorization, "authorization"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"User\":\"" + json(user) + "\"}")));
		String found = response.headers()
			.allValues("Set-Cookie")
			.stream()
			.map(value -> value.split(";", 2)[0].strip())
			.filter(value -> value.startsWith("TM1SessionId="))
			.findFirst()
			.orElse(null);
		if (response.statusCode() / 100 != 2 || found == null) {
			throw new IllegalStateException("TM1 refused the login of '" + user + "' to instance '" + instance
					+ "' (HTTP " + response.statusCode() + "): " + response.body());
		}
		return found;
	}

	/**
	 * Adds this login's session cookie to a request.
	 * @param request the request being built
	 * @return the same builder
	 */
	public HttpRequest.Builder authorize(HttpRequest.Builder request) {
		return request.header("Cookie", cookie());
	}

	@Override
	public String toString() {
		// Never the secret
		return "Tm1Login[" + user + " in " + instance + "]";
	}

	private HttpResponse<String> send(HttpRequest.Builder request) {
		try {
			return HttpClient.newBuilder()
				.connectTimeout(REQUEST_TIMEOUT)
				.build()
				.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't reach TM1 at " + authUrl, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted logging in to TM1", e);
		}
	}

	private static String json(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
