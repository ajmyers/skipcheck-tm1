package net.ajmyers.tm1.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpMethod;

import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * One TM1 REST call, described but not sent: the method, path, query, headers and body, and
 * how to read the response.
 *
 * <p>Every service method is built on one of these. The same request can be sent on its own
 * (the service's ordinary methods do that) or added to a {@code $batch}, so anything the client
 * can do one request at a time, it can also batch. Requests are immutable.
 *
 * <pre>{@code
 * Tm1Request<JsonNode> cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"), JsonNode.class);
 * Tm1Request<Void> reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), Map.of(), Void.class);
 * }</pre>
 *
 * @param <T> the type the response is read as: an entity or value for a single response, the
 *     item type for a {@linkplain Response#COLLECTION collection}, {@code String} for text, or
 *     {@code Void} for no content
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
	private final Object body;
	private final Response response;
	private final Class<T> type;

	private Tm1Request(HttpMethod method, ODataPath path, ODataQuery query, Map<String, String> headers, Object body, Response response,
			Class<T> type) {
		this.method = Objects.requireNonNull(method, "method");
		this.path = Objects.requireNonNull(path, "path");
		this.query = Objects.requireNonNull(query, "query");
		this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
		this.body = body;
		this.response = Objects.requireNonNull(response, "response");
		this.type = Objects.requireNonNull(type, "type");
	}

	/** {@code GET} of one entity or value. */
	public static <T> Tm1Request<T> get(ODataPath path, ODataQuery query, Class<T> type) {
		return new Tm1Request<>(HttpMethod.GET, path, query, Map.of(), null, Response.SINGLE, type);
	}

	/** {@code GET} of a collection, read item by item as {@code itemType}. */
	public static <T> Tm1Request<T> list(ODataPath path, ODataQuery query, Class<T> itemType) {
		return new Tm1Request<>(HttpMethod.GET, path, query, Map.of(), null, Response.COLLECTION, itemType);
	}

	/** {@code GET} of plain text, such as {@code Configuration/ProductVersion/$value}. */
	public static Tm1Request<String> text(ODataPath path) {
		return new Tm1Request<>(HttpMethod.GET, path, ODataQuery.none(), Map.of(), null, Response.TEXT, String.class);
	}

	/**
	 * {@code POST}: create an entity, or invoke an action. Actions always get a JSON body (an
	 * empty object if there are no parameters), since TM1 refuses an action without a JSON
	 * content type. With {@code Void.class} the response is ignored.
	 */
	public static <T> Tm1Request<T> post(ODataPath path, Object body, Class<T> type) {
		return new Tm1Request<>(HttpMethod.POST, path, ODataQuery.none(), Map.of(), body == null ? Map.of() : body, responseFor(type), type);
	}

	/** {@code PATCH}: change the properties in {@code body}, leaving the rest as they are. */
	public static Tm1Request<Void> patch(ODataPath path, Object body) {
		return new Tm1Request<>(HttpMethod.PATCH, path, ODataQuery.none(), Map.of(), Objects.requireNonNull(body, "body"), Response.NONE, Void.class);
	}

	/** {@code DELETE}. */
	public static Tm1Request<Void> delete(ODataPath path) {
		return new Tm1Request<>(HttpMethod.DELETE, path, ODataQuery.none(), Map.of(), null, Response.NONE, Void.class);
	}

	/** A copy with these query options instead. */
	public Tm1Request<T> withQuery(ODataQuery query) {
		return new Tm1Request<>(method, path, query, headers, body, response, type);
	}

	/** A copy with a header added or replaced, such as {@code If-Match}. */
	public Tm1Request<T> withHeader(String name, String value) {
		Map<String, String> next = new LinkedHashMap<>(headers);
		next.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
		return new Tm1Request<>(method, path, query, next, body, response, type);
	}

	/** A copy sent with {@code If-Match: etag}, failing with 412 if the entity has changed since. */
	public Tm1Request<T> ifMatch(String etag) {
		return withHeader("If-Match", etag);
	}

	public HttpMethod method() {
		return method;
	}

	public ODataPath path() {
		return path;
	}

	public ODataQuery query() {
		return query;
	}

	public Map<String, String> headers() {
		return headers;
	}

	/** The body, serialized as JSON when sent, or {@code null} for none. */
	public Object body() {
		return body;
	}

	public Response response() {
		return response;
	}

	public Class<T> type() {
		return type;
	}

	/**
	 * Whether sending this twice has the same effect as sending it once: GET, PUT and DELETE
	 * are; POST (process runs, cell writes, creates) and PATCH aren't. The client never retries
	 * a non-idempotent request by itself.
	 */
	public boolean isIdempotent() {
		return method == HttpMethod.GET || method == HttpMethod.PUT || method == HttpMethod.DELETE || method == HttpMethod.HEAD;
	}

	/** The encoded path and query relative to the service root, e.g. {@code Cubes('Sales')?$select=Name}. */
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
