// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WritesTest {

	private static final String PROBE = """
			{"probeVersion": 2, "server": {"schemaVersion": "11.8.35"},
			 "writes": {
			  "Cube": {"type": "Cube", "post": {"Rules": {"outcome": "applied"}}, "patch": {"Rules": {"outcome": "applied"}}},
			  "Edge": {"type": "Edge", "generate": false, "post": {"Weight": {"outcome": "applied"}},
			   "patch": {"Weight": {"outcome": "applied"}}}},
			 "collections": {}}""";

	@Test
	void leavesOutAProbedTypeNotYetGeneratedFrom() {
		Overlay overlay = Overlay.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
		Writes writes = Writes.read(PROBE, overlay);
		assertThat(writes.get("Cube")).isNotNull();
		// Recorded by the probe, but the generators don't act on it yet (#338)
		assertThat(writes.get("Edge")).isNull();
	}

}
