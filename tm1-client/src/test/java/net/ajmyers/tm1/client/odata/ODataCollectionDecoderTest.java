package net.ajmyers.tm1.client.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import net.ajmyers.tm1.client.error.Tm1ProtocolException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ODataCollectionDecoderTest {

	record Element(String Name, String Type) {
	}

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final Class<Element> ELEMENTS = Element.class;

	private static final String BODY = """
			{"@odata.context":"$metadata#Elements","@odata.count":3,"value":[
			{"@odata.etag":"W/\\"1\\"","Name":"Q1","Type":"Numeric"},
			{"Name":"Q'2 {x}","Type":"Numeric","Extra":{"nested":[1,[2,{"a":null}]]}},
			{"Name":"Total","Type":"Consolidated"}],"@odata.nextLink":"Elements?$skiptoken=3"}""";

	@Test
	void decodesItemsAndControlInformation() {
		List<ODataCollectionDecoder.Event<Element>> events = ODataCollectionDecoder
			.decode(chunks(BODY, 7), MAPPER, ELEMENTS, "GET", "Elements")
			.collectList()
			.block();
		assertThat(events).extracting(e -> e.getClass().getSimpleName())
			.containsExactly("Control", "Control", "Item", "Item", "Item", "Control");
		assertThat(items(events)).extracting(Element::Name).containsExactly("Q1", "Q'2 {x}", "Total");
		assertThat(controls(events)).containsKeys("@odata.context", "@odata.count", "@odata.nextLink");
		assertThat(controls(events).get("@odata.count").asInt()).isEqualTo(3);
	}

	@Test
	void givesTheSameResultHoweverTheBytesAreSplit() {
		List<Element> expected = List.of(new Element("Q1", "Numeric"), new Element("Q'2 {x}", "Numeric"),
				new Element("Total", "Consolidated"));
		byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
		for (int split = 1; split < bytes.length; split++) {
			Flux<DataBuffer> two = Flux.just(buffer(bytes, 0, split), buffer(bytes, split, bytes.length));
			assertThat(ODataCollectionDecoder.items(two, MAPPER, ELEMENTS, "GET", "x").collectList().block())
				.as("split at %d", split)
				.isEqualTo(expected);
		}
		Random random = new Random(7);
		for (int i = 0; i < 200; i++) {
			assertThat(ODataCollectionDecoder.items(randomChunks(bytes, random), MAPPER, ELEMENTS, "GET", "x")
				.collectList()
				.block()).isEqualTo(expected);
		}
	}

	@Test
	void handlesMultibyteCharactersSplitAcrossBuffers() {
		String body = "{\"value\":[{\"Name\":\"Région 日本 😀\",\"Type\":\"String\"}]}";
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		for (int split = 1; split < bytes.length; split++) {
			assertThat(ODataCollectionDecoder
				.items(Flux.just(buffer(bytes, 0, split), buffer(bytes, split, bytes.length)), MAPPER, ELEMENTS, "GET",
						"x")
				.blockFirst()
				.Name()).isEqualTo("Région 日本 😀");
		}
	}

	@Test
	void decodesCollectionsOfPrimitives() {
		Flux<DataBuffer> body = chunks("{\"value\":[\"a\",null,\"b\"]}", 3);
		// Reactor can't carry nulls, so items() skips them...
		assertThat(ODataCollectionDecoder.items(body, MAPPER, String.class, "GET", "x").collectList().block())
			.containsExactly("a", "b");
		// ...while decode() shows them
		assertThat(
				ODataCollectionDecoder.decode(chunks("{\"value\":[\"a\",null]}", 3), MAPPER, String.class, "GET", "x")
					.collectList()
					.block())
			.hasSize(2)
			.last()
			.isEqualTo(new ODataCollectionDecoder.Event.Item<String>(null));
	}

	@Test
	void decodesAnEmptyCollection() {
		StepVerifier
			.create(ODataCollectionDecoder.items(chunks("{\"@odata.context\":\"x\",\"value\":[]}", 4), MAPPER, ELEMENTS,
					"GET", "x"))
			.verifyComplete();
	}

	@Test
	void failsOnAnErrorInsteadOfACollection() {
		StepVerifier
			.create(ODataCollectionDecoder.items(chunks("{\"error\":{\"code\":\"278\",\"message\":\"Boom\"}}", 5),
					MAPPER, ELEMENTS, "GET", "x"))
			.expectErrorSatisfies(e -> {
				assertThat(e).isInstanceOf(Tm1ProtocolException.class).hasMessageContaining("Boom");
				assertThat(((Tm1ProtocolException) e).error().code()).isEqualTo("278");
			})
			.verify();
	}

	@Test
	void failsWhenTheResponseIsCutShortAfterSomeItems() {
		String truncated = BODY.substring(0, BODY.indexOf("Total"));
		StepVerifier.create(ODataCollectionDecoder.items(chunks(truncated, 9), MAPPER, ELEMENTS, "GET", "Elements"))
			.expectNextCount(2)
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(Tm1ProtocolException.class)
				.hasMessageContaining("ended before the collection was complete"))
			.verify();
	}

	@Test
	void failsOnMalformedOrUnexpectedBodies() {
		StepVerifier
			.create(ODataCollectionDecoder.items(chunks("{\"value\":[{\"Name\":}]}", 4), MAPPER, ELEMENTS, "GET", "x"))
			.expectErrorSatisfies(
					e -> assertThat(e).isInstanceOf(Tm1ProtocolException.class).hasMessageContaining("malformed JSON"))
			.verify();
		StepVerifier.create(ODataCollectionDecoder.items(chunks("[1,2]", 4), MAPPER, ELEMENTS, "GET", "x"))
			.expectError(Tm1ProtocolException.class)
			.verify();
		StepVerifier.create(ODataCollectionDecoder.items(Flux.empty(), MAPPER, ELEMENTS, "GET", "x"))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("empty"))
			.verify();
		StepVerifier.create(ODataCollectionDecoder.items(chunks("{\"value\":[]} {}", 4), MAPPER, ELEMENTS, "GET", "x"))
			.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("after the collection"))
			.verify();
	}

	@Test
	void followsNextLinksAsGiven() {
		List<String> fetched = new ArrayList<>();
		Map<String, String> pages = Map.of("Elements?$skiptoken=3",
				"{\"value\":[{\"Name\":\"p2\"}],\"@odata.nextLink\":\"Elements?$skiptoken=4&x='a b'\"}",
				"Elements?$skiptoken=4&x='a b'", "{\"value\":[{\"Name\":\"p3\"}]}");
		List<Element> all = ODataCollectionDecoder.follow(chunks(BODY, 11), link -> {
			fetched.add(link);
			return chunks(pages.get(link), 5);
		}, MAPPER, ELEMENTS, "GET", "Elements").collectList().block();
		assertThat(all).extracting(Element::Name).containsExactly("Q1", "Q'2 {x}", "Total", "p2", "p3");
		assertThat(fetched).containsExactly("Elements?$skiptoken=3", "Elements?$skiptoken=4&x='a b'");
	}

	@Test
	void streamsALargeCollectionWithoutReadingAheadOfDemand() {
		int total = 100_000;
		AtomicInteger buffersRead = new AtomicInteger();
		Flux<DataBuffer> body = Flux.<String, Integer>generate(() -> 0, (i, sink) -> {
			if (i == 0) {
				sink.next("{\"value\":[");
			}
			else if (i <= total) {
				sink.next((i > 1 ? "," : "") + "{\"Name\":\"e" + i + "\",\"Type\":\"Numeric\"}");
			}
			else if (i == total + 1) {
				sink.next("]}");
			}
			else {
				sink.complete();
			}
			return i + 1;
		})
			.map(s -> buffer(s.getBytes(StandardCharsets.UTF_8), 0, s.length()))
			.doOnNext(b -> buffersRead.incrementAndGet());

		Flux<Element> items = ODataCollectionDecoder.items(body, MAPPER, ELEMENTS, "GET", "x");
		StepVerifier.create(items, 10)
			.expectNextCount(10)
			.thenAwait()
			.then(() -> assertThat(buffersRead.get()).isLessThan(1000))
			.thenCancel()
			.verify();
		assertThat(ODataCollectionDecoder.items(body, MAPPER, ELEMENTS, "GET", "x").count().block()).isEqualTo(total);
	}

	private static List<Element> items(List<ODataCollectionDecoder.Event<Element>> events) {
		List<Element> out = new ArrayList<>();
		events.forEach(e -> {
			if (e instanceof ODataCollectionDecoder.Event.Item<Element> item) {
				out.add(item.value());
			}
		});
		return out;
	}

	private static Map<String, JsonNode> controls(List<ODataCollectionDecoder.Event<Element>> events) {
		Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
		events.forEach(e -> {
			if (e instanceof ODataCollectionDecoder.Event.Control<Element> c) {
				out.put(c.name(), c.value());
			}
		});
		return out;
	}

	private static Flux<DataBuffer> chunks(String text, int size) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		List<DataBuffer> out = new ArrayList<>();
		for (int i = 0; i < bytes.length; i += size) {
			out.add(buffer(bytes, i, Math.min(bytes.length, i + size)));
		}
		return Flux.fromIterable(out);
	}

	private static Flux<DataBuffer> randomChunks(byte[] bytes, Random random) {
		List<DataBuffer> out = new ArrayList<>();
		for (int i = 0; i < bytes.length;) {
			int end = Math.min(bytes.length, i + 1 + random.nextInt(40));
			out.add(buffer(bytes, i, end));
			i = end;
		}
		return Flux.fromIterable(out);
	}

	private static DataBuffer buffer(byte[] bytes, int from, int to) {
		byte[] slice = java.util.Arrays.copyOfRange(bytes, from, to);
		return DefaultDataBufferFactory.sharedInstance.wrap(slice);
	}

}
