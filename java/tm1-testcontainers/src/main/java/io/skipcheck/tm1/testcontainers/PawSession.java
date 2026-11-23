package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A logged-on PAW browser session, over plain HTTP: what {@link PawContainer} uses to
 * tell PAW is ready, and what tests use to call TM1 through PAW.
 *
 * <p>
 * The logon form posts the user name and password to {@code /login/form/}. When PAW has
 * set a {@code ba-sso-csrf} cookie, the form also sends its value in a
 * {@code ba-sso-authenticity} header. PAW Local 2.1.19 sets none on the logon page, and
 * takes the form without it. The session is the {@code paSession} cookie after that.
 *
 * <p>
 * Cookies are kept here rather than by a {@link java.net.CookieManager}, which drops
 * cookies set by a host name without a dot, such as {@code localhost}.
 */
final class PawSession {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final Pattern NAME = Pattern.compile("\"Name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	private final String url;

	private final Map<String, String> cookies = new LinkedHashMap<>();

	private final HttpClient http;

	/**
	 * A session against PAW at {@code url}, not logged on yet.
	 * @param url PAW's address, ending in {@code /}
	 */
	PawSession(String url) {
		this.url = url;
		this.http = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			// PAW's gateway is Apache over plain http: no h2c upgrade
			.version(HttpClient.Version.HTTP_1_1)
			.build();
	}

	/**
	 * Logs on as a user of the TM1 server PAW authenticates against.
	 * @return this session
	 * @throws IOException if PAW doesn't answer, or refuses the logon
	 */
	PawSession login(String username, String password) throws IOException, InterruptedException {
		HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(url + "login")).GET());
		if (page.statusCode() != 200) {
			throw new IOException("PAW's logon page answered " + page.statusCode());
		}
		String form = "mode=basic&username=" + encode(username) + "&password=" + encode(password);
		HttpRequest.Builder post = HttpRequest.newBuilder(URI.create(url + "login/form/"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form));
		String csrf = cookies.get("ba-sso-csrf");
		if (csrf != null) {
			post.header("ba-sso-authenticity", csrf);
		}
		HttpResponse<String> response = send(post);
		if (response.statusCode() != 200 || response.body().contains("error = \"")) {
			throw new IOException("PAW refused the logon of " + username + " (" + response.statusCode() + ")");
		}
		return this;
	}

	/**
	 * The TM1 servers PAW lists from the Admin Server.
	 * @return their names
	 */
	List<String> servers() throws IOException, InterruptedException {
		HttpResponse<String> response = get("tm1/api/v1/Servers");
		if (response.statusCode() != 200) {
			throw new IOException("PAW couldn't list TM1 servers (" + response.statusCode() + "): " + response.body());
		}
		List<String> names = new ArrayList<>();
		Matcher matcher = NAME.matcher(response.body());
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	/**
	 * Calls a TM1 server's REST API through PAW, e.g.
	 * {@code tm1Get("tm1", "Configuration/ProductVersion/$value")}.
	 * @param server the TM1 server's name
	 * @param path the path after {@code /api/v1/}
	 * @return the response
	 */
	HttpResponse<String> tm1Get(String server, String path) throws IOException, InterruptedException {
		return get("tm1/" + encode(server).replace("+", "%20") + "/api/v1/" + path);
	}

	/**
	 * A GET request on PAW, relative to its address.
	 * @param path the path, without a leading {@code /}
	 * @return the response
	 */
	HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return send(HttpRequest.newBuilder(URI.create(url + path)).GET());
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		if (!cookies.isEmpty()) {
			request.header("Cookie",
					cookies.entrySet()
						.stream()
						.map(cookie -> cookie.getKey() + "=" + cookie.getValue())
						.collect(Collectors.joining("; ")));
		}
		HttpResponse<String> response = http.send(request.timeout(REQUEST_TIMEOUT).build(),
				HttpResponse.BodyHandlers.ofString());
		for (String header : response.headers().allValues("Set-Cookie")) {
			for (HttpCookie cookie : HttpCookie.parse(header)) {
				cookies.put(cookie.getName(), cookie.getValue());
			}
		}
		return response;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
