package net.ajmyers.tm1.client.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Every fixture in {@code spec/conformance/calls} belongs to a test class that records
 * it: a renamed or deleted test class can't leave a fixture behind for the other clients
 * to replay.
 */
class ConformanceFixturesTest {

	@Test
	void everyFixtureIsRecordedByATestClass() throws IOException {
		List<Path> files;
		try (Stream<Path> list = Files.list(Conformance.directory())) {
			files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		assertThat(files).isNotEmpty();
		for (Path file : files) {
			JsonNode fixture = ConformanceCodec.json().readTree(file.toFile());
			assertThat(fixture.get("specVersion").asInt()).as(file + " specVersion")
				.isEqualTo(Conformance.SPEC_VERSION);
			String source = fixture.get("source").asString();
			Class<?> type;
			try {
				type = Class.forName(source);
			}
			catch (ClassNotFoundException e) {
				throw new AssertionError(file + " is recorded by " + source + ", which no longer exists: delete it", e);
			}
			assertThat(file.getFileName().toString()).isEqualTo(type.getSimpleName() + ".json");
			assertThat(Stream.of(type.getDeclaredFields())
				.filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == Conformance.class)
				.map(Field::getName)
				.toList()).as(source + " registers a Conformance extension").isNotEmpty();
		}
	}

}
