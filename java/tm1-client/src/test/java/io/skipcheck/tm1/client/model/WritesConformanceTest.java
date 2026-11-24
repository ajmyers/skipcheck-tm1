// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The writes made from what was read that every client must produce identically
 * ({@code spec/conformance/writes.json}): each case reads an entity, builds its create or
 * patch with {@code builder(read)}, and must get exactly what the case expects, or fail.
 * Every client has this test.
 */
class WritesConformanceTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode CASES = JSON
		.readTree(Path.of(System.getProperty("tm1.spec")).resolveSibling("conformance/writes.json").toFile());

	@Test
	void everyCaseWritesWhatItExpects() throws ReflectiveOperationException {
		List<String> wrong = new ArrayList<>();
		for (JsonNode c : CASES.path("cases")) {
			String id = c.path("id").asString();
			Class<?> type = Class.forName(Process.class.getPackageName() + "." + c.path("type").asString());
			Class<?> write = Class.forName(Process.class.getPackageName() + "." + c.path("type").asString()
					+ (c.path("write").asString().equals("create") ? "Create" : "Patch"));
			Object read = JSON.treeToValue(c.path("read"), type);
			Object builder = write.getMethod("builder", type).invoke(null, read);
			try {
				JsonNode got = JSON
					.readTree(JSON.writeValueAsString(builder.getClass().getMethod("build").invoke(builder)));
				if (c.has("error")) {
					wrong.add(id + ": built " + got + ", but should fail with " + c.path("error").asString());
				}
				else if (!got.equals(c.path("expect"))) {
					wrong.add(id + ": wrote " + got + ", expected " + c.path("expect"));
				}
			}
			catch (InvocationTargetException e) {
				String message = String.valueOf(e.getCause().getMessage());
				if (!c.has("error") || !message.contains(c.path("error").asString())) {
					wrong.add(id + ": failed with " + message);
				}
			}
		}
		assertThat(CASES.path("cases").size()).isPositive();
		assertThat(wrong).isEmpty();
	}

}
