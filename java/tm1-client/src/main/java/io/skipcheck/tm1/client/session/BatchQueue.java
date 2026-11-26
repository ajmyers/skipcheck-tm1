// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import io.skipcheck.tm1.client.odata.ODataPath;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The requests of a batch, queued as they're made, and sent as JSON {@code $batch}
 * requests when the batch runs. Each request's result is held for its caller, so a caller
 * can subscribe before or after the run.
 */
final class BatchQueue {

	private final Connection connection;

	private final ObjectMapper mapper;

	private final List<Entry> entries = new ArrayList<>();

	private boolean ran;

	BatchQueue(Connection connection, ObjectMapper mapper) {
		this.connection = connection;
		this.mapper = mapper;
	}

	/** Queues a request, and returns its result, there once the batch has run. */
	<T> Flux<T> add(Tm1Request<T> request) {
		if (request.response() == Tm1Request.Response.BYTES || request.body() instanceof Tm1Request.Bytes) {
			return Flux.error(new IllegalArgumentException("Raw bytes can't go in a JSON batch: " + request));
		}
		// Written now, so a body that can't be written fails its own call, where it was
		// made, rather than the run and every other call with it
		JsonNode body;
		try {
			body = request.body() == null ? null : mapper.readTree(connection.json(request.body()));
		}
		catch (JacksonException e) {
			return Flux.error(new IllegalArgumentException(
					"The request body isn't JSON, so it can't go in a batch: " + request, e));
		}
		catch (IllegalArgumentException e) {
			return Flux.error(e);
		}
		Sinks.One<Item> sink = Sinks.one();
		synchronized (this) {
			if (ran) {
				return Flux.error(new IllegalStateException(
						"The batch has already run, so this call can't join it: a call whose request depends on "
								+ "another's result can't be batched. " + request));
			}
			entries.add(new Entry(String.valueOf(entries.size() + 1), request, body, sink));
		}
		URI uri = connection.uri(request.relativeUri());
		return sink.asMono().flatMapMany(item -> connection.decode(request, uri, item.status(), item.body()));
	}

	/** How many requests are queued. */
	synchronized int size() {
		return entries.size();
	}

	/** Sends the queued requests, at most {@code perRequest} to each {@code $batch}. */
	Mono<Void> run(int perRequest) {
		return Mono.defer(() -> {
			List<Entry> all;
			synchronized (this) {
				if (ran) {
					return Mono.error(new IllegalStateException("The batch has already run"));
				}
				ran = true;
				all = List.copyOf(entries);
			}
			return Flux.fromIterable(all).concatMap(this::supported).collectList().flatMap(sendable -> {
				List<List<Entry>> chunks = new ArrayList<>();
				for (int i = 0; i < sendable.size(); i += perRequest) {
					chunks.add(sendable.subList(i, Math.min(sendable.size(), i + perRequest)));
				}
				return Flux.fromIterable(chunks).concatMap(this::send).then();
			});
		});
	}

	/**
	 * The entry, if the server defines what its request requires. If it doesn't (or that
	 * can't be found out), the entry's call fails and it isn't sent, as outside a batch.
	 */
	private Mono<Entry> supported(Entry entry) {
		if (entry.request().requirement().isEmpty()) {
			return Mono.just(entry);
		}
		// As outside a batch: the body may lose a parameter the server lacks whose
		// value is what leaving it out means
		// The copy queued, the one the batch sends, not the caller's object as it is now
		return connection.prepare(entry.request().withBody(entry.body()))
			.map(ready -> ready == entry.request() ? entry
					: new Entry(entry.id(), ready,
							ready.body() == null ? null : mapper.readTree(connection.json(ready.body())), entry.sink()))
			.onErrorResume(e -> {
				entry.sink().tryEmitError(e);
				return Mono.empty();
			});
	}

	private Mono<Void> send(List<Entry> chunk) {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode requests = body.putArray("requests");
		for (Entry entry : chunk) {
			requests.add(json(entry));
		}
		return connection.execute(Tm1Request.post(ODataPath.batch(), body, JsonNode.class))
			.doOnNext(response -> deliver(chunk, response))
			// No body is no answers: without this the calls would wait for ever
			.switchIfEmpty(Mono.error(() -> new Tm1ProtocolException("TM1 answered the batch with no body", null,
					"POST", connection.uri(ODataPath.batch().toString()).toString(), null)))
			.doOnError(e -> chunk.forEach(entry -> entry.sink().tryEmitError(e)))
			.onErrorResume(e -> Mono.empty())
			.then();
	}

	private ObjectNode json(Entry entry) {
		Tm1Request<?> request = entry.request();
		ObjectNode json = mapper.createObjectNode();
		json.put("id", entry.id());
		json.put("method", request.method().name());
		json.put("url", request.relativeUri());
		ObjectNode headers = json.putObject("headers");
		// TM1 (11.8) gives a request of a batch the batch's own Accept unless it has one,
		// and refuses plain text ($count, $value) under the batch's application/json. It
		// reads a header's name as written, and only knows this one in lower case.
		headers.put("accept", Connection.accept(request.response()));
		request.headers().forEach(headers::put);
		if (entry.body() != null) {
			headers.put("content-type", "application/json");
			json.set("body", entry.body());
		}
		return json;
	}

	private void deliver(List<Entry> chunk, JsonNode response) {
		Map<String, JsonNode> byId = new LinkedHashMap<>();
		for (JsonNode item : response.path("responses")) {
			byId.put(item.path("id").asString(""), item);
		}
		for (Entry entry : chunk) {
			JsonNode item = byId.get(entry.id());
			if (item == null) {
				entry.sink()
					.tryEmitError(
							new Tm1ProtocolException("TM1's batch response has no answer for request " + entry.id(),
									null, entry.request().method().name(), entry.request().relativeUri(), null));
				continue;
			}
			entry.sink().tryEmitValue(new Item(item.path("status").asInt(0), bytes(item.get("body"))));
		}
	}

	private byte[] bytes(@Nullable JsonNode body) {
		if (body == null || body.isNull() || body.isMissingNode()) {
			return new byte[0];
		}
		if (body.isString()) {
			return body.asString().getBytes(StandardCharsets.UTF_8);
		}
		try {
			return mapper.writeValueAsBytes(body);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("A batch item's body can't be written back as JSON", e);
		}
	}

	private record Entry(String id, Tm1Request<?> request, @Nullable JsonNode body, Sinks.One<Item> sink) {
	}

	/**
	 * One answer of a batch: its status, and its body as the bytes TM1 would have sent.
	 */
	private static final class Item {

		private final int status;

		private final byte[] body;

		Item(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}

		int status() {
			return status;
		}

		byte[] body() {
			return body;
		}

	}

	@Override
	public String toString() {
		return "BatchQueue[" + size() + " requests" + (ran ? ", ran" : "") + "]";
	}

}
