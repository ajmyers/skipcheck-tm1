// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.odata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import io.skipcheck.tm1.client.error.ODataError;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Decodes an OData collection response, {@code {"@odata.context":..., "value":[...]}},
 * item by item as the bytes arrive, without ever holding the whole response.
 *
 * <p>
 * TM1 doesn't page its responses ({@code odata.maxpagesize} is ignored), so a collection
 * such as a large dimension's elements arrives as one body of any size. This decoder
 * feeds Jackson's non-blocking parser with each buffer as it comes, and emits each item
 * of {@code value} as soon as it's complete. Demand is respected: buffers are only
 * requested as items are consumed.
 *
 * <p>
 * Top-level control information ({@code @odata.nextLink}, {@code @odata.count},
 * {@code @odata.deltaLink} and so on) is emitted as {@link Event.Control} events,
 * wherever it appears in the body. {@link #follow} uses {@code @odata.nextLink} to fetch
 * further pages.
 *
 * <p>
 * A body that turns out to be an error, or that ends before the collection is complete,
 * fails with a {@link Tm1ProtocolException}, even if items were already emitted (OData
 * Part 1 §9.5).
 *
 * <p>
 * Bytes that aren't UTF-8 are read as U+FFFD rather than failing the response: TM1 (11.8)
 * sends them in the transaction log ({@link Utf8Repair}).
 */
public final class ODataCollectionDecoder {

	private ODataCollectionDecoder() {
	}

	/**
	 * An item or a piece of control information from a collection response.
	 *
	 * @param <T> the type of the collection's items
	 */
	public sealed interface Event<T> {

		/**
		 * One member of {@code value}.
		 *
		 * @param <T> the type of the collection's items
		 * @param value the member, or {@code null} for a null member of a collection of
		 * primitive values
		 */
		record Item<T>(T value) implements Event<T> {
		}

		/**
		 * A top-level property other than {@code value}, such as {@code @odata.nextLink}.
		 *
		 * @param <T> the type of the collection's items
		 * @param name the property's name, e.g. {@code @odata.nextLink}
		 * @param value the property's value, as JSON
		 */
		record Control<T>(String name, JsonNode value) implements Event<T> {
		}

	}

	/**
	 * Decodes one response body.
	 * @param body the response body; each buffer is released once read
	 * @param mapper the mapper to read items with
	 * @param itemType the type to read each item as
	 * @param method the request's method, for error messages
	 * @param url the request's URL, for error messages
	 * @param <T> the type of the collection's items
	 * @return the items and control information, in the order they appear in the body
	 */
	public static <T> Flux<Event<T>> decode(Flux<DataBuffer> body, ObjectMapper mapper, Class<T> itemType,
			String method, String url) {
		Objects.requireNonNull(mapper, "mapper");
		Objects.requireNonNull(itemType, "itemType");
		return Flux.defer(() -> {
			State<T> state = new State<>(mapper.readerFor(itemType), method, url);
			return body.concatMapIterable(state::feed).concatWith(Flux.defer(() -> Flux.fromIterable(state.end())));
		});
	}

	/**
	 * Just the items of one response body. Null members (possible in a collection of
	 * primitive values) are skipped, since a Reactor stream can't carry nulls; use
	 * {@link #decode} to see them.
	 * @param body the response body; each buffer is released once read
	 * @param mapper the mapper to read items with
	 * @param itemType the type to read each item as
	 * @param method the request's method, for error messages
	 * @param url the request's URL, for error messages
	 * @param <T> the type of the collection's items
	 * @return the non-null items, in order
	 */
	public static <T> Flux<T> items(Flux<DataBuffer> body, ObjectMapper mapper, Class<T> itemType, String method,
			String url) {
		Flux<Event<T>> events = decode(body, mapper, itemType, method, url);
		return events.<T>handle((event, sink) -> {
			if (event instanceof Event.Item<T> item && item.value() != null) {
				sink.next(item.value());
			}
		});
	}

	/**
	 * The items of a collection across all its pages: decodes {@code firstPage}, then, if
	 * it had an {@code @odata.nextLink}, the page {@code fetch} returns for that link,
	 * and so on. Next links are opaque: they're passed to {@code fetch} exactly as
	 * received (OData Part 1 §11.2.6.7).
	 * @param firstPage the first page's response body; each buffer is released once read
	 * @param fetch fetches the page at a next link, returning its response body
	 * @param mapper the mapper to read items with
	 * @param itemType the type to read each item as
	 * @param method the request's method, for error messages
	 * @param url the first request's URL, for error messages; later pages use their next
	 * link
	 * @param <T> the type of the collection's items
	 * @return the non-null items of every page, in order
	 */
	public static <T> Flux<T> follow(Flux<DataBuffer> firstPage, Function<String, Flux<DataBuffer>> fetch,
			ObjectMapper mapper, Class<T> itemType, String method, String url) {
		return Flux.defer(() -> {
			String[] next = new String[1];
			Flux<Event<T>> events = decode(firstPage, mapper, itemType, method, url);
			Flux<T> page = events.<T>handle((event, sink) -> {
				if (event instanceof Event.Item<T> item && item.value() != null) {
					sink.next(item.value());
				}
				else if (event instanceof Event.Control<T> control && ODataJson.NEXT_LINK.equals(control.name())
						&& control.value().isString()) {
					next[0] = control.value().asString();
				}
			});
			return page.concatWith(Flux.defer(() -> next[0] == null ? Flux.empty()
					: follow(fetch.apply(next[0]), fetch, mapper, itemType, method, next[0])));
		});
	}

	/**
	 * Parser state for one body: where we are in the JSON, and the item being collected.
	 */
	private static final class State<T> {

		private final ObjectReader itemReader;

		private final ObjectReader nodeReader;

		private final String method;

		private final String url;

		private final JsonParser parser;

		private final ByteArrayFeeder feeder;

		private final Utf8Repair utf8 = new Utf8Repair();

		private boolean started;

		private boolean finished;

		private @Nullable String field;

		private boolean inValue;

		private @Nullable TokenBuffer current;

		private int currentDepth;

		private @Nullable String currentControl;

		State(ObjectReader itemReader, String method, String url) {
			this.itemReader = itemReader;
			this.nodeReader = itemReader.forType(JsonNode.class);
			this.method = method;
			this.url = url;
			this.parser = itemReader.createNonBlockingByteArrayParser();
			this.feeder = (ByteArrayFeeder) parser.nonBlockingInputFeeder();
		}

		List<Event<T>> feed(DataBuffer buffer) {
			byte[] bytes;
			try {
				bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
			}
			finally {
				DataBufferUtils.release(buffer);
			}
			try {
				bytes = utf8.feed(bytes);
				feeder.feedInput(bytes, 0, bytes.length);
				return drain();
			}
			catch (JacksonException e) {
				throw new Tm1ProtocolException("TM1 sent malformed JSON: " + e.getOriginalMessage(), null, method, url,
						e);
			}
		}

		List<Event<T>> end() {
			try {
				byte[] rest = utf8.end();
				feeder.feedInput(rest, 0, rest.length);
				feeder.endOfInput();
				List<Event<T>> events = drain();
				if (!finished) {
					throw new Tm1ProtocolException(started ? "The response ended before the collection was complete"
							: "The response was empty", null, method, url, null);
				}
				return events;
			}
			catch (JacksonException e) {
				// Input ran out partway through a token: the response was cut short, e.g.
				// a dropped connection
				throw new Tm1ProtocolException(
						"The response ended before the collection was complete (" + e.getOriginalMessage() + ")", null,
						method, url, e);
			}
		}

		private List<Event<T>> drain() {
			List<Event<T>> events = new ArrayList<>();
			JsonToken token;
			while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
				if (finished) {
					throw new Tm1ProtocolException("Unexpected content after the collection", null, method, url, null);
				}
				handle(token, events);
			}
			return events;
		}

		private void handle(JsonToken token, List<Event<T>> events) {
			if (!started) {
				if (token != JsonToken.START_OBJECT) {
					throw new Tm1ProtocolException("Expected a JSON object with a 'value' array, got " + token, null,
							method, url, null);
				}
				started = true;
				return;
			}
			if (current != null) {
				collect(token, events);
				return;
			}
			if (inValue) {
				if (token == JsonToken.END_ARRAY) {
					inValue = false;
					return;
				}
				begin(token, null);
				if (currentDepth == 0) {
					events.add(new Event.Item<>(readItem()));
				}
				return;
			}
			// At the top level of the response object
			if (token == JsonToken.END_OBJECT) {
				finished = true;
				return;
			}
			if (token == JsonToken.PROPERTY_NAME) {
				field = parser.currentName();
				return;
			}
			if ("value".equals(field) && token == JsonToken.START_ARRAY) {
				inValue = true;
				return;
			}
			begin(token, field);
			if (currentDepth == 0) {
				events.add(control());
			}
		}

		/**
		 * Starts collecting one value (an item or a control property), which may be a
		 * single token.
		 */
		private void begin(JsonToken token, @Nullable String control) {
			TokenBuffer buffer = TokenBuffer.forBuffering(parser, parser.objectReadContext());
			buffer.copyCurrentEvent(parser);
			current = buffer;
			currentControl = control;
			currentDepth = token.isStructStart() ? 1 : 0;
		}

		/** The value being collected; only called while one is. */
		private TokenBuffer buffering() {
			return Objects.requireNonNull(current, "current");
		}

		private void collect(JsonToken token, List<Event<T>> events) {
			buffering().copyCurrentEvent(parser);
			if (token.isStructStart()) {
				currentDepth++;
			}
			else if (token.isStructEnd()) {
				currentDepth--;
			}
			if (currentDepth == 0) {
				events.add(currentControl == null ? new Event.Item<>(readItem()) : control());
			}
		}

		private T readItem() {
			try (JsonParser item = buffering().asParser(parser.objectReadContext())) {
				return itemReader.readValue(item);
			}
			finally {
				current = null;
			}
		}

		private Event<T> control() {
			JsonNode value;
			try (JsonParser p = buffering().asParser(parser.objectReadContext())) {
				value = nodeReader.readValue(p);
			}
			finally {
				current = null;
			}
			String name = Objects.requireNonNull(currentControl, "currentControl");
			if ("error".equals(name)) {
				ODataError error = ODataError.from(value);
				throw new Tm1ProtocolException(
						"TM1 reported an error in the response" + (error == null ? "" : ": " + error.message()), error,
						method, url, null);
			}
			return new Event.Control<>(name, value);
		}

	}

}
