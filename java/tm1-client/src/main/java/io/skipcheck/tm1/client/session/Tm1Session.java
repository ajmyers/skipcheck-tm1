// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.error.Tm1ErrorClassifier;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.client.version.Tm1Version;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One TM1 session, shared by every service. Thread-safe, and safe to share between any
 * number of concurrent callers: TM1 runs requests on one session in parallel.
 *
 * <ul>
 * <li><b>Login is lazy and happens once.</b> The first request logs in, and concurrent
 * first requests share that login. Only the login carries the credentials; every other
 * request carries just the session cookie, since a request with credentials and no cookie
 * makes TM1 open another session.
 * <li><b>Close it.</b> {@link #close()} ends the TM1 session. Otherwise it stays open on
 * the server, counting against its sessions, until it times out.
 * <li><b>Any request.</b> {@link #execute} and {@link #stream} send any
 * {@link Tm1Request}, so anything the services don't cover yet is still reachable.
 * </ul>
 *
 * <p>
 * Build a session with CAM security and a label:
 * {@snippet class = examples.SessionExamples region = build}
 *
 * <p>
 * Or log in with a CAM passport, such as one from exchanging an OIDC token with Cognos:
 * {@snippet class = examples.SessionExamples region = passport}
 *
 * <p>
 * Send a request no service covers yet, here streaming a collection:
 * {@snippet class = examples.SessionExamples region = request}
 *
 * <p>
 * Run a service's requests in a sandbox by giving it a sandbox view of the session:
 * {@snippet class = examples.SessionExamples region = sandbox}
 */
public final class Tm1Session {

	/** TM1's custom query option that runs a request in a sandbox. */
	static final String SANDBOX_OPTION = "!sandbox";

	/** TM1's custom query option that records a request's writes in a change set. */
	static final String CHANGE_SET_OPTION = "!ChangeSet";

	private final Connection connection;

	private final @Nullable String sandbox;

	private final @Nullable String changeSet;

	private final @Nullable Duration async;

	private final @Nullable BatchQueue batch;

	Tm1Session(Connection connection) {
		this(connection, null, null, null, null);
	}

	private Tm1Session(Connection connection, @Nullable String sandbox, @Nullable String changeSet,
			@Nullable Duration async, @Nullable BatchQueue batch) {
		this.connection = Objects.requireNonNull(connection, "connection");
		this.sandbox = sandbox;
		this.changeSet = changeSet;
		this.async = async;
		this.batch = batch;
	}

	/**
	 * A builder for a session on one TM1 server.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Sends a request and reads its response: the entity or value for
	 * {@link Tm1Request.Response#SINGLE}, the text for {@link Tm1Request.Response#TEXT},
	 * or nothing for {@link Tm1Request.Response#NONE}. Logs in first if needed.
	 *
	 * <p>
	 * TM1's errors arrive as {@link io.skipcheck.tm1.client.error.Tm1ApiException},
	 * failures below HTTP as {@link io.skipcheck.tm1.client.error.Tm1TransportException}.
	 * @param <T> the type the response is read as
	 * @param request the request to send; not a collection request
	 * @return the response, empty for {@link Tm1Request.Response#NONE}, or an
	 * {@link IllegalArgumentException} for a collection request
	 */
	public <T> Mono<T> execute(Tm1Request<T> request) {
		Objects.requireNonNull(request, "request");
		if (request.response() == Tm1Request.Response.COLLECTION || request.response() == Tm1Request.Response.BYTES) {
			return Mono.error(new IllegalArgumentException(
					"Collection and byte requests are streamed: use stream() for " + request));
		}
		if (batch != null) {
			return batch.add(inSandbox(request)).next();
		}
		// What it needs, and the parameters the server has, checked first; the body may
		// lose
		// a parameter the server lacks whose value is what leaving it out means
		return connection.prepare(request)
			.flatMap(ready -> async != null && ready.response() != Tm1Request.Response.BYTES
					? connection.async(inSandbox(ready), async).next() : connection.execute(inSandbox(ready)));
	}

	/**
	 * Sends a {@link Tm1Request.Response#COLLECTION} request and streams its items as
	 * they're decoded, never holding the whole response. Follows {@code @odata.nextLink}
	 * if TM1 pages. A {@link Tm1Request.Response#BYTES} request streams the body's
	 * buffers as they arrive.
	 * @param <T> the type of each item
	 * @param request the collection request to send
	 * @return the items, or an {@link IllegalArgumentException} for a request that isn't
	 * a collection request
	 */
	public <T> Flux<T> stream(Tm1Request<T> request) {
		Objects.requireNonNull(request, "request");
		if (request.response() != Tm1Request.Response.COLLECTION && request.response() != Tm1Request.Response.BYTES) {
			return Flux.error(new IllegalArgumentException(
					"Only collection requests are streamed: use execute() for " + request));
		}
		if (batch != null) {
			return batch.add(inSandbox(request));
		}
		return connection.prepare(request)
			.flatMapMany(ready -> async != null && ready.response() == Tm1Request.Response.COLLECTION
					? connection.async(inSandbox(ready), async) : connection.stream(inSandbox(ready)));
	}

	/**
	 * The server's product version, such as {@code 11.8.03500.6}, read from
	 * {@code Server.ProductVersion} once per login. Logs in first if needed.
	 * @return the version
	 */
	public Mono<Tm1Version> version() {
		return connection.version();
	}

	/**
	 * What the server supports, from its own {@code $metadata}. Fetched once per server
	 * version and shared by every session built from the same settings, a pool's
	 * included. A request that declares {@link Tm1Request#requires} is checked against it
	 * before it's sent.
	 * @return the capabilities
	 */
	public Mono<Tm1Capabilities> capabilities() {
		return connection.capabilities();
	}

	/**
	 * A view of this session whose requests run in sandbox {@code name}: each gets TM1's
	 * {@code !sandbox} query option, unless the request sets its own. The view shares
	 * this session's login, cookie and connections, so it costs nothing; closing either
	 * closes both.
	 *
	 * <p>
	 * Derived rather than set on the session, so one caller's sandbox can't leak into
	 * another caller's requests. TM1 evaluates {@code NON EMPTY} and filters in the
	 * request's sandbox, so every request of an operation needs it, cellset reads and
	 * deletes included; a service given this view gets that for free.
	 * @param name the sandbox's name; not blank
	 * @return a view of this session in that sandbox
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public Tm1Session withSandbox(String name) {
		Objects.requireNonNull(name, "name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("sandbox name is blank");
		}
		return new Tm1Session(connection, name, changeSet, async, batch);
	}

	/**
	 * A view of this session whose requests run outside any sandbox, in base data.
	 * @return the view, or this session if it has no sandbox
	 */
	public Tm1Session withoutSandbox() {
		return sandbox == null ? this : new Tm1Session(connection, null, changeSet, async, batch);
	}

	/**
	 * Starts a JSON {@code $batch}: its {@link Tm1SessionBatch#session() session} is a
	 * view of this one (same sandbox and change set) whose requests are queued, and
	 * {@link Tm1SessionBatch#run()} sends them together.
	 * @return the batch
	 */
	public Tm1SessionBatch batch() {
		BatchQueue queue = new BatchQueue(connection, connection.mapper());
		return new Tm1SessionBatch(queue, new Tm1Session(connection, sandbox, changeSet, null, queue));
	}

	/**
	 * A view of this session whose requests TM1 runs asynchronously (OData's
	 * {@code Prefer: respond-async}): each returns at once with a monitor, which the
	 * client polls every {@code every} until the result is there, so a long process or
	 * chore never holds a request open for its whole run. Methods return what they always
	 * do. Cancelling the subscription before the result deletes the monitor, which stops
	 * the work on TM1: a process run ends as {@code TM1UserException: Cancel}.
	 *
	 * <p>
	 * Raw byte requests (document content) aren't sent async. TM1 (11.8) sends no
	 * {@code Retry-After}, so {@code every} is the only pace.
	 * @param every how long to wait between polls of the monitor
	 * @return the view
	 * @throws IllegalArgumentException if {@code every} isn't positive
	 */
	public Tm1Session withAsync(Duration every) {
		Objects.requireNonNull(every, "every");
		if (every.isNegative() || every.isZero()) {
			throw new IllegalArgumentException("The poll interval must be positive: " + every);
		}
		return new Tm1Session(connection, sandbox, changeSet, every, batch);
	}

	/**
	 * A view of this session whose requests run synchronously, as usual.
	 * @return the view, or this session if it isn't async
	 */
	public Tm1Session withoutAsync() {
		return async == null ? this : new Tm1Session(connection, sandbox, changeSet, null, batch);
	}

	/**
	 * How often this view polls an async request's monitor, if it's async.
	 * @return the interval, or empty for a synchronous view
	 */
	public Optional<Duration> async() {
		return Optional.ofNullable(async);
	}

	/**
	 * The sandbox this view's requests run in, if any.
	 * @return the sandbox's name, or empty for base data
	 */
	public Optional<String> sandbox() {
		return Optional.ofNullable(sandbox);
	}

	/**
	 * A view of this session whose writes are recorded in change set {@code id}: each
	 * request gets TM1's {@code !ChangeSet} query option, unless it sets its own, so
	 * {@code UndoChangeSet} can revert them together. Like {@link #withSandbox}, the view
	 * shares this session's login and connections; it keeps the sandbox, if any.
	 * @param id the change set's ID, from {@code BeginChangeSet}; not blank
	 * @return a view of this session recording into that change set
	 * @throws IllegalArgumentException if {@code id} is blank
	 */
	public Tm1Session withChangeSet(String id) {
		Objects.requireNonNull(id, "id");
		if (id.isBlank()) {
			throw new IllegalArgumentException("change set ID is blank");
		}
		return new Tm1Session(connection, sandbox, id, async, batch);
	}

	/**
	 * A view of this session whose writes go into no change set.
	 * @return the view, or this session if it has no change set
	 */
	public Tm1Session withoutChangeSet() {
		return changeSet == null ? this : new Tm1Session(connection, sandbox, null, async, batch);
	}

	/**
	 * The change set this view's writes are recorded in, if any.
	 * @return the change set's ID, or empty
	 */
	public Optional<String> changeSet() {
		return Optional.ofNullable(changeSet);
	}

	/**
	 * Logs in now, rather than on the first request, and completes with this session.
	 * @return this session, once logged in
	 */
	public Mono<Tm1Session> connect() {
		return connection.session().thenReturn(this);
	}

	/**
	 * The TM1 session's ID, as listed in {@code Sessions}. Logs in first if needed.
	 * @return the session ID
	 */
	public Mono<Long> sessionId() {
		return connection.session().map(LoggedIn::sessionId);
	}

	/**
	 * Closes the TM1 session. A login in progress is waited for and then closed, so a
	 * close that races the first request can't leave a session open. If no login ever
	 * started, nothing is sent. Afterwards every request fails with
	 * {@link Tm1SessionClosedException}. Calling it again returns the same result.
	 * @return completes once the session is closed
	 */
	public Mono<Void> close() {
		return connection.close();
	}

	/** Whether a request is running on the session, through any view of it. */
	boolean isBusy() {
		return connection.isBusy();
	}

	/**
	 * Whether {@link #close()} has been called.
	 * @return {@code true} once closed
	 */
	public boolean isClosed() {
		return connection.isClosed();
	}

	/**
	 * The service root, such as {@code http://localhost:8010/api/v1/}.
	 * @return the service root, always ending in {@code /}
	 */
	public String serviceRoot() {
		return connection.root();
	}

	@Override
	public String toString() {
		return "Tm1Session[" + connection.root() + (sandbox == null ? "" : ", sandbox=" + sandbox)
				+ (changeSet == null ? "" : ", changeSet=" + changeSet) + "]";
	}

	private <T> Tm1Request<T> inSandbox(Tm1Request<T> request) {
		Tm1Request<T> result = request;
		if (sandbox != null && result.query().option(SANDBOX_OPTION) == null) {
			result = result.withQuery(result.query().option(SANDBOX_OPTION, sandbox));
		}
		if (changeSet != null && result.query().option(CHANGE_SET_OPTION) == null) {
			result = result.withQuery(result.query().option(CHANGE_SET_OPTION, changeSet));
		}
		return result;
	}

	/**
	 * How long the default connector keeps an idle connection for reuse. TM1 (11.8)
	 * closes a kept-alive connection after about 90 seconds idle
	 * ({@code Tm1SessionBehaviorTest}), and a request sent on it as it closes gets no
	 * response at all, so the client closes it first.
	 */
	static final Duration MAX_IDLE = Duration.ofSeconds(60);

	/**
	 * The default connector's pool, shared by every session that doesn't bring its own,
	 * as Reactor Netty's global one would be, but closing connections idle for
	 * {@link #MAX_IDLE}.
	 */
	private static final ConnectionProvider CONNECTIONS = ConnectionProvider.builder("tm1-client")
		.maxIdleTime(MAX_IDLE)
		.evictInBackground(Duration.ofSeconds(30))
		.build();

	/**
	 * Builds a {@link Tm1Session}. Only {@link #baseUrl} and credentials are required.
	 */
	public static final class Builder {

		private @Nullable String baseUrl;

		private Tm1Dialect dialect = Tm1Dialect.V11;

		private @Nullable Tm1Authenticator authenticator;

		private @Nullable String sessionContext;

		private @Nullable String impersonate;

		private LongSupplier nanoTime = System::nanoTime;

		/** Shared by copies, so a pool's sessions fetch $metadata once. */
		private CapabilitiesCache capabilitiesCache = new CapabilitiesCache();

		private Tm1Timeouts timeouts = Tm1Timeouts.DEFAULTS;

		private Tm1ErrorClassifier classifier = Tm1ErrorClassifier.defaults();

		private @Nullable ObjectMapper mapper;

		private ObservationRegistry observations = ObservationRegistry.NOOP;

		private WebClient.@Nullable Builder webClient;

		private @Nullable ClientHttpConnector connector;

		private Builder() {
		}

		/** A copy of these settings, for the pool to build one session per identity. */
		Builder copy() {
			Builder copy = new Builder();
			copy.baseUrl = baseUrl;
			copy.dialect = dialect;
			copy.authenticator = authenticator;
			copy.sessionContext = sessionContext;
			copy.impersonate = impersonate;
			copy.nanoTime = nanoTime;
			copy.capabilitiesCache = capabilitiesCache;
			copy.timeouts = timeouts;
			copy.classifier = classifier;
			copy.mapper = mapper;
			copy.observations = observations;
			copy.webClient = webClient == null ? null : webClient.clone();
			copy.connector = connector;
			return copy;
		}

		/** Logs in as the credentials' own user: no impersonation. */
		Builder withoutImpersonation() {
			this.impersonate = null;
			return this;
		}

		/** The base URL, or a placeholder if it isn't set yet, for the pool's logs. */
		String baseUrlOrUnset() {
			return baseUrl == null ? "(no base URL)" : baseUrl;
		}

		/** The observation registry, which the pool reports to as well. */
		ObservationRegistry observations() {
			return observations;
		}

		/**
		 * The server's address, such as {@code http://tm1:8010}: its scheme, host and
		 * HTTP port. The service root comes from the dialect, {@link Tm1Dialect#V11}
		 * unless set, which adds {@code /api/v1/}. Use {@link #baseUrl} to give the
		 * service root itself.
		 * @param address the server's address
		 * @return this builder
		 * @throws IllegalArgumentException if the address doesn't fit the dialect
		 */
		public Builder address(String address) {
			return baseUrl(dialect.serviceRoot(address));
		}

		/**
		 * The kind of deployment, for {@link #address}. {@link Tm1Dialect#V11} unless
		 * set.
		 * @param dialect the dialect
		 * @return this builder
		 */
		public Builder dialect(Tm1Dialect dialect) {
			this.dialect = Objects.requireNonNull(dialect, "dialect");
			return this;
		}

		/**
		 * The service root, such as {@code http://localhost:8010/api/v1/}. A missing
		 * trailing {@code /} is added.
		 * @param baseUrl an absolute URL with no query or fragment
		 * @return this builder
		 * @throws IllegalArgumentException if {@code baseUrl} isn't absolute or has a
		 * query or fragment
		 */
		public Builder baseUrl(String baseUrl) {
			String url = Objects.requireNonNull(baseUrl, "baseUrl");
			URI uri = URI.create(url);
			if (!uri.isAbsolute() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw new IllegalArgumentException("baseUrl must be an absolute URL with no query or fragment: " + url);
			}
			this.baseUrl = url.endsWith("/") ? url : url + "/";
			return this;
		}

		/**
		 * Basic authentication, for TM1 security modes 1 and 2: shorthand for
		 * {@code authenticator(Tm1Authenticator.basic(user, password))}.
		 * @param user the TM1 user name
		 * @param password the password; {@code null} counts as empty
		 * @return this builder
		 */
		public Builder basicAuth(String user, @Nullable String password) {
			return authenticator(Tm1Authenticator.basic(user, password));
		}

		/**
		 * How to log in: TM1's own security, CAM, or credentials of your own. Asked for
		 * credentials on every login.
		 * @param authenticator supplies the credentials
		 * @return this builder
		 */
		public Builder authenticator(Tm1Authenticator authenticator) {
			this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
			return this;
		}

		/**
		 * A label for the TM1 session, shown as its {@code Context} in {@code Sessions}
		 * and {@code Threads}, such as the application's name. Sent with the login.
		 *
		 * <p>
		 * It's a setting of the session, not of a view: TM1 keeps one label per session
		 * and the last request to send one wins, so views sharing a session can't each
		 * have their own. A different label needs a different session.
		 * @param sessionContext the label, at most 250 characters
		 * @return this builder
		 * @throws IllegalArgumentException if {@code sessionContext} is longer than 250
		 * characters
		 */
		public Builder sessionContext(String sessionContext) {
			Objects.requireNonNull(sessionContext, "sessionContext");
			if (sessionContext.length() > 250) {
				throw new IllegalArgumentException(
						"TM1 keeps at most 250 characters of session context, got " + sessionContext.length());
			}
			this.sessionContext = sessionContext;
			return this;
		}

		/**
		 * Logs in as {@code user} rather than as the credentials' own user, with TM1's
		 * {@code TM1-Impersonate}. The credentials must be an administrator's or a data
		 * administrator's, and nobody can impersonate an administrator. TM1 decides the
		 * identity at login and keeps it for the session, so one session is one identity;
		 * for many users, use a {@link Tm1SessionPool}.
		 *
		 * <p>
		 * A refused login fails with
		 * {@link io.skipcheck.tm1.client.error.Tm1ErrorKind#UNKNOWN_USER} (no such user),
		 * {@link io.skipcheck.tm1.client.error.Tm1ErrorKind#IMPERSONATION_REFUSED} (not
		 * allowed), or
		 * {@link io.skipcheck.tm1.client.error.Tm1ErrorKind#CREDENTIAL_REJECTED} (wrong
		 * credentials, or the user is disabled: TM1 answers both alike). The client never
		 * falls back to another identity.
		 * @param user the TM1 user to act as; matched ignoring case and spaces, as TM1
		 * does. A name with characters outside ASCII goes out as UTF-8, which is how TM1
		 * (11.8) reads the header; that needs the default Reactor Netty connector, as the
		 * JDK's {@code HttpClient} replaces such characters
		 * @return this builder
		 * @throws IllegalArgumentException if {@code user} is blank, which TM1 would take
		 * as no impersonation at all, or has a control character
		 */
		public Builder impersonate(String user) {
			this.impersonate = UserNames.checkImpersonated(user);
			return this;
		}

		/**
		 * Timeouts by kind of work; {@link Tm1Timeouts#DEFAULTS} unless set.
		 * @param timeouts the timeouts
		 * @return this builder
		 */
		public Builder timeouts(Tm1Timeouts timeouts) {
			this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
			return this;
		}

		/**
		 * How error responses are classified; {@link Tm1ErrorClassifier#defaults()}
		 * unless set.
		 * @param classifier the classifier
		 * @return this builder
		 */
		public Builder errorClassifier(Tm1ErrorClassifier classifier) {
			this.classifier = Objects.requireNonNull(classifier, "classifier");
			return this;
		}

		/**
		 * The mapper for request and response bodies; a plain {@link JsonMapper} unless
		 * set.
		 * @param mapper the mapper
		 * @return this builder
		 */
		public Builder jsonMapper(ObjectMapper mapper) {
			this.mapper = Objects.requireNonNull(mapper, "mapper");
			return this;
		}

		/**
		 * Where observations go: the session's own ({@code tm1.session.login},
		 * {@code tm1.session.close}) and WebClient's per request
		 * ({@code http.client.requests}). None are recorded unless set.
		 * @param observations the registry
		 * @return this builder
		 */
		public Builder observationRegistry(ObservationRegistry observations) {
			this.observations = Objects.requireNonNull(observations, "observations");
			return this;
		}

		/**
		 * A {@link WebClient.Builder} to start from, such as Spring Boot's, so its
		 * customizations apply. It's copied, not changed. The connector is still set as
		 * described in {@link #connector}.
		 * @param webClient the builder to copy
		 * @return this builder
		 */
		public Builder webClient(WebClient.Builder webClient) {
			this.webClient = Objects.requireNonNull(webClient, "webClient").clone();
			return this;
		}

		/**
		 * The HTTP connector. Unless set, a Reactor Netty connector with gzip and the
		 * {@linkplain Tm1Timeouts#connect() connect timeout}. With another connector,
		 * connect and request timeouts are that connector's own settings, and so is its
		 * pool: keep its idle connections under TM1's 90 seconds (the default connector
		 * closes them after 60).
		 * @param connector the connector
		 * @return this builder
		 */
		public Builder connector(ClientHttpConnector connector) {
			this.connector = Objects.requireNonNull(connector, "connector");
			return this;
		}

		/** The clock for remembering refused logins; for tests and the pool. */
		Builder nanoTime(LongSupplier nanoTime) {
			this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
			return this;
		}

		/**
		 * The session. Nothing is sent to TM1 until the first request.
		 * @return a new session
		 * @throws IllegalStateException if the base URL or credentials aren't set
		 */
		public Tm1Session build() {
			if (baseUrl == null) {
				throw new IllegalStateException("baseUrl is required");
			}
			if (authenticator == null) {
				throw new IllegalStateException("credentials are required: call basicAuth or authenticator");
			}
			ClientHttpConnector http = connector != null ? connector : nettyConnector(timeouts);
			WebClient web = (webClient != null ? webClient : WebClient.builder()).clientConnector(http)
				.observationRegistry(observations)
				.build();
			ObjectMapper json = mapper != null ? mapper : JsonMapper.builder().build();
			return new Tm1Session(new Connection(web, baseUrl, authenticator, sessionContext, impersonate, timeouts,
					classifier, json, observations, nanoTime, capabilitiesCache));
		}

		private static ClientHttpConnector nettyConnector(Tm1Timeouts timeouts) {
			HttpClient http = HttpClient.create(CONNECTIONS)
				.compress(true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
						(int) Math.min(Integer.MAX_VALUE, timeouts.connect().toMillis()))
				.responseTimeout(timeouts.request());
			return new ReactorClientHttpConnector(http);
		}

	}

}
