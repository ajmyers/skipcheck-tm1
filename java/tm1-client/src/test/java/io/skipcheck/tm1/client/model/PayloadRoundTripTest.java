package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 1's exit criterion (plan §15): JSON captured from a real TM1
 * ({@link PayloadCaptureTest}) decodes into the generated model and encodes back to the
 * same JSON, with the session's default mapper.
 *
 * <p>
 * "The same" allows for what the model deliberately doesn't keep: control information
 * other than {@code @odata.etag} and {@code @odata.type}, and nulls (null components
 * aren't sent). Values compare by meaning, so {@code P0DT00H00M00S} and {@code PT0S}, or
 * {@code 1} and {@code 1.0}, are the same. A property TM1 sent that didn't come back is
 * one the model doesn't know, and must be listed in {@link #UNKNOWN}.
 */
class PayloadRoundTripTest {

	private static final String DIR = "/payloads/11.8.35/";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Properties TM1 sends that {@code $metadata} doesn't declare, by payload. */
	private static final Map<String, Set<String>> UNKNOWN = Map.of();

	// Configuration is deprecated (since schema 11.0.0, no reason given) but still served
	// still served
	@SuppressWarnings("deprecation")
	static Stream<Arguments> payloads() {
		return Stream.of(Arguments.of("server", Server.class), Arguments.of("configuration", Configuration.class),
				Arguments.of("active-session", Session.class), Arguments.of("users", User.class),
				Arguments.of("groups", Group.class), Arguments.of("dimension", Dimension.class),
				Arguments.of("members", Member.class), Arguments.of("cube", Cube.class),
				Arguments.of("views", View.class), Arguments.of("process", Process.class),
				Arguments.of("chore", Chore.class), Arguments.of("threads", Thread.class),
				Arguments.of("cellset", Cellset.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("payloads")
	void decodesAndEncodesBackToTheSameJson(String payload, Class<?> type) throws IOException {
		JsonNode original = read(payload);
		List<String> differences = new ArrayList<>();
		Set<String> unknown = new TreeSet<>();
		JsonNode items = original.get("value");
		if (items != null && items.isArray()) {
			assertThat(items).as("a collection with something in it").isNotEmpty();
			for (int i = 0; i < items.size(); i++) {
				compare(items.get(i), roundTrip(items.get(i), type), "value[" + i + "]", differences, unknown);
			}
		}
		else {
			compare(original, roundTrip(original, type), "", differences, unknown);
		}
		assertThat(differences).as("what changed in the round trip").isEmpty();
		assertThat(unknown).as("properties the model doesn't know")
			.containsExactlyInAnyOrderElementsOf(UNKNOWN.getOrDefault(payload, Set.of()));
	}

	private static JsonNode roundTrip(JsonNode json, Class<?> type) {
		Object value = JSON.treeToValue(json, type);
		assertThat(value).isInstanceOf(type);
		return JSON.valueToTree(value);
	}

	private static void compare(JsonNode original, JsonNode written, String path, List<String> differences,
			Set<String> unknown) {
		if (original.isObject()) {
			if (!written.isObject()) {
				differences.add(path + ": " + original + " became " + written);
				return;
			}
			for (Map.Entry<String, JsonNode> property : original.properties()) {
				String name = property.getKey();
				String at = path + "/" + name;
				JsonNode back = written.get(name);
				if (name.startsWith("@odata.") && !name.equals("@odata.etag") && !name.equals("@odata.type")) {
					continue;
				}
				if (property.getValue().isNull()) {
					if (back != null && !back.isNull()) {
						differences.add(at + ": null became " + back);
					}
				}
				else if (back == null) {
					unknown.add(at.replaceAll("\\[\\d+]", "[]"));
				}
				else {
					compare(property.getValue(), back, at, differences, unknown);
				}
			}
			for (Map.Entry<String, JsonNode> property : written.properties()) {
				if (!original.has(property.getKey()) && !property.getKey().equals("@odata.type")) {
					differences.add(path + "/" + property.getKey() + ": written, but TM1 didn't send it");
				}
			}
		}
		else if (original.isArray()) {
			if (!written.isArray() || written.size() != original.size()) {
				differences.add(path + ": " + original + " became " + written);
				return;
			}
			for (int i = 0; i < original.size(); i++) {
				compare(original.get(i), written.get(i), path + "[" + i + "]", differences, unknown);
			}
		}
		else if (!same(original, written)) {
			differences.add(path + ": " + original + " became " + written);
		}
	}

	private static boolean same(JsonNode original, JsonNode written) {
		if (original.equals(written)) {
			return true;
		}
		if (original.isNumber() && written.isNumber()) {
			return original.decimalValue().compareTo(written.decimalValue()) == 0;
		}
		if (original.isString() && written.isString()) {
			String a = original.stringValue();
			String b = written.stringValue();
			try {
				return OffsetDateTime.parse(a).isEqual(OffsetDateTime.parse(b));
			}
			catch (DateTimeParseException notADateTime) {
				// try a duration
			}
			try {
				return Duration.parse(a).equals(Duration.parse(b));
			}
			catch (DateTimeParseException notADuration) {
				return false;
			}
		}
		return false;
	}

	private static JsonNode read(String payload) throws IOException {
		try (InputStream in = PayloadRoundTripTest.class.getResourceAsStream(DIR + payload + ".json")) {
			assertThat(in).as(payload).isNotNull();
			return JSON.readTree(in);
		}
	}

}
