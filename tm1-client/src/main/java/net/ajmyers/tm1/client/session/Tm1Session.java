package net.ajmyers.tm1.client.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1ErrorClassifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One TM1 session, shared by every service (plan §5). Thread-safe, and safe to share between any
 * number of concurrent subscribers: TM1 runs requests on one session in parallel.
 *
 * <pre>{@code
 * Tm1Session session = Tm1Session.builder()
 *     .baseUrl("http://localhost:8010/api/v1/")
 *     .basicAuth("admin", "")
 *     .build();
 * session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name"), JsonNode.class))
 *     .then(session.close())
 *     .block();   // in a standalone main() only
 * }</pre>
 *
 * <ul>
 *   <li><b>Login is lazy and happens once.</b> The first request logs in, and concurrent first
 *       requests share that login. Only the login carries the credentials; every other request
 *       carries just the session cookie, since a request with credentials and no cookie makes
 *       TM1 open another session.
 *   <li><b>Close it.</b> {@link #close()} ends the TM1 session. Otherwise it stays open on the
 *       server, counting against its sessions, until it times out.
 *   <li><b>Any request.</b> {@link #execute} and {@link #stream} send any {@link Tm1Request},
 *       so anything the services don't cover yet is still reachable.
 * </ul>
 */
public final class Tm1Session {

	private final Connection connection;

	Tm1Session(Connection connection) {
		this.connection = Objects.requireNonNull(connection, "connection");
	}

	/** A builder for a session on one TM1 server. */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Sends a request and reads its response: the entity or value for
	 * {@link Tm1Request.Response#SINGLE}, the text for {@link Tm1Request.Response#TEXT}, or
	 * nothing for {@link Tm1Request.Response#NONE}. Logs in first if needed.
	 *
	 * <p>TM1's errors arrive as {@link net.ajmyers.tm1.client.error.Tm1ApiException}, failures
	 * below HTTP as {@link net.ajmyers.tm1.client.error.Tm1TransportException}.
	 */
	public <T> Mono<T> execute(Tm1Request<T> request) {
		return connection.execute(Objects.requireNonNull(request, "request"));
	}

	/**
	 * Sends a {@link Tm1Request.Response#COLLECTION} request and streams its items as they're
	 * decoded, never holding the whole response. Follows {@code @odata.nextLink} if TM1 pages.
	 */
	public <T> Flux<T> stream(Tm1Request<T> request) {
		Objects.requireNonNull(request, "request");
		if (request.response() != Tm1Request.Response.COLLECTION) {
			return Flux.error(new IllegalArgumentException("Only collection requests are streamed: use execute() for " + request));
		}
		return connection.stream(request);
	}

	/** Logs in now, rather than on the first request, and completes with this session. */
	public Mono<Tm1Session> connect() {
		return connection.session().thenReturn(this);
	}

	/** The TM1 session's ID, as listed in {@code Sessions}. Logs in first if needed. */
	public Mono<Long> sessionId() {
		return connection.session().map(LoggedIn::sessionId);
	}

	/**
	 * Closes the TM1 session. A login in progress is waited for and then closed, so a close that
	 * races the first request can't leave a session open. If no login ever started, nothing is
	 * sent. Afterwards every request fails with {@link Tm1SessionClosedException}. Calling it
	 * again returns the same result.
	 */
	public Mono<Void> close() {
		return connection.close();
	}

	/** Whether {@link #close()} has been called. */
	public boolean isClosed() {
		return connection.isClosed();
	}

	/** The service root, such as {@code http://localhost:8010/api/v1/}. */
	public String serviceRoot() {
		return connection.root();
	}

	@Override
	public String toString() {
		return "Tm1Session[" + connection.root() + "]";
	}

	/** Builds a {@link Tm1Session}. Only {@link #baseUrl} and credentials are required. */
	public static final class Builder {

		private String baseUrl;
		private String authorization;
		private Tm1Timeouts timeouts = Tm1Timeouts.DEFAULTS;
		private Tm1ErrorClassifier classifier = Tm1ErrorClassifier.defaults();
		private ObjectMapper mapper;
		private ObservationRegistry observations = ObservationRegistry.NOOP;
		private WebClient.Builder webClient;
		private ClientHttpConnector connector;

		private Builder() {
		}

		/** The service root, such as {@code http://localhost:8010/api/v1/}. A missing trailing {@code /} is added. */
		public Builder baseUrl(String baseUrl) {
			String url = Objects.requireNonNull(baseUrl, "baseUrl");
			URI uri = URI.create(url);
			if (!uri.isAbsolute() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw new IllegalArgumentException("baseUrl must be an absolute URL with no query or fragment: " + url);
			}
			this.baseUrl = url.endsWith("/") ? url : url + "/";
			return this;
		}

		/** Basic authentication, for TM1 security modes 1 and 2. */
		public Builder basicAuth(String user, String password) {
			Objects.requireNonNull(user, "user");
			String credentials = user + ":" + (password == null ? "" : password);
			this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
			return this;
		}

		/** Timeouts by kind of work; {@link Tm1Timeouts#DEFAULTS} unless set. */
		public Builder timeouts(Tm1Timeouts timeouts) {
			this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
			return this;
		}

		/** How error responses are classified; {@link Tm1ErrorClassifier#defaults()} unless set. */
		public Builder errorClassifier(Tm1ErrorClassifier classifier) {
			this.classifier = Objects.requireNonNull(classifier, "classifier");
			return this;
		}

		/** The mapper for request and response bodies; a plain {@link JsonMapper} unless set. */
		public Builder jsonMapper(ObjectMapper mapper) {
			this.mapper = Objects.requireNonNull(mapper, "mapper");
			return this;
		}

		/**
		 * Where observations go: the session's own ({@code tm1.session.login},
		 * {@code tm1.session.close}) and WebClient's per request ({@code http.client.requests}).
		 * None are recorded unless set.
		 */
		public Builder observationRegistry(ObservationRegistry observations) {
			this.observations = Objects.requireNonNull(observations, "observations");
			return this;
		}

		/**
		 * A {@link WebClient.Builder} to start from, such as Spring Boot's, so its customizations
		 * apply. It's copied, not changed. The connector is still set as described in
		 * {@link #connector}.
		 */
		public Builder webClient(WebClient.Builder webClient) {
			this.webClient = Objects.requireNonNull(webClient, "webClient").clone();
			return this;
		}

		/**
		 * The HTTP connector. Unless set, a Reactor Netty connector with gzip and the
		 * {@linkplain Tm1Timeouts#connect() connect timeout}. With another connector, connect and
		 * request timeouts are that connector's own settings.
		 */
		public Builder connector(ClientHttpConnector connector) {
			this.connector = Objects.requireNonNull(connector, "connector");
			return this;
		}

		/** The session. Nothing is sent to TM1 until the first request. */
		public Tm1Session build() {
			if (baseUrl == null) {
				throw new IllegalStateException("baseUrl is required");
			}
			if (authorization == null) {
				throw new IllegalStateException("credentials are required: call basicAuth");
			}
			ClientHttpConnector http = connector != null ? connector : nettyConnector(timeouts);
			WebClient web = (webClient != null ? webClient : WebClient.builder()).clientConnector(http).observationRegistry(observations).build();
			ObjectMapper json = mapper != null ? mapper : JsonMapper.builder().build();
			return new Tm1Session(new Connection(web, baseUrl, authorization, null, timeouts, classifier, json, observations));
		}

		private static ClientHttpConnector nettyConnector(Tm1Timeouts timeouts) {
			HttpClient http = HttpClient.create()
				.compress(true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, timeouts.connect().toMillis()))
				.responseTimeout(timeouts.request());
			return new ReactorClientHttpConnector(http);
		}
	}
}
