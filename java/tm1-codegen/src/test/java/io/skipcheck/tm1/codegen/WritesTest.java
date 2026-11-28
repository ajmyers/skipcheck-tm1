// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class WritesTest {

	private static final String PROBE = """
			{"probeVersion": 2, "server": {"schemaVersion": "11.8.35"},
			 "writes": {
			  "Cube": {"type": "Cube", "post": {"Rules": {"outcome": "applied"}}, "patch": {"Rules": {"outcome": "applied"}}},
			  "Edge": {"type": "Edge", "required": ["ParentName"], "post": {"Weight": {"outcome": "applied"}},
			   "patch": {"Weight": {"outcome": "applied"}}}},
			 "collections": {"Cube/Dimensions": {"outcome": "rejected"}}}""";

	private static final String PROBE_V12 = """
			{"probeVersion": 2, "server": {"schemaVersion": "12.6.0"},
			 "writes": {
			  "Cube": {"type": "Cube", "post": {"Rules": {"outcome": "applied"}}, "patch": {"Rules": {"outcome": "applied"}}}},
			 "collections": {"Cube/Dimensions": {"outcome": "replaces"}}}""";

	@Test
	void readsEveryProbedType() {
		Overlay overlay = Overlay.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
		Writes writes = Writes.read(PROBE, overlay);
		assertThat(writes.get("Cube")).isNotNull();
		// Its create's needs too, which build() checks
		assertThat(writes.get("Edge")).isNotNull();
		assertThat(writes.get("Edge").required()).containsExactly("ParentName");
	}

	@Test
	void mergesMultipleProbesAndCollectsObservedInPerType() {
		Overlay overlay = Overlay.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
		Writes writes = Writes.read(List.of(PROBE, PROBE_V12), overlay);
		assertThat(writes.observedIn()).containsExactly("11.8.35", "12.6.0");
		assertThat(writes.get("Cube").observedIn()).containsExactly("11.8.35", "12.6.0");
		assertThat(writes.get("Edge").observedIn()).containsExactly("11.8.35");
		assertThat(writes.get("Cube").collections()).containsEntry("Dimensions", "replaces");
	}

}
