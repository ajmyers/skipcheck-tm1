package net.ajmyers.tm1.client;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.version.Tm1Capability;

/**
 * One TM1 REST call, described but not sent: the method, path, query, headers and body,
 * and how to read the response.
 *
 * <p>
 * Every service method is built on one of these. The same request can be sent on its own
 * (the service's ordinary methods do that) or added to a {@code $batch}, so anything the
 * client can do one request at a time, it can also batch. Requests are immutable.
 *
 * <pre>{@code
 * Tm1Request<JsonNode> cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"), JsonNode.class);
 * Tm1Request<Void> reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), Map.of(), Void.class);
 * }</pre>
 *
 * @param <T> the type the response is read as: an entity or value for a single response,
 * the item type for a {@linkplain Response#COLLECTION collection}, {@code String} for
 * text, or {@code Void} for no content
 */
public final class Tm1Request<T> {

	/** How the response body is read. */
	public enum Response {

		/** One JSON value, read as {@code T}. */
		SINGLE,
		/** A collection, {@code {"value":[...]}}, streamed item by item as {@code T}. */
		COLLECTION,
		/** Plain text, such as {@code $value} or {@code $count}. */
		TEXT,
		/** No content (or content that's ignored). */
		NONE

	}

	private final HttpMethod method;

	private final ODataPath path;

	private final ODataQuery query;

	private final Map<String, String> headers;

	private final @Nullable Object body;

	private final Response response;

	private final Class<T> type;

	private final @Nullable Duration timeout;

	private final @Nullable Tm1Capability requires;

	private Tm1Request(HttpMethod method, ODataPath path, ODataQuery query, Map<String, String> headers,
			@Nullable Object body, Response response, Class<T> type, @Nullable Duration timeout,
			@Nullable Tm1Capability requires) {
		this.method = Objects.requireNonNull(method, "method");
		this.path = Objects.requireNonNull(path, "path");
		this.query = Objects.requireNonNull(query, "query");
		this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
		this.body = body;
		this.response = Objects.requireNonNull(response, "response");
		this.type = Objects.requireNonNull(type, "type");
		this.timeout = timeout;
		this.requires = requires;
	}

	/**
	 * {@code GET} of one entity or value.
	 * @param <T> the type the response is read as
	 * @param path the resource path
	 * @param query the query options
	 * @param type the class the response is read as
	 * @return the request
	 */
	public static <T> Tm1Request<T> get(ODataPath path, ODataQuery query, Class<T> type) {
		return new Tm1Request<>(HttpMethod.GET, path, query, Map.of(), null, Response.SINGLE, type, null, null);
	}

	/**
	 * {@code GET} of a collection, read item by item as {@code itemType}.
	 * @param <T> the type each item is read as
	 * @param path the collection's path
	 * @param query the query options
	 * @param itemType the class each item is read as
	 * @return the request
	 */
	public static <T> Tm1Request<T> list(ODataPath path, ODataQuery query, Class<T> itemType) {
		return new Tm1Request<>(HttpMethod.GET, path, query, Map.of(), null, Response.COLLECTION, itemType, null, null);
	}

	/**
	 * {@code GET} of plain text, such as {@code Configuration/ProductVersion/$value}.
	 * @param path the resource path
	 * @return the request
	 */
	public static Tm1Request<String> text(ODataPath path) {
		return new Tm1Request<>(HttpMethod.GET, path, ODataQuery.none(), Map.of(), null, Response.TEXT, String.class,
				null, null);
	}

	/**
	 * {@code POST} whose response is a collection, read item by item as {@code itemType}:
	 * an action returning {@code Collection(...)}, such as {@code tm1.CheckRules}.
	 * @param <T> the type each item is read as
	 * @param path the action's path
	 * @param body the parameters, written as JSON
	 * @param itemType the class each item is read as
	 * @return the request
	 */
	public static <T> Tm1Request<T> postList(ODataPath path, Object body, Class<T> itemType) {
		return new Tm1Request<>(HttpMethod.POST, path, ODataQuery.none(), Map.of(),
				Objects.requireNonNull(body, "body"), Response.COLLECTION, itemType, null, null);
	}

	/**
	 * {@code POST}: create an entity, or invoke an action. Actions always get a JSON body
	 * (an empty object if there are no parameters), since TM1 refuses an action without a
	 * JSON content type. With {@code Void.class} the response is ignored.
	 * @param <T> the type the response is read as
	 * @param path the collection or action path
	 * @param body the entity or action parameters, or {@code null} for an empty object
	 * @param type the class the response is read as: {@code Void.class} to ignore it,
	 * {@code String.class} to read it as text
	 * @return the request
	 */
	public static <T> Tm1Request<T> post(ODataPath path, @Nullable Object body, Class<T> type) {
		return new Tm1Request<>(HttpMethod.POST, path, ODataQuery.none(), Map.of(), body == null ? Map.of() : body,
				responseFor(type), type, null, null);
	}

	/**
	 * {@code PATCH}: change the properties in {@code body}, leaving the rest as they are.
	 * @param path the entity's path
	 * @param body the properties to change
	 * @return the request
	 */
	public static Tm1Request<Void> patch(ODataPath path, Object body) {
		return new Tm1Request<>(HttpMethod.PATCH, path, ODataQuery.none(), Map.of(),
				Objects.requireNonNull(body, "body"), Response.NONE, Void.class, null, null);
	}

	/**
	 * {@code DELETE}.
	 * @param path the path of the entity to delete
	 * @return the request
	 */
	public static Tm1Request<Void> delete(ODataPath path) {
		return new Tm1Request<>(HttpMethod.DELETE, path, ODataQuery.none(), Map.of(), null, Response.NONE, Void.class,
				null, null);
	}

	/**
	 * A copy with these query options instead.
	 * @param query the query options
	 * @return the copy
	 */
	public Tm1Request<T> withQuery(ODataQuery query) {
		return new Tm1Request<>(method, path, query, headers, body, response, type, timeout, requires);
	}

	/**
	 * A copy with a header added or replaced, such as {@code If-Match}.
	 * @param name the header name
	 * @param value the header value
	 * @return the copy
	 */
	public Tm1Request<T> withHeader(String name, String value) {
		Map<String, String> next = new LinkedHashMap<>(headers);
		next.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
		return new Tm1Request<>(method, path, query, next, body, response, type, timeout, requires);
	}

	/**
	 * A copy that may go {@code timeout} without receiving any data before it fails,
	 * instead of the session's request timeout. For calls TM1 only answers when the work
	 * is done, such as a long process run.
	 * @param timeout the longest wait for data; must be positive
	 * @return the copy
	 */
	public Tm1Request<T> withTimeout(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive: " + timeout);
		}
		return new Tm1Request<>(method, path, query, headers, body, response, type, timeout, requires);
	}

	/**
	 * A copy that's only sent if the server defines {@code capability}: before sending,
	 * the session checks the server's {@code $metadata} and otherwise fails with
	 * {@link net.ajmyers.tm1.client.error.Tm1UnsupportedOperationException}, without
	 * calling the server (plan §9). Service methods for newer endpoints declare it.
	 * @param capability what the request needs the server to define
	 * @return the copy
	 */
	public Tm1Request<T> requires(Tm1Capability capability) {
		return new Tm1Request<>(method, path, query, headers, body, response, type, timeout,
				Objects.requireNonNull(capability, "capability"));
	}

	/**
	 * What this request needs the server to define, if anything.
	 * @return the capability, or empty
	 */
	public Optional<Tm1Capability> requirement() {
		return Optional.ofNullable(requires);
	}

	/**
	 * A copy sent with {@code If-Match: etag}, failing with 412 if the entity has changed
	 * since.
	 * @param etag the entity's ETag
	 * @return the copy
	 */
	public Tm1Request<T> ifMatch(String etag) {
		return withHeader("If-Match", etag);
	}

	/**
	 * The HTTP method.
	 * @return the method
	 */
	public HttpMethod method() {
		return method;
	}

	/**
	 * The resource path, relative to the service root.
	 * @return the path
	 */
	public ODataPath path() {
		return path;
	}

	/**
	 * The query options.
	 * @return the query, possibly {@link ODataQuery#none()}
	 */
	public ODataQuery query() {
		return query;
	}

	/**
	 * The headers this request adds, in the order they were added.
	 * @return an unmodifiable map of header names to values
	 */
	public Map<String, String> headers() {
		return headers;
	}

	/**
	 * The body, serialized as JSON when sent, or {@code null} for none.
	 * @return the body, or {@code null}
	 */
	public @Nullable Object body() {
		return body;
	}

	/**
	 * How the response body is read.
	 * @return the response kind
	 */
	public Response response() {
		return response;
	}

	/**
	 * The class the response, or each item of a collection, is read as.
	 * @return the type
	 */
	public Class<T> type() {
		return type;
	}

	/**
	 * This request's own timeout, if it has one; otherwise the session's applies.
	 * @return the timeout, or empty
	 */
	public Optional<Duration> timeout() {
		return Optional.ofNullable(timeout);
	}

	/**
	 * Whether sending this twice has the same effect as sending it once: GET, PUT and
	 * DELETE are; POST (process runs, cell writes, creates) and PATCH aren't. The client
	 * never retries a non-idempotent request by itself.
	 * @return {@code true} for GET, PUT, DELETE and HEAD
	 */
	public boolean isIdempotent() {
		// equals, not ==: HttpMethod is a class, and a method parsed from text needn't be
		// the constant
		return HttpMethod.GET.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.DELETE.equals(method)
				|| HttpMethod.HEAD.equals(method);
	}

	/**
	 * The encoded path and query relative to the service root, e.g.
	 * {@code Cubes('Sales')?$select=Name}.
	 * @return the relative URI
	 */
	public String relativeUri() {
		String q = query.toQueryString();
		return q.isEmpty() ? path.toString() : path + "?" + q;
	}

	/** {@code METHOD relative-uri}, unencoded, for logs. */
	@Override
	public String toString() {
		String q = query.toString();
		return method.name() + " " + path + (q.isEmpty() ? "" : "?" + q);
	}

	private static Response responseFor(Class<?> type) {
		return type == Void.class ? Response.NONE : type == String.class ? Response.TEXT : Response.SINGLE;
	}

}
