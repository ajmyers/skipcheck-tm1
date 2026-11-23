// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.error.ODataError;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1ErrorClassifier;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import io.skipcheck.tm1.client.error.Tm1TransportException;
import io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException;
import io.skipcheck.tm1.client.odata.ODataCollectionDecoder;
import io.skipcheck.tm1.client.odata.ODataJson;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.odata.Utf8Repair;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Capability;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParseException;
import io.skipcheck.tm1.csdl.CsdlParser;
import lombok.extern.slf4j.Slf4j;
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

	static final String IMPERSONATE = "TM1-Impersonate";

	private static final long NOT_REFUSED = Long.MIN_VALUE;

	private static final Tm1Request<JsonNode> VERSION = Tm1Request.get(ODataPath.of("Server"),
			ODataQuery.none().select("ProductVersion"), JsonNode.class);

	private static final Tm1Request<String> METADATA = Tm1Request.text(ODataPath.metadata())
		.withHeader(HttpHeaders.ACCEPT, "application/xml");

	private static final String LOGIN_PATH = "ActiveSession?$select=ID";

	private static final String CLOSE_PATH = "ActiveSession/tm1.Close";

	private static final byte[] EMPTY_OBJECT = { '{', '}' };

	private final WebClient web;

	private final String root;

	private final Tm1Authenticator authenticator;

	private final @Nullable String sessionContext;

	private final @Nullable String impersonate;

	private final LongSupplier nanoTime;

	private final Tm1Timeouts timeouts;

	private final Tm1ErrorClassifier classifier;

	private final ObjectMapper mapper;

	private final ObservationRegistry observations;

	/**
	 * The current login, or null if none has started (or the last one failed). Guarded by
	 * this.
	 */
	private @Nullable Attempt attempt;

	/**
	 * The version read for the current login, and which login it was. Guarded by this.
	 */
	private @Nullable Mono<Tm1Version> version;

	private @Nullable Attempt versionFor;

	private final CapabilitiesCache capabilitiesCache;

	/** Set once by {@link #close()}. Guarded by this. */
	private boolean closed;

	/** What {@link #close()} returns, once called. Guarded by this. */
	private @Nullable Mono<Void> closing;

	Connection(WebClient web, String root, Tm1Authenticator authenticator, @Nullable String sessionContext,
			@Nullable String impersonate, Tm1Timeouts timeouts, Tm1ErrorClassifier classifier, ObjectMapper mapper,
			ObservationRegistry observations, LongSupplier nanoTime, CapabilitiesCache capabilitiesCache) {
		this.web = Objects.requireNonNull(web, "web");
		this.root = Objects.requireNonNull(root, "root");
		this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
		this.sessionContext = sessionContext;
		this.impersonate = impersonate;
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.capabilitiesCache = Objects.requireNonNull(capabilitiesCache, "capabilitiesCache");
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

	/**
	 * The server's product version, read from {@code Server} inside the session (an
	 * anonymous read would open a session of its own). Read once per login: a server that
	 * restarted, perhaps upgraded, makes the session log in again.
	 */
	Mono<Tm1Version> version() {
		return Mono.defer(() -> {
			synchronized (this) {
				Attempt now = current();
				if (version == null || versionFor != now) {
					AtomicReference<Mono<Tm1Version>> self = new AtomicReference<>();
					Mono<Tm1Version> read = execute(VERSION).map(server -> {
						JsonNode text = server.get("ProductVersion");
						if (text == null || !text.isString()) {
							throw new Tm1ProtocolException("TM1 didn't report its ProductVersion", null, "GET",
									uri(VERSION.relativeUri()).toString(), null);
						}
						return Tm1Version.parse(text.asString());
					}).doOnError(e -> forgetVersion(self.get())).cache();
					self.set(read);
					version = read;
					versionFor = now;
				}
				return version;
			}
		});
	}

	/**
	 * What the server supports, from its {@code $metadata}, fetched once per server
	 * version and shared with every session built from the same settings.
	 */
	Mono<Tm1Capabilities> capabilities() {
		return version()
			.flatMap(v -> capabilitiesCache.get(root, v, () -> metadata().map(doc -> Tm1Capabilities.of(doc, v))));
	}

	/**
	 * Fails with {@link Tm1UnsupportedOperationException} if the server doesn't define
	 * {@code capability}.
	 */
	Mono<Void> require(Tm1Capability capability) {
		return capabilities().flatMap(c -> {
			Optional<Tm1UnsupportedOperationException> missing = c.check(capability, root);
			return missing.isPresent() ? Mono.<Void>error(missing.get()) : Mono.<Void>empty();
		});
	}

	private Mono<CsdlDocument> metadata() {
		URI uri = uri(METADATA.relativeUri());
		return execute(METADATA).map(xml -> {
			try {
				return CsdlParser.parse(xml);
			}
			catch (CsdlParseException e) {
				throw new Tm1ProtocolException("TM1 sent $metadata that isn't valid CSDL", null, "GET", uri.toString(),
						e);
			}
		});
	}

	// Identity on purpose: forget the read only if it's the one that failed
	@SuppressWarnings("ReferenceEquality")
	private synchronized void forgetVersion(@Nullable Mono<Tm1Version> failed) {
		if (version == failed) {
			version = null;
			versionFor = null;
		}
	}

	/** The JSON mapper the session reads and writes with. */
	ObjectMapper mapper() {
		return mapper;
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
		if (request.response() == Tm1Request.Response.BYTES) {
			return exchange(request, uri(request.relativeUri()),
					response -> response.bodyToFlux(DataBuffer.class).map(request.type()::cast));
		}
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
		if (attempt == null || (attempt.refusedAt != NOT_REFUSED && !attempt.refusedRecently())) {
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
		if (attempt != null && attempt.refusedRecently()) {
			return attempt;
		}
		if (attempt == expired || attempt == null || attempt.refusedAt != NOT_REFUSED) {
			log.info("TM1 session at {} expired or was closed on the server; logging in again", root);
			attempt = new Attempt(seedCookie, "relogin");
		}
		return attempt;
	}

	/**
	 * Handles a failed login. One TM1 refused (400, 401 or 403: wrong credentials, an
	 * unknown or disabled user, impersonation not allowed) is kept for
	 * {@link Tm1Timeouts#refusedLogin()}, so requests meanwhile fail at once with the
	 * same error: each login attempt leaves a session behind on TM1, and retrying a
	 * refusal at once would pile them up (plan §16.8). Any other failure, such as TM1
	 * being unreachable, is forgotten, so the next request tries again.
	 */
	private synchronized void failed(Attempt failed, Throwable error) {
		if (attempt != failed) {
			return;
		}
		if (isRefusal(error) && !timeouts.refusedLogin().isZero()) {
			failed.refusedAt = nanoTime.getAsLong();
			log.warn("TM1 at {} refused the login as {}{}; not trying again for {}", root, authenticator.describe(),
					impersonate == null ? "" : " impersonating a user", timeouts.refusedLogin());
		}
		else {
			attempt = null;
		}
	}

	private static boolean isRefusal(Throwable error) {
		if (!(error instanceof Tm1ApiException api)) {
			return false;
		}
		int status = api.status();
		return status == 400 || status == 401 || status == 403;
	}

	private <T> Flux<T> page(Tm1Request<T> request, URI uri) {
		return items(request,
				exchange(request, uri, response -> ODataCollectionDecoder.decode(response.bodyToFlux(DataBuffer.class),
						mapper, request.type(), request.method().name(), uri.toString())));
	}

	/**
	 * The items of one decoded page, then those of the pages after it, if it has a next
	 * link.
	 */
	private <T> Flux<T> items(Tm1Request<T> request, Flux<ODataCollectionDecoder.Event<T>> events) {
		return Flux.defer(() -> {
			String[] next = new String[1];
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
				odataHeaders(h, request.response());
				request.headers().forEach(h::set);
			})
				.cookie(COOKIE, session.cookie())
				.httpRequest(r -> responseTimeout(r.getNativeRequest(), request.timeout().orElse(timeouts.request())));
			Object body = request.body();
			WebClient.RequestHeadersSpec<?> ready;
			if (body == null) {
				ready = spec;
			}
			else if (body instanceof Tm1Request.Bytes bytes) {
				// TM1 (11.8) refuses a chunked body with 400, so the bytes are gathered
				// and sent with their length
				ready = spec.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.body(DataBufferUtils.join(bytes.content()), DataBuffer.class);
			}
			else {
				ready = spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json(body));
			}
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
			// Joined like SINGLE rather than bodyToMono(String), whose codec refuses
			// bodies over
			// 256 KB: $metadata alone is about 1 MB
			case TEXT -> DataBufferUtils.join(response.bodyToFlux(DataBuffer.class)).map(buffer -> {
				try {
					return request.type().cast(buffer.toString(StandardCharsets.UTF_8));
				}
				finally {
					DataBufferUtils.release(buffer);
				}
			});
			case SINGLE -> DataBufferUtils.join(response.bodyToFlux(DataBuffer.class)).map(buffer -> {
				try {
					byte[] bytes = new byte[buffer.readableByteCount()];
					buffer.read(bytes);
					return mapper.readValue(Utf8Repair.whole(bytes), request.type());
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
		return bytes(response).map(body -> errorFrom(status, body, method, uri));
	}

	/**
	 * A response's whole body, empty if it has none. Joined from its buffers rather than
	 * read with {@code bodyToMono(byte[].class)}, whose codec refuses bodies over 256 KB.
	 */
	private static Mono<byte[]> bytes(ClientResponse response) {
		return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class)).map(buffer -> {
			try {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				return bytes;
			}
			finally {
				DataBufferUtils.release(buffer);
			}
		}).defaultIfEmpty(new byte[0]);
	}

	/** The exception for an error status and its body, wherever the body came from. */
	Tm1ApiException errorFrom(int status, byte[] body, String method, URI uri) {
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
	}

	/**
	 * Reads a response that arrived whole, as a status and body: the result an async
	 * monitor unwraps, or an item of a batch. An error status becomes its exception, as
	 * for a response on the wire.
	 */
	<T> Flux<T> decode(Tm1Request<T> request, URI uri, int status, byte[] body) {
		String method = request.method().name();
		if (status >= 400) {
			return Flux.error(errorFrom(status, body, method, uri));
		}
		return switch (request.response()) {
			case NONE -> Flux.empty();
			case TEXT -> Flux.just(request.type().cast(new String(body, StandardCharsets.UTF_8)));
			case BYTES -> Flux.just(request.type().cast(DefaultDataBufferFactory.sharedInstance.wrap(body)));
			case SINGLE -> {
				if (body.length == 0) {
					yield Flux.empty();
				}
				try {
					yield Flux.just(mapper.readValue(Utf8Repair.whole(body), request.type()));
				}
				catch (JacksonException e) {
					yield Flux.error(new Tm1ProtocolException(
							"TM1 sent a response that isn't valid JSON for " + request.type().getSimpleName(), null,
							method, uri.toString(), e));
				}
			}
			// A next link is followed with ordinary requests, as stream() does: the rest
			// of the collection isn't part of the async result or the batch
			case COLLECTION -> items(request,
					ODataCollectionDecoder.decode(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)), mapper,
							request.type(), method, uri.toString()));
		};
	}

	/**
	 * Sends {@code request} with {@code Prefer: respond-async}. If TM1 accepts it (202,
	 * with the monitor's {@code Location}), polls the monitor every {@code every} until
	 * it holds the result, an {@code application/http} message, and reads that as the
	 * request's response. If TM1 answers at once, reads that. Cancelling before the
	 * result deletes the monitor, which stops the work on TM1.
	 */
	<T> Flux<T> async(Tm1Request<T> request, Duration every) {
		URI uri = uri(request.relativeUri());
		return exchange(request.withHeader("Prefer", "respond-async"), uri, response -> {
			if (response.statusCode().value() != 202) {
				// TM1 answered at once: read it as an ordinary response
				return request.response() == Tm1Request.Response.COLLECTION
						? bytes(response).flatMapMany(body -> decode(request, uri, response.statusCode().value(), body))
						: read(request, uri, response).flux();
			}
			String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
			if (location == null) {
				return response.releaseBody()
					.thenMany(Flux.error(new Tm1ProtocolException("TM1 accepted an async request without a Location",
							null, request.method().name(), uri.toString(), null)));
			}
			// The Location is relative to the request's URL: ../_async('id')
			return response.releaseBody().thenMany(monitor(request, uri, uri.resolve(location), every));
		});
	}

	private <T> Flux<T> monitor(Tm1Request<T> request, URI uri, URI monitor, Duration every) {
		Tm1Request<DataBuffer> poll = Tm1Request.bytes(ODataPath.link(URI.create(root).relativize(monitor).getPath()));
		AtomicBoolean finished = new AtomicBoolean();
		Mono<byte[]> once = exchange(poll, monitor, response -> {
			if (response.statusCode().value() == 202) {
				return response.releaseBody().thenMany(Flux.<byte[]>empty());
			}
			return bytes(response).flux();
		}).next();
		Flux<T> result = Mono.defer(() -> once)
			.repeatWhenEmpty(Integer.MAX_VALUE, polls -> polls.concatMap(n -> Mono.delay(every)))
			.doOnNext(message -> finished.set(true))
			.flatMapMany(message -> {
				AsyncResult unwrapped = AsyncResult.parse(message);
				return decode(request, uri, unwrapped.status(), unwrapped.body());
			});
		// Deleting the monitor before the result is what stops the work on TM1
		return Flux.usingWhen(Mono.just(monitor), m -> result, m -> Mono.empty(), (m, e) -> Mono.empty(),
				m -> finished.get() ? Mono.empty()
						: exchange(Tm1Request.delete(poll.path()), m,
								response -> response.releaseBody().<Void>thenMany(Flux.empty()))
							.then()
							.onErrorResume(e -> {
								log.debug("Couldn't delete the async monitor {}", m, e);
								return Mono.empty();
							}));
	}

	private <R> Mono<R> error(ClientResponse response, String method, URI uri) {
		return errorOf(response, method, uri).flatMap(Mono::error);
	}

	// Login and close

	private Mono<LoggedIn> login(@Nullable String seedCookie, String reason) {
		URI uri = uri(LOGIN_PATH);
		// The authenticator's own failures (say, a passport it couldn't get) reach the
		// caller unchanged; only the login request's are mapped. It gets the login's
		// timeout too: it may ask another server (Cognos, for a passport), and one that
		// never answers would otherwise hold every request, and close(), for ever.
		Mono<LoggedIn> login = authenticator.authorization()
			.switchIfEmpty(Mono.error(() -> new IllegalStateException(
					"The authenticator " + authenticator.describe() + " supplied no credentials")))
			.timeout(timeouts.login(),
					Mono.error(() -> new Tm1TransportException(Tm1TransportException.Phase.CONNECT, true, "GET",
							uri.toString(),
							new TimeoutException("The authenticator " + authenticator.describe()
									+ " supplied no credentials within " + timeouts.login()))))
			.flatMap(authorization -> sendLogin(uri, authorization, seedCookie));
		return observe("tm1.session.login", "tm1 login", reason, login)
			.doOnNext(session -> log.info("Logged in to TM1 at {} as {} (session {}, {})", root,
					authenticator.describe(), session.sessionId(), reason))
			.doOnError(e -> log.debug("Login to TM1 at {} failed", root, e));
	}

	private Mono<LoggedIn> sendLogin(URI uri, String authorization, @Nullable String seedCookie) {
		return Mono.defer(() -> {
			WebClient.RequestHeadersSpec<?> spec = web.get().uri(uri).headers(h -> {
				odataHeaders(h, Tm1Request.Response.SINGLE);
				h.set(HttpHeaders.AUTHORIZATION, authorization);
				if (impersonate != null) {
					// At login only: TM1 decides the identity then and ignores the header
					// on later
					// requests (Tm1ImpersonationBehaviorTest)
					h.set(IMPERSONATE, HeaderText.utf8(impersonate));
				}
				if (sessionContext != null) {
					h.set(SESSION_CONTEXT, HeaderText.utf8(sessionContext));
				}
			}).httpRequest(r -> responseTimeout(r.getNativeRequest(), timeouts.login()));
			if (seedCookie != null) {
				// Log in the session TM1 opened for a 401, rather than opening another
				spec = spec.cookie(COOKIE, seedCookie);
			}
			return spec.exchangeToMono(response -> {
				int status = response.statusCode().value();
				if (status == 401) {
					// TM1 answers a disabled impersonated user exactly like wrong
					// credentials
					ODataError why = impersonate == null ? null
							: new ODataError("", "The credentials were refused, or the user being impersonated ("
									+ impersonate + ") is disabled", null, List.of(), null);
					return response.releaseBody()
						.then(Mono.error(
								Tm1ApiException.of(401, Tm1ErrorKind.CREDENTIAL_REJECTED, why, "GET", uri.toString())));
				}
				if (status >= 400) {
					return errorOf(response, "GET", uri).flatMap(e -> Mono.error(impersonationError(e, uri)));
				}
				String cookie = cookie(response);
				String value = cookie != null ? cookie : seedCookie;
				return response.bodyToMono(byte[].class).map(body -> loggedIn(value, body, uri));
			});
		}).onErrorMap(e -> Transport.map(e, "GET", uri.toString(), true));
	}

	/**
	 * A login that impersonates and gets a 400 or 403 was refused for the impersonation:
	 * the user doesn't exist (400), or the logged-in user may not impersonate them (403).
	 * The classifier recognizes TM1 11.8's messages; this keeps the kind right if another
	 * version words them differently.
	 */
	private Throwable impersonationError(Throwable error, URI uri) {
		if (impersonate == null || !(error instanceof Tm1ApiException api)) {
			return error;
		}
		if (api.status() == 400 && api.kind() != Tm1ErrorKind.UNKNOWN_USER) {
			return Tm1ApiException.of(400, Tm1ErrorKind.UNKNOWN_USER, api.error(), "GET", uri.toString());
		}
		if (api.status() == 403 && api.kind() != Tm1ErrorKind.IMPERSONATION_REFUSED) {
			return Tm1ApiException.of(403, Tm1ErrorKind.IMPERSONATION_REFUSED, api.error(), "GET", uri.toString());
		}
		return error;
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
		Mono<Void> close = web.post().uri(uri).headers(h -> {
			odataHeaders(h, Tm1Request.Response.SINGLE);
			// TM1 sometimes drops the connection that carried a close, so the next
			// request to
			// reuse it gets no response at all. Don't put it back in the pool.
			h.set(HttpHeaders.CONNECTION, "close");
		})
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

	URI uri(String relative) {
		// Already encoded by ODataPath and ODataQuery: never through a URI template,
		// which would
		// read the braces in TM1 control object names as variables (plan §16.2)
		return URI.create(root + relative);
	}

	private static void odataHeaders(HttpHeaders headers, Tm1Request.Response response) {
		headers.set("OData-Version", "4.0");
		headers.set("OData-MaxVersion", "4.0");
		headers.set(HttpHeaders.ACCEPT, accept(response));
	}

	/** The media types a request accepts, by how its response is read. */
	static String accept(Tm1Request.Response response) {
		return switch (response) {
			case TEXT -> "text/plain, application/json";
			case BYTES -> "*/*";
			default -> MediaType.APPLICATION_JSON_VALUE;
		};
	}

	private static void responseTimeout(Object nativeRequest, Duration timeout) {
		// Only Reactor Netty supports an idle timeout per request; other connectors use
		// their own settings
		if (nativeRequest instanceof HttpClientRequest netty) {
			netty.responseTimeout(timeout);
		}
	}

	byte[] json(Object body) {
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

		/** When TM1 refused this login ({@link System#nanoTime()} scale), or never. */
		volatile long refusedAt = NOT_REFUSED;

		Attempt(@Nullable String seedCookie, String reason) {
			// cache(): concurrent requests share one login. A failed login is forgotten,
			// so
			// the next request tries again, unless TM1 refused it: then it's kept for a
			// while
			// (see failed()).
			this.login = Mono.defer(() -> login(seedCookie, reason)).doOnError(e -> failed(this, e)).cache();
		}

		/**
		 * Whether TM1 refused this login recently enough that it shouldn't be tried
		 * again.
		 */
		boolean refusedRecently() {
			long at = refusedAt;
			return at != NOT_REFUSED && nanoTime.getAsLong() - at < timeouts.refusedLogin().toNanos();
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
