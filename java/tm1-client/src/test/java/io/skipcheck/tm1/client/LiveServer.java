// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import java.net.http.HttpRequest;
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
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1Login;
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

	private final Tm1Dialect dialect;

	private final @Nullable Tm1ServerContainer container;

	private final @Nullable Tm1EngineContainer engine;

	private final @Nullable Tm1Database database;

	private @Nullable Tm1Version version;

	private LiveServer(String restUrl, String username, String password, @Nullable String namespace, boolean verify,
			Tm1Dialect dialect, @Nullable Tm1ServerContainer container, @Nullable Tm1EngineContainer engine,
			@Nullable Tm1Database database) {
		this.restUrl = restUrl.endsWith("/") ? restUrl : restUrl + "/";
		this.username = username;
		this.password = password;
		this.namespace = namespace;
		this.verify = verify;
		this.dialect = dialect;
		this.container = container;
		this.engine = engine;
		this.database = database;
		this.version = container != null ? container.getTm1Version() : database != null ? database.getVersion() : null;
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
				!"false".equalsIgnoreCase(blankIsNull(lookup.apply("TM1_VERIFY"))), Tm1Dialect.V11, null, null, null));
	}

	/** The TM1 11 container, as a live test's server. */
	static LiveServer of(Tm1ServerContainer container) {
		return new LiveServer(container.getRestUrl(), container.getUsername(), container.getPassword(), null, true,
				Tm1Dialect.V11, container, null, null);
	}

	/** The TM1 v12 engine container, as a live test's server. */
	static LiveServer of(Tm1EngineContainer engine) {
		Tm1Database db = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);
		return new LiveServer(db.getRestUrl(), db.login().getUser(), "", null, true,
				Tm1Dialect.v12(db.getInstance(), db.getName()), null, engine, db);
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
	 * How the tests log in: basic, through the CAM namespace {@code TM1_NAMESPACE} names,
	 * or through the v12 instance's application.
	 */
	public Tm1Authenticator authenticator() {
		if (database != null) {
			Tm1Login login = database.login();
			return Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), login.getUser());
		}
		return namespace == null ? Tm1Authenticator.basic(username, password)
				: Tm1Authenticator.camNamespace(username, password, namespace);
	}

	/**
	 * How a named user logs in: basic on TM1 11, or through the v12 instance's
	 * application on TM1 v12.
	 */
	public Tm1Authenticator authenticator(String user, String password) {
		if (database != null) {
			Tm1Login login = database.login(user);
			return Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), user);
		}
		return Tm1Authenticator.basic(user, password);
	}

	/** Adds this server's authentication to a raw JDK HTTP request. */
	public HttpRequest.Builder authorize(HttpRequest.Builder request) {
		if (database != null) {
			return database.login().authorize(request);
		}
		return request.header("Authorization", getAuthorization());
	}

	/** The TM1 dialect for this server (v11 or v12). */
	public Tm1Dialect dialect() {
		return dialect;
	}

	/** Whether this server is a TM1 v12 engine container. */
	public boolean isEngine() {
		return engine != null;
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
		return trusting(Tm1Session.builder().baseUrl(restUrl).dialect(dialect).authenticator(authenticator()));
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
		return trusting(Tm1Client.builder().baseUrl(restUrl).dialect(dialect).authenticator(authenticator()));
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

	/** The TM1 11 container, when the suite started one; empty otherwise. */
	public Optional<Tm1ServerContainer> container() {
		return Optional.ofNullable(container);
	}

	/** The TM1 v12 engine container, when the suite started one; empty otherwise. */
	public Optional<Tm1EngineContainer> engine() {
		return Optional.ofNullable(engine);
	}

	/** The TM1 v12 database, when the suite started an engine; empty otherwise. */
	public Optional<Tm1Database> database() {
		return Optional.ofNullable(database);
	}

	/**
	 * Whether the server's install is writable, as {@link ServerAssumptions#install} has
	 * it.
	 */
	public ServerAssumptions.Install install() {
		if (container != null) {
			return ServerAssumptions.install(container.getSource());
		}
		if (engine != null) {
			return ServerAssumptions.install(engine.getSource());
		}
		return ServerAssumptions.Install.UNKNOWN;
	}

	/** Where the server is, for messages. */
	@Override
	public String toString() {
		if (container != null) {
			return restUrl + " (" + container.getSource().describe() + ")";
		}
		if (engine != null) {
			return restUrl + " (" + engine.getSource().describe() + ")";
		}
		return restUrl + " (TM1_URL)";
	}

	private record Closing(Tm1Client client) implements AutoCloseable {
		@Override
		public void close() {
			client.close().block(WAIT);
		}
	}

}
