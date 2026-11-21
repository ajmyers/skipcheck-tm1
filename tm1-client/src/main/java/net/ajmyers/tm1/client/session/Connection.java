package net.ajmyers.tm1.client.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.ODataError;
import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1ErrorClassifier;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.error.Tm1ProtocolException;
import net.ajmyers.tm1.client.odata.ODataCollectionDecoder;
import net.ajmyers.tm1.client.odata.ODataJson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The state every view of one {@link Tm1Session} shares: the HTTP client, the
 * credentials, and the TM1 session itself (its cookie, the login in progress, whether
 * it's closed).
 *
 * <p>
 * State changes (starting a login, replacing a failed one, closing) happen under this
 * object's lock. They only swap references, never wait for I/O, so the lock is never held
 * across a request.
 */
@Slf4j
final class Connection {

	static final String COOKIE = "TM1SessionId";
	static final String SESSION_CONTEXT = "TM1-SessionContext";

	private static final String LOGIN_PATH = "ActiveSession?$select=ID";

	private static final String CLOSE_PATH = "ActiveSession/tm1.Close";

	private static final byte[] EMPTY_OBJECT = { '{', '}' };

	private final WebClient web;

	private final String root;

	private final String authorization;

	private final @Nullable String sessionContext;

	private final Tm1Timeouts timeouts;

	private final Tm1ErrorClassifier classifier;

	private final ObjectMapper mapper;

	private final ObservationRegistry observations;

	/**
	 * The current login, or null if none has started (or the last one failed). Guarded by
	 * this.
	 */
	private @Nullable Attempt attempt;

	/** Set once by {@link #close()}. Guarded by this. */
	private boolean closed;

	/** What {@link #close()} returns, once called. Guarded by this. */
	private @Nullable Mono<Void> closing;

	Connection(WebClient web, String root, String authorization, @Nullable String sessionContext, Tm1Timeouts timeouts,
			Tm1ErrorClassifier classifier, ObjectMapper mapper, ObservationRegistry observations) {
		this.web = Objects.requireNonNull(web, "web");
		this.root = Objects.requireNonNull(root, "root");
		this.authorization = Objects.requireNonNull(authorization, "authorization");
		this.sessionContext = sessionContext;
		this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
		this.classifier = Objects.requireNonNull(classifier, "classifier");
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		this.observations = Objects.requireNonNull(observations, "observations");
	}

	String root() {
		return root;
	}

	synchronized boolean isClosed() {
		return closed;
	}

	/**
	 * The logged-in session, logging in first if needed. Concurrent callers share one
	 * login.
	 */
	Mono<LoggedIn> session() {
		return Mono.defer(() -> current().login);
	}

	/** Sends one request and reads a single response: an entity, text, or nothing. */
	<T> Mono<T> execute(Tm1Request<T> request) {
		URI uri = uri(request.relativeUri());
		return exchange(request, uri, response -> read(request, uri, response).flux()).next();
	}

	/**
	 * Sends a collection request and streams its items, following
	 * {@code @odata.nextLink}.
	 *
	 * <p>
	 * Each page is its own exchange, decoded as it arrives. The next page is only
	 * requested once the previous response is complete and its connection back in the
	 * pool, so a collection of many pages doesn't hold a connection for its whole length.
	 */
	<T> Flux<T> stream(Tm1Request<T> request) {
		return page(request, uri(request.relativeUri()));
	}

	/**
	 * Closes the TM1 session: waits for a login in progress, then sends
	 * {@code tm1.Close}. Does nothing if no login ever started. Afterwards the connection
	 * refuses requests. Every call returns the same result.
	 */
	Mono<Void> close() {
		return Mono.defer(() -> {
			synchronized (this) {
				if (closing == null) {
					closed = true;
					Attempt last = attempt;
					attempt = null;
					closing = (last == null ? Mono.<Void>empty() : last.close()).cache();
				}
				return closing;
			}
		});
	}

	private synchronized Attempt current() {
		if (closed) {
			throw new Tm1SessionClosedException(root);
		}
		if (attempt == null) {
			attempt = new Attempt(null, "initial");
		}
		return attempt;
	}

	/**
	 * The login to replay on after {@code expired} saw a 401. The first request to report
	 * it starts a new login; requests that report the same expired login later share that
	 * one. TM1 opened a session along with the 401, so the new login logs that one in (by
	 * sending its cookie) rather than opening another.
	 */
	private synchronized Attempt relogin(Attempt expired, @Nullable String seedCookie) {
		if (closed) {
			throw new Tm1SessionClosedException(root);
		}
		if (attempt == expired || attempt == null) {
			log.info("TM1 session at {} expired or was closed on the server; logging in again", root);
			attempt = new Attempt(seedCookie, "relogin");
		}
		return attempt;
	}

	/** Forgets a login that failed, so the next request tries again. */
	private synchronized void forget(Attempt failed) {
		if (attempt == failed) {
			attempt = null;
		}
	}

	private <T> Flux<T> page(Tm1Request<T> request, URI uri) {
		return Flux.defer(() -> {
			String[] next = new String[1];
			Flux<ODataCollectionDecoder.Event<T>> events = exchange(request, uri,
					response -> ODataCollectionDecoder.decode(response.bodyToFlux(DataBuffer.class), mapper,
							request.type(), request.method().name(), uri.toString()));
			Flux<T> items = events.handle((event, sink) -> {
				if (event instanceof ODataCollectionDecoder.Event.Item<T> item && item.value() != null) {
					sink.next(item.value());
				}
				else if (event instanceof ODataCollectionDecoder.Event.Control<T> control
						&& ODataJson.NEXT_LINK.equals(control.name()) && control.value().isString()) {
					next[0] = control.value().asString();
				}
			});
			// Next links are opaque: resolved against the service root, never changed
			// (OData Part 1 §11.2.6.7)
			return items.concatWith(Flux
				.defer(() -> next[0] == null ? Flux.empty() : page(request, URI.create(root).resolve(next[0]))));
		});
	}

	/**
	 * Sends {@code request} on the logged-in session, and hands a successful response to
	 * {@code onSuccess}.
	 *
	 * <p>
	 * A 401 means the session expired or was killed. Then the session logs in again, once
	 * for all the requests that saw it expire, and the request is replayed <b>once</b>,
	 * POSTs included: TM1 refuses a request on a dead session before running it, so the
	 * replay can't run it twice ({@code Tm1SessionBehaviorTest} pins this). A 401 on the
	 * replay is final, so a server that keeps refusing can't cause a loop of logins.
	 */
	private <T, R> Flux<R> exchange(Tm1Request<T> request, URI uri, Function<ClientResponse, Flux<R>> onSuccess) {
		return Flux.defer(() -> {
			Attempt used = current();
			return used.login.flatMapMany(session -> send(request, uri, session, onSuccess))
				.onErrorResume(Expired.class, expired -> relogin(used, expired.cookie).login
					.flatMapMany(session -> send(request, uri, session, onSuccess)));
		}).onErrorMap(Expired.class, expired -> unauthenticated(request, uri));
	}

	private <T, R> Flux<R> send(Tm1Request<T> request, URI uri, LoggedIn session,
			Function<ClientResponse, Flux<R>> onSuccess) {
		String method = request.method().name();
		return Flux.defer(() -> {
			long start = System.nanoTime();
			log.debug("Sending {} {}", method, uri);
			WebClient.RequestBodySpec spec = web.method(request.method()).uri(uri).headers(h -> {
				odataHeaders(h, request.response() == Tm1Request.Response.TEXT);
				request.headers().forEach(h::set);
			})
				.cookie(COOKIE, session.cookie())
				.httpRequest(r -> responseTimeout(r.getNativeRequest(), request.timeout().orElse(timeouts.request())));
			WebClient.RequestHeadersSpec<?> ready = request.body() == null ? spec
					: spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json(request.body()));
			return ready.<R>exchangeToFlux(response -> {
				int status = response.statusCode().value();
				log.debug("Received {} for {} {} after {} ms", status, method, uri,
						Duration.ofNanos(System.nanoTime() - start).toMillis());
				if (status == 401) {
					return response.releaseBody().thenMany(Flux.<R>error(new Expired(cookie(response))));
				}
				if (status >= 400) {
					return this.<R>error(response, method, uri).flux();
				}
				return onSuccess.apply(response);
			});
		})
			.onErrorMap(e -> !(e instanceof Expired),
					e -> Transport.map(e, method, uri.toString(), request.isIdempotent()));
	}

	private <T> Mono<T> read(Tm1Request<T> request, URI uri, ClientResponse response) {
		return switch (request.response()) {
			case NONE -> response.releaseBody().then(Mono.empty());
			case TEXT -> response.bodyToMono(String.class).map(request.type()::cast);
			case SINGLE -> DataBufferUtils.join(response.bodyToFlux(DataBuffer.class)).map(buffer -> {
				try {
					return mapper.readValue(buffer.asInputStream(), request.type());
				}
				catch (JacksonException e) {
					throw new Tm1ProtocolException(
							"TM1 sent a response that isn't valid JSON for " + request.type().getSimpleName(), null,
							request.method().name(), uri.toString(), e);
				}
				finally {
					DataBufferUtils.release(buffer);
				}
			});
			default -> Mono.error(new IllegalArgumentException(
					request.response() + " responses are streamed: use stream(), not execute()"));
		};
	}

	/**
	 * The exception for an error response, from its OData error body, or its text if it's
	 * a plain-text error, so the classifier's message rules apply to both.
	 */
	private Mono<Throwable> errorOf(ClientResponse response, String method, URI uri) {
		int status = response.statusCode().value();
		return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(body -> {
			ODataError error = null;
			if (body.length > 0) {
				try {
					error = ODataError.from(mapper.readTree(body));
				}
				catch (JacksonException e) {
					// Not JSON: TM1 sends some errors as plain text, read below
				}
				if (error == null) {
					error = ODataError.fromText(new String(body, StandardCharsets.UTF_8));
				}
			}
			return Tm1ApiException.of(status, error, method, uri.toString(), classifier);
		});
	}

	private <R> Mono<R> error(ClientResponse response, String method, URI uri) {
		return errorOf(response, method, uri).flatMap(Mono::error);
	}

	// Login and close

	private Mono<LoggedIn> login(@Nullable String seedCookie, String reason) {
		URI uri = uri(LOGIN_PATH);
		Mono<LoggedIn> login = Mono.defer(() -> {
			WebClient.RequestHeadersSpec<?> spec = web.get().uri(uri).headers(h -> {
				odataHeaders(h, false);
				h.set(HttpHeaders.AUTHORIZATION, authorization);
				if (sessionContext != null) {
					h.set(SESSION_CONTEXT, sessionContext);
				}
			}).httpRequest(r -> responseTimeout(r.getNativeRequest(), timeouts.login()));
			if (seedCookie != null) {
				// Log in the session TM1 opened for a 401, rather than opening another
				spec = spec.cookie(COOKIE, seedCookie);
			}
			return spec.exchangeToMono(response -> {
				int status = response.statusCode().value();
				if (status == 401) {
					return response.releaseBody()
						.then(Mono.error(Tm1ApiException.of(401, Tm1ErrorKind.CREDENTIAL_REJECTED, null, "GET",
								uri.toString())));
				}
				if (status >= 400) {
					return error(response, "GET", uri);
				}
				String cookie = cookie(response);
				String value = cookie != null ? cookie : seedCookie;
				return response.bodyToMono(byte[].class).map(body -> loggedIn(value, body, uri));
			});
		}).onErrorMap(e -> Transport.map(e, "GET", uri.toString(), true));
		return observe("tm1.session.login", "tm1 login", reason, login)
			.doOnNext(session -> log.info("Logged in to TM1 at {} (session {}, {})", root, session.sessionId(), reason))
			.doOnError(e -> log.debug("Login to TM1 at {} failed", root, e));
	}

	private LoggedIn loggedIn(@Nullable String cookie, byte[] body, URI uri) {
		if (cookie == null) {
			throw new Tm1ProtocolException("TM1 accepted the login but set no " + COOKIE + " cookie", null, "GET",
					uri.toString(), null);
		}
		try {
			JsonNode id = mapper.readTree(body).get("ID");
			if (id == null || !id.canConvertToLong()) {
				throw new Tm1ProtocolException("TM1 accepted the login but didn't return the session ID", null, "GET",
						uri.toString(), null);
			}
			return new LoggedIn(cookie, id.asLong());
		}
		catch (JacksonException e) {
			throw new Tm1ProtocolException("TM1 accepted the login but its response isn't JSON", null, "GET",
					uri.toString(), e);
		}
	}

	private Mono<Void> sendClose(LoggedIn session) {
		URI uri = uri(CLOSE_PATH);
		Mono<Void> close = web.post()
			.uri(uri)
			.headers(h -> odataHeaders(h, false))
			.cookie(COOKIE, session.cookie())
			.httpRequest(r -> responseTimeout(r.getNativeRequest(), timeouts.close()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(EMPTY_OBJECT)
			.exchangeToMono(response -> {
				int status = response.statusCode().value();
				log.debug("Received {} for POST {}", status, uri);
				// 401: the session was already gone (killed, or timed out), which is what
				// close wants
				if (status < 400 || status == 401) {
					return response.releaseBody();
				}
				return error(response, "POST", uri);
			})
			.onErrorMap(e -> Transport.map(e, "POST", uri.toString(), true));
		return observe("tm1.session.close", "tm1 close", "close", close)
			.doOnSuccess(v -> log.info("Closed TM1 session {} at {}", session.sessionId(), root))
			.doOnError(
					e -> log.warn("Couldn't close TM1 session {} at {}: {}", session.sessionId(), root, e.toString()));
	}

	// Helpers

	private URI uri(String relative) {
		// Already encoded by ODataPath and ODataQuery: never through a URI template,
		// which would
		// read the braces in TM1 control object names as variables (plan §16.2)
		return URI.create(root + relative);
	}

	private static void odataHeaders(HttpHeaders headers, boolean text) {
		headers.set("OData-Version", "4.0");
		headers.set("OData-MaxVersion", "4.0");
		headers.set(HttpHeaders.ACCEPT, text ? "text/plain, application/json" : MediaType.APPLICATION_JSON_VALUE);
	}

	private static void responseTimeout(Object nativeRequest, Duration timeout) {
		// Only Reactor Netty supports an idle timeout per request; other connectors use
		// their own settings
		if (nativeRequest instanceof HttpClientRequest netty) {
			netty.responseTimeout(timeout);
		}
	}

	private byte[] json(Object body) {
		if (body instanceof byte[] bytes) {
			return bytes;
		}
		if (body instanceof Map<?, ?> map && map.isEmpty()) {
			return EMPTY_OBJECT;
		}
		try {
			return mapper.writeValueAsBytes(body);
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException(
					"The request body can't be written as JSON: " + body.getClass().getName(), e);
		}
	}

	/**
	 * The {@code TM1SessionId} a response sets, found by name among all its
	 * {@code Set-Cookie} headers, or null. A cookie being expired ({@code Max-Age=0}, as
	 * {@code tm1.Close} sends) doesn't count. Parsed from the headers rather than through
	 * the connector's cookie support, so it works the same with any connector.
	 */
	static @Nullable String cookie(ClientResponse response) {
		for (String header : response.headers().header(HttpHeaders.SET_COOKIE)) {
			String[] parts = header.split(";", -1);
			int eq = parts[0].indexOf('=');
			if (eq < 0 || !parts[0].substring(0, eq).trim().equals(COOKIE)) {
				continue;
			}
			String value = parts[0].substring(eq + 1).trim();
			boolean expired = false;
			for (int i = 1; i < parts.length; i++) {
				expired |= parts[i].trim().replace(" ", "").equalsIgnoreCase("Max-Age=0");
			}
			if (!value.isEmpty() && !expired) {
				return value;
			}
		}
		return null;
	}

	private Tm1ApiException unauthenticated(Tm1Request<?> request, URI uri) {
		return Tm1ApiException.of(401, Tm1ErrorKind.UNAUTHENTICATED, null, request.method().name(), uri.toString());
	}

	private <T> Mono<T> observe(String name, String contextualName, String reason, Mono<T> work) {
		return Mono.defer(() -> {
			Observation observation = Observation.createNotStarted(name, observations)
				.contextualName(contextualName)
				.lowCardinalityKeyValue(KeyValue.of("tm1.reason", reason))
				.start();
			return work.doOnError(observation::error).doFinally(signal -> observation.stop());
		});
	}

	/**
	 * One login and what it led to: shared by every request until it fails or the session
	 * expires.
	 */
	private final class Attempt {

		final Mono<LoggedIn> login;

		Attempt(@Nullable String seedCookie, String reason) {
			// cache(): concurrent requests share one login. A failed login is forgotten,
			// so the next
			// request tries again rather than getting the cached failure forever.
			this.login = Mono.defer(() -> login(seedCookie, reason)).doOnError(e -> forget(this)).cache();
		}

		/**
		 * Waits for this login if it's in progress, then closes the session. A failed
		 * login leaves nothing to close.
		 */
		Mono<Void> close() {
			return login.onErrorResume(e -> Mono.empty()).flatMap(Connection.this::sendClose);
		}

	}

	/**
	 * A 401 on an established session: the session expired or was killed. Internal, never
	 * seen by callers.
	 */
	static final class Expired extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/** The cookie of the session TM1 opened along with the 401, or null. */
		final transient @Nullable String cookie;

		Expired(@Nullable String cookie) {
			super("TM1 session expired", null, false, false);
			this.cookie = cookie;
		}

	}

}
