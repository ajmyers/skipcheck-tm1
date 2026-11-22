package net.ajmyers.tm1.client.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fake TM1 for conformance tests: answers the session's own requests (login, close,
 * {@code Server?$select=ProductVersion}, {@code $metadata}) by itself, and every other
 * request with the next scripted answer, recording each as an exchange: the request as
 * sent (method, target, the headers that matter, body) and the answer.
 *
 * <p>
 * Every client's replay runner answers the same session requests the same way, with the
 * same product version and the same {@code $metadata} snapshot, so a fixture holds only
 * what a service call itself sends.
 */
public final class ConformanceTm1 implements ExchangeFunction {

	public static final String ROOT = "http://tm1.test/api/v1/";

	/** The version every runner's fake reports. */
	public static final String PRODUCT_VERSION = "11.8.03500.6";

	/**
	 * The request headers a fixture records and every runner compares: lower-case names.
	 * Not cookies, credentials or the user agent, which are the session's, not the
	 * call's; and TM1-* headers too, except the session's own.
	 */
	static final Set<String> HEADERS = Set.of("accept", "content-type", "if-match", "if-none-match", "odata-version",
			"odata-maxversion", "prefer");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final byte[] SNAPSHOT = snapshot();

	private final Deque<Answer> answers = new ArrayDeque<>();

	private final List<ObjectNode> exchanges = new ArrayList<>();

	private final List<Recorded> requests = new ArrayList<>();

	/** A scripted answer: status, headers and a body (JSON, text, bytes or none). */
	@SuppressWarnings("ArrayRecordComponent")
	public record Answer(int status, Map<String, String> headers, @Nullable JsonNode json, @Nullable String text,
			byte @Nullable [] bytes) {
	}

	/** A request as the fake saw it. */
	@SuppressWarnings("ArrayRecordComponent")
	public record Recorded(String method, String target, Map<String, String> headers, byte[] body) {

		public @Nullable String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}

		public String text() {
			return new String(body, StandardCharsets.UTF_8);
		}

		public JsonNode json() {
			return JSON.readTree(body);
		}

		/** {@code METHOD target}. */
		public String key() {
			return method + " " + target;
		}
	}

	public Tm1Session session() {
		return Tm1Session.builder()
			.baseUrl(ROOT)
			.basicAuth("admin", "apple")
			.webClient(WebClient.builder().exchangeFunction(this))
			.build();
	}

	public Tm1Client client() {
		return Tm1Client.of(session());
	}

	/** Answers the next call's request with JSON. */
	public ConformanceTm1 json(int status, String json) {
		answers.add(new Answer(status, Map.of("content-type", "application/json"), JSON.readTree(json), null, null));
		return this;
	}

	/** Answers the next request with {@code 200} and JSON. */
	public ConformanceTm1 json(String json) {
		return json(200, json);
	}

	/** Answers the next request with {@code 200} and plain text. */
	public ConformanceTm1 text(String text) {
		answers.add(new Answer(200, Map.of("content-type", "text/plain"), null, text, null));
		return this;
	}

	/** Answers the next request with {@code 200} and bytes. */
	public ConformanceTm1 bytes(byte[] bytes) {
		answers.add(new Answer(200, Map.of("content-type", "application/octet-stream"), null, null, bytes));
		return this;
	}

	/** Answers the next request with a status and no body, such as {@code 204}. */
	public ConformanceTm1 status(int status) {
		answers.add(new Answer(status, Map.of(), null, null, null));
		return this;
	}

	/** Answers the next request with a TM1 error. */
	public ConformanceTm1 error(int status, String code, String message) {
		ObjectNode error = JSON.createObjectNode();
		error.putObject("error").put("code", code).put("message", message);
		return json(status, error.toString());
	}

	/** The call's requests, the session's own left out. */
	public List<Recorded> requests() {
		return List.copyOf(requests);
	}

	public Recorded last() {
		if (requests.isEmpty()) {
			throw new IllegalStateException("No request was sent");
		}
		return requests.get(requests.size() - 1);
	}

	/** The exchanges since the last call to this, in order, and forgets them. */
	List<ObjectNode> takeExchanges() {
		List<ObjectNode> taken = List.copyOf(exchanges);
		exchanges.clear();
		return taken;
	}

	/** Answers left over: a call that sends fewer requests than scripted is a mistake. */
	int unanswered() {
		return answers.size();
	}

	void reset() {
		answers.clear();
		exchanges.clear();
		requests.clear();
	}

	@Override
	public Mono<ClientResponse> exchange(ClientRequest request) {
		String target = target(request.url());
		String key = request.method().name() + " " + target;
		return switch (key) {
			case "GET ActiveSession?$select=ID" -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.header(HttpHeaders.SET_COOKIE, "TM1SessionId=c1; Path=/api/; HttpOnly")
				.body("{\"ID\":1}")
				.build());
			case "POST ActiveSession/tm1.Close" -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
			case "GET Server?$select=ProductVersion" -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.body("{\"ProductVersion\":\"" + PRODUCT_VERSION + "\"}")
				.build());
			case "GET $metadata" -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, "application/xml")
				.body(new String(SNAPSHOT, StandardCharsets.UTF_8))
				.build());
			default -> body(request).map(body -> answer(request, target, body));
		};
	}

	private ClientResponse answer(ClientRequest request, String target, byte[] body) {
		Map<String, String> headers = new TreeMap<>();
		request.headers()
			.forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
		Recorded recorded = new Recorded(request.method().name(), target, headers, body);
		requests.add(recorded);

		ObjectNode req = JSON.createObjectNode();
		req.put("method", recorded.method());
		req.put("target", target);
		ObjectNode h = req.putObject("headers");
		headers.forEach((name, value) -> {
			if (HEADERS.contains(name) || (name.startsWith("tm1-") && !name.equals("tm1-sessioncontext"))) {
				h.put(name, value);
			}
		});
		putBody(req, headers.get("content-type"), body);

		Answer answer = answers.poll();
		if (answer == null) {
			throw new IllegalStateException("No scripted answer for " + recorded.key());
		}
		ObjectNode res = JSON.createObjectNode();
		res.put("status", answer.status());
		ObjectNode rh = res.putObject("headers");
		new TreeMap<>(answer.headers()).forEach(rh::put);
		ClientResponse.Builder response = ClientResponse.create(HttpStatus.valueOf(answer.status()));
		answer.headers().forEach(response::header);
		if (answer.json() != null) {
			res.set("json", answer.json());
			response.body(answer.json().toString());
		}
		else if (answer.text() != null) {
			res.put("text", answer.text());
			response.body(answer.text());
		}
		else if (answer.bytes() != null) {
			res.put("bytes", Base64.getEncoder().encodeToString(answer.bytes()));
			response.body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(answer.bytes())));
		}
		ObjectNode exchange = JSON.createObjectNode();
		exchange.set("request", req);
		exchange.set("response", res);
		exchanges.add(exchange);
		return response.build();
	}

	/** A body by its content type: JSON, text or bytes; nothing if empty. */
	static void putBody(ObjectNode node, @Nullable String contentType, byte[] body) {
		if (body.length == 0) {
			return;
		}
		String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
		if (type.startsWith("application/json")) {
			node.set("json", JSON.readTree(body));
		}
		else if (type.startsWith("text/")) {
			node.put("text", new String(body, StandardCharsets.UTF_8));
		}
		else {
			node.put("bytes", Base64.getEncoder().encodeToString(body));
		}
	}

	/** The request's path and query relative to the root, as sent (encoded). */
	static String target(URI url) {
		String uri = url.toString();
		return uri.startsWith(ROOT) ? uri.substring(ROOT.length()) : uri;
	}

	@SuppressWarnings("unchecked")
	static Mono<byte[]> body(ClientRequest request) {
		MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
		BodyInserter<?, ? super MockClientHttpRequest> inserter = request.body();
		List<HttpMessageWriter<?>> writers = ExchangeStrategies.withDefaults().messageWriters();
		return inserter.insert(mock, new BodyInserter.Context() {
			@Override
			public List<HttpMessageWriter<?>> messageWriters() {
				return writers;
			}

			@Override
			public Optional<ServerHttpRequest> serverRequest() {
				return Optional.empty();
			}

			@Override
			public Map<String, Object> hints() {
				return new LinkedHashMap<>();
			}
		}).then(Mono.defer(() -> DataBufferUtils.join(mock.getBody()).map(buffer -> {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			DataBufferUtils.release(buffer);
			return bytes;
		}))).defaultIfEmpty(new byte[0]);
	}

	private static byte[] snapshot() {
		try (InputStream in = ConformanceTm1.class.getResourceAsStream("/metadata/tm1-schema-11.8.35.xml")) {
			if (in == null) {
				throw new IllegalStateException("The $metadata snapshot isn't on the test classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
