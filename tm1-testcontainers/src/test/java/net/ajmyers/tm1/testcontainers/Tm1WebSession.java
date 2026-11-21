package net.ajmyers.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives TM1 Web the way its login page does, for tests: TM1 Web's browser API is DWR
 * (Direct Web Remoting) over HTTP, under {@code /tm1web/dwr/}.
 *
 * <p>
 * Test code only, and deliberately small. DWR is TM1 Web's internal browser protocol, not
 * a published API, so this does just enough to prove TM1 Web can list and log on to a TM1
 * server. DWR rejects calls (as a CSRF error) unless they carry the HTTP session ID and a
 * DWR script session, so it does the handshake the browser does first.
 */
public final class Tm1WebSession {

	// One line per callback: "." stops at the line end, so the match ends at its ");"
	private static final Pattern CALLBACK = Pattern.compile("handleCallback\\(\"\\d+\",\"0\",(.*)\\);");

	private static final Pattern ERROR = Pattern.compile("handle(?:Batch)?Exception\\((.*?)\\);", Pattern.DOTALL);

	private static final Pattern NAME = Pattern.compile("name:\"([^\"]*)\"");

	private static final Pattern SESSION_ID = Pattern.compile("tm1SessionId:\"([^\"]*)\"");

	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

	private final HttpClient http = HttpClient.newBuilder()
		.cookieHandler(cookies)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final URI base;

	private String scriptSessionId = "";

	private int batch;

	/**
	 * Opens an HTTP session and a DWR script session with TM1 Web.
	 * @param web the running TM1 Web
	 */
	public Tm1WebSession(Tm1WebContainer web) {
		this.base = URI.create(web.getUrl());
		send(HttpRequest.newBuilder(base).GET().build());
		String id = string(call("__System", "generateId"));
		HttpCookie cookie = new HttpCookie("DWRSESSIONID", id);
		cookie.setPath(base.getPath().replaceAll("/$", ""));
		cookie.setVersion(0);
		cookies.getCookieStore().add(base, cookie);
		this.scriptSessionId = id + "/" + UUID.randomUUID();
	}

	/**
	 * The TM1 servers TM1 Web lists from an Admin Server, as on its login page.
	 * @param adminHost the Admin Server host
	 * @return the servers' names
	 */
	public List<String> servers(String adminHost) {
		String result = call("TM1Service", "getTM1Servers", "string:" + adminHost);
		List<String> names = new ArrayList<>();
		Matcher matcher = NAME.matcher(result);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	/**
	 * Logs on to a TM1 server through TM1 Web, as its login page does.
	 * @param adminHost the Admin Server host
	 * @param server the TM1 server's name
	 * @param username the TM1 user name
	 * @param password the password
	 * @return the TM1 session ID TM1 Web opened on the server
	 * @throws IllegalStateException if TM1 Web refuses the login
	 */
	public String login(String adminHost, String server, String username, String password) {
		String result = call("TM1Service", "login", "string:" + adminHost, "string:" + server, "string:" + username,
				"string:" + password);
		Matcher matcher = SESSION_ID.matcher(result);
		if (!matcher.find()) {
			throw new IllegalStateException("TM1 Web logged on but sent no TM1 session ID: " + result);
		}
		return matcher.group(1);
	}

	private String call(String script, String method, String... params) {
		batch++;
		StringBuilder body = new StringBuilder();
		body.append("callCount=1\nnextReverseAjaxIndex=0\n");
		body.append("c0-scriptName=").append(script).append("\nc0-methodName=").append(method).append("\nc0-id=0\n");
		for (int i = 0; i < params.length; i++) {
			body.append("c0-param").append(i).append('=').append(params[i]).append('\n');
		}
		body.append("batchId=").append(batch).append("\ninstanceId=0\npage=").append(base.getPath()).append('\n');
		body.append("httpSessionId=").append(cookie("JSESSIONID")).append('\n');
		body.append("scriptSessionId=").append(scriptSessionId).append('\n');
		HttpResponse<String> response = send(
				HttpRequest.newBuilder(base.resolve("dwr/call/plaincall/" + script + "." + method + ".dwr"))
					.header("Content-Type", "text/plain")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
					.build());
		Matcher result = CALLBACK.matcher(response.body());
		if (response.statusCode() == 200 && result.find()) {
			return result.group(1);
		}
		Matcher error = ERROR.matcher(response.body());
		throw new IllegalStateException(script + "." + method + " failed (HTTP " + response.statusCode() + "): "
				+ (error.find() ? error.group(1) : response.body()));
	}

	private String cookie(String name) {
		return cookies.getCookieStore()
			.get(base)
			.stream()
			.filter(cookie -> cookie.getName().equals(name))
			.map(HttpCookie::getValue)
			.findFirst()
			.orElse("");
	}

	private static String string(String literal) {
		return literal.strip().replaceAll("^\"|\"$", "");
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
