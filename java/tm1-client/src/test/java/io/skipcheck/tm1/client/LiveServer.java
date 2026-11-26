// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.net.ssl.SSLException;

import org.jspecify.annotations.Nullable;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.netty.http.client.HttpClient;

/**
 * The TM1 a live test runs against, as {@link LiveTm1} gives it: one already running,
 * named with {@code TM1_URL} (with {@code TM1_USER} and {@code TM1_PASSWORD}, default
 * {@code admin} and {@code apple}, {@code TM1_NAMESPACE} for CAM and
 * {@code TM1_VERIFY=false} for a self-signed certificate), or else the container
 * tm1-testcontainers starts for the run. As the Python, .NET and TypeScript live tests
 * take it.
 */
public final class LiveServer {

	/** The variable naming a TM1 that is already running. */
	public static final String URL = "TM1_URL";

	private static final Duration WAIT = Duration.ofSeconds(60);

	private final String restUrl;

	private final String username;

	private final String password;

	private final @Nullable String namespace;

	private final boolean verify;

	private final @Nullable Tm1ServerContainer container;

	private @Nullable Tm1Version version;

	private LiveServer(String restUrl, String username, String password, @Nullable String namespace, boolean verify,
			@Nullable Tm1ServerContainer container) {
		this.restUrl = restUrl.endsWith("/") ? restUrl : restUrl + "/";
		this.username = username;
		this.password = password;
		this.namespace = namespace;
		this.verify = verify;
		this.container = container;
		this.version = container == null ? null : container.getTm1Version();
	}

	/** The running TM1 {@code TM1_URL} names, if it's set (not blank). */
	static Optional<LiveServer> running() {
		String url = setting(URL);
		if (url == null) {
			return Optional.empty();
		}
		String user = setting("TM1_USER");
		// Set but empty is a blank password (tm1-server's admin has none), as the other
		// clients
		// read it: only an unset one defaults
		String password = System.getenv("TM1_PASSWORD") != null ? System.getenv("TM1_PASSWORD")
				: System.getProperty("TM1_PASSWORD");
		return Optional.of(new LiveServer(url, user == null ? "admin" : user, password == null ? "apple" : password,
				setting("TM1_NAMESPACE"), !"false".equalsIgnoreCase(setting("TM1_VERIFY")), null));
	}

	/** The container, as a live test's server. */
	static LiveServer of(Tm1ServerContainer container) {
		return new LiveServer(container.getRestUrl(), container.getUsername(), container.getPassword(), null, true,
				container);
	}

	private static @Nullable String setting(String name) {
		String value = System.getenv(name);
		if (value == null) {
			value = System.getProperty(name);
		}
		return value == null || value.isBlank() ? null : value.strip();
	}

	/** The REST API's root, ending in {@code /}: {@code http://host:port/api/v1/}. */
	public String getRestUrl() {
		return restUrl;
	}

	/** Who the tests log in as. */
	public String getUsername() {
		return username;
	}

	/** Their password. */
	public String getPassword() {
		return password;
	}

	/**
	 * The {@code Authorization} header that logs the tests in: {@code Basic}, or
	 * {@code CAMNamespace} with the namespace {@code TM1_NAMESPACE} names, as TM1 takes
	 * it.
	 */
	public String getAuthorization() {
		String credentials = namespace == null ? username + ":" + password
				: username + ":" + password + ":" + namespace;
		return (namespace == null ? "Basic " : "CAMNamespace ")
				+ Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * How the tests log in: basic, or through the CAM namespace {@code TM1_NAMESPACE}
	 * names.
	 */
	public Tm1Authenticator authenticator() {
		return namespace == null ? Tm1Authenticator.basic(username, password)
				: Tm1Authenticator.camNamespace(username, password, namespace);
	}

	/**
	 * A web client for the server: one that trusts any certificate when
	 * {@code TM1_VERIFY} is {@code false}.
	 */
	public WebClient.Builder webClient() {
		WebClient.Builder web = WebClient.builder();
		if (!verify) {
			try {
				var insecure = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
				web.clientConnector(
						new ReactorClientHttpConnector(HttpClient.create().secure(ssl -> ssl.sslContext(insecure))));
			}
			catch (SSLException e) {
				throw new IllegalStateException("Can't make a client that trusts any certificate", e);
			}
		}
		return web;
	}

	/** A client builder for the server: its URL, the login and {@link #webClient()}. */
	public Tm1Client.Builder clientBuilder() {
		return Tm1Client.builder().baseUrl(restUrl).authenticator(authenticator()).webClient(webClient());
	}

	/** The server's product version, read from it the first time for a running one. */
	public synchronized Tm1Version getTm1Version() {
		if (version == null) {
			try (var tm1 = new Closing(clientBuilder().build())) {
				version = tm1.client().session().capabilities().block(WAIT).productVersion().orElseThrow();
			}
		}
		return version;
	}

	/** The container, when the suite started it; empty for a running server. */
	public Optional<Tm1ServerContainer> container() {
		return Optional.ofNullable(container);
	}

	/**
	 * Whether the server's install is writable, as {@link ServerAssumptions#install} has
	 * it.
	 */
	public ServerAssumptions.Install install() {
		return container == null ? ServerAssumptions.Install.UNKNOWN : ServerAssumptions.install(container.getSource());
	}

	/** Where the server is, for messages. */
	@Override
	public String toString() {
		return container == null ? restUrl + " (TM1_URL)" : restUrl + " (" + container.getSource().describe() + ")";
	}

	private record Closing(Tm1Client client) implements AutoCloseable {
		@Override
		public void close() {
			client.close().block(WAIT);
		}
	}

}
