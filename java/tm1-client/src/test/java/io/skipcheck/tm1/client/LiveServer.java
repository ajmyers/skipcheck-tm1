// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import org.jspecify.annotations.Nullable;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

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

	/** The trusting connector's pool: idle connections closed before TM1's 90 seconds. */
	private static final ConnectionProvider TRUSTING_CONNECTIONS = ConnectionProvider.builder("live-tm1-trusting")
		.maxIdleTime(Duration.ofSeconds(60))
		.build();

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

	/**
	 * The running TM1 {@code TM1_URL} names, if it's set (not blank): one for the run, so
	 * its version is read once.
	 */
	static Optional<LiveServer> running() {
		return Running.SERVER;
	}

	/** Read the first time it's asked for, once for the run. */
	private static final class Running {

		static final Optional<LiveServer> SERVER = running(LiveServer::lookup);

	}

	/**
	 * The running TM1 the settings name, read through {@code lookup} (a variable's value,
	 * or null): the URL, with a blank one taken as unset; the user, {@code admin} unless
	 * set; the password, {@code apple} unless set, an empty one staying empty
	 * (tm1-server's admin has none), as the other clients read it; the CAM namespace; and
	 * whether to check the certificate, unless {@code TM1_VERIFY} is {@code false}.
	 */
	static Optional<LiveServer> running(UnaryOperator<@Nullable String> lookup) {
		String url = blankIsNull(lookup.apply(URL));
		if (url == null) {
			return Optional.empty();
		}
		String user = blankIsNull(lookup.apply("TM1_USER"));
		String password = lookup.apply("TM1_PASSWORD");
		return Optional.of(new LiveServer(url, user == null ? "admin" : user, password == null ? "apple" : password,
				blankIsNull(lookup.apply("TM1_NAMESPACE")),
				!"false".equalsIgnoreCase(blankIsNull(lookup.apply("TM1_VERIFY"))), null));
	}

	/** The container, as a live test's server. */
	static LiveServer of(Tm1ServerContainer container) {
		return new LiveServer(container.getRestUrl(), container.getUsername(), container.getPassword(), null, true,
				container);
	}

	private static @Nullable String lookup(String name) {
		String value = System.getenv(name);
		return value != null ? value : System.getProperty(name);
	}

	private static @Nullable String blankIsNull(@Nullable String value) {
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
	 * The client builder with a connector that trusts any certificate when
	 * {@code TM1_VERIFY} is {@code false}, as the session's own would be otherwise (gzip,
	 * a connect timeout, idle connections closed before TM1's 90 seconds). Through
	 * {@code connector}, not a web client: the session sets its connector on any web
	 * client it's given.
	 */
	public Tm1Client.Builder trusting(Tm1Client.Builder client) {
		return verify ? client : client.connector(trustingConnector());
	}

	/** The session builder, as {@link #trusting(Tm1Client.Builder)} has it. */
	public Tm1Session.Builder trusting(Tm1Session.Builder session) {
		return verify ? session : session.connector(trustingConnector());
	}

	private static ClientHttpConnector trustingConnector() {
		return new ReactorClientHttpConnector(HttpClient.create(TRUSTING_CONNECTIONS)
			.compress(true)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
			.secure(ssl -> ssl.sslContext(insecure())));
	}

	/**
	 * A session builder for the server: its URL, the login, and trust as
	 * {@code TM1_VERIFY} says.
	 */
	public Tm1Session.Builder sessionBuilder() {
		return trusting(Tm1Session.builder().baseUrl(restUrl).authenticator(authenticator()));
	}

	/**
	 * A JDK HTTP client builder for the server: one that trusts any certificate when
	 * {@code TM1_VERIFY} is {@code false}.
	 */
	public java.net.http.HttpClient.Builder httpClient() {
		java.net.http.HttpClient.Builder http = java.net.http.HttpClient.newBuilder();
		if (!verify) {
			http.sslContext(trustingContext());
		}
		return http;
	}

	/**
	 * A Reactor Netty client for the server, as given, but trusting any certificate when
	 * {@code TM1_VERIFY} is {@code false}.
	 */
	public HttpClient secure(HttpClient http) {
		return verify ? http : http.secure(ssl -> ssl.sslContext(insecure()));
	}

	/**
	 * Whether the server's certificate is checked ({@code TM1_VERIFY} isn't
	 * {@code false}).
	 */
	public boolean verifies() {
		return verify;
	}

	/** An SSL context that trusts any certificate, for {@code TM1_VERIFY=false}. */
	public static SSLContext trustingContext() {
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
			return context;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Can't make an SSL context that trusts any certificate", e);
		}
	}

	private static SslContext insecure() {
		try {
			return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		}
		catch (SSLException e) {
			throw new IllegalStateException("Can't make a client that trusts any certificate", e);
		}
	}

	/**
	 * A client builder for the server: its URL, the login, and trust as
	 * {@code TM1_VERIFY} says.
	 */
	public Tm1Client.Builder clientBuilder() {
		return trusting(Tm1Client.builder().baseUrl(restUrl).authenticator(authenticator()));
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
