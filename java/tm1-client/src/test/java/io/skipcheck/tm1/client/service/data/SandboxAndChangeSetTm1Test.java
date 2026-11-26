// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.Sandbox;
import io.skipcheck.tm1.client.model.SandboxCreate;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;

/** Sandboxes and change sets, on a cube of two dimensions with cells X and Y. */
@LiveTm1
class SandboxAndChangeSetTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private Tm1Client tm1;

	private String cube;

	@BeforeEach
	void createCube(LiveServer server) {
		tm1 = server.clientBuilder().build();
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		for (String d : List.of(cube + "-a", cube + "-b")) {
			tm1.dimensions()
				.create(DimensionCreate.builder()
					.name(d)
					.hierarchies(List.of(HierarchyCreate.builder()
						.name(d)
						.elements(List.of(ElementCreate.builder().name("X").type(ElementType.NUMERIC).build(),
								ElementCreate.builder().name("Y").type(ElementType.NUMERIC).build()))
						.build()))
					.build())
				.block(WAIT);
		}
		tm1.cubes()
			.create(CubeCreate.builder()
				.name(cube)
				.bindDimensions(ODataPath.of("Dimensions").key(cube + "-a"),
						ODataPath.of("Dimensions").key(cube + "-b"))
				.build())
			.block(WAIT);
	}

	@AfterEach
	void deleteCube() {
		tm1.sandboxes().list(ODataQuery.none()).flatMap(s -> tm1.sandboxes().delete(s.name())).blockLast(WAIT);
		tm1.cubes().delete(cube).block(WAIT);
		for (String d : List.of(cube + "-a", cube + "-b")) {
			tm1.dimensions().delete(d).block(WAIT);
		}
		tm1.close().block(WAIT);
	}

	@Test
	void keepsSandboxWritesApartUntilPublished() {
		tm1.sandboxes().create(SandboxCreate.builder().name("S").build()).block(WAIT);
		assertThat(tm1.sandboxes().list(ODataQuery.none()).map(Sandbox::name).collectList().block(WAIT)).contains("S");
		tm1.withSandbox("S").cells().update(cube, cell("X"), 5).block(WAIT);
		assertThat(value(tm1.withSandbox("S"), "X")).isEqualTo(5.0);
		assertThat(value(tm1, "X")).as("base data untouched").isEqualTo(0.0);

		tm1.sandboxes().publish("S").block(WAIT);
		assertThat(value(tm1, "X")).isEqualTo(5.0);
	}

	@Test
	void mergesDiscardsLoadsAndUnloads() {
		tm1.sandboxes().create(SandboxCreate.builder().name("S").build()).block(WAIT);
		tm1.sandboxes().create(SandboxCreate.builder().name("T").build()).block(WAIT);
		tm1.withSandbox("S").cells().update(cube, cell("X"), 7).block(WAIT);
		tm1.sandboxes().merge("S", "T").block(WAIT);
		assertThat(value(tm1.withSandbox("T"), "X")).isEqualTo(7.0);

		tm1.sandboxes().discardChanges("S").block(WAIT);
		assertThat(value(tm1.withSandbox("S"), "X")).isEqualTo(0.0);
		tm1.sandboxes().unload("T").then(tm1.sandboxes().load("T")).block(WAIT);
		assertThat(tm1.sandboxes().get("T", ODataQuery.none()).block(WAIT).isLoaded()).isTrue();

		tm1.sandboxes().delete("T").block(WAIT);
		assertThat(tm1.sandboxes().list(ODataQuery.none()).map(Sandbox::name).collectList().block(WAIT))
			.doesNotContain("T");
	}

	@Test
	void undoesTheWritesOfAChangeSetOnly() {
		AtomicReference<String> id = new AtomicReference<>();
		tm1.changeSets().within(session -> {
			id.set(session.changeSet().orElseThrow());
			return Tm1Client.of(session).cells().update(cube, cell("X"), 5);
		}).blockLast(WAIT);
		tm1.cells().update(cube, cell("Y"), 7).block(WAIT);
		assertThat(value(tm1, "X")).isEqualTo(5.0);

		tm1.changeSets().undo(id.get()).block(WAIT);
		assertThat(value(tm1, "X")).as("in the change set: reverted").isEqualTo(0.0);
		assertThat(value(tm1, "Y")).as("outside it: kept").isEqualTo(7.0);
	}

	@Test
	void beginsAndEndsAChangeSetByHand() {
		String id = tm1.changeSets().begin().block(WAIT);
		assertThat(id).isNotBlank();
		tm1.withChangeSet(id).cells().update(cube, cell("Y"), 3).block(WAIT);
		assertThat(tm1.changeSets().end(id).block(WAIT)).isNotNull();
		tm1.changeSets().undo(id).block(WAIT);
		assertThat(value(tm1, "Y")).isEqualTo(0.0);
	}

	private Map<String, String> cell(String element) {
		Map<String, String> tuple = new LinkedHashMap<>();
		tuple.put(cube + "-a", element);
		tuple.put(cube + "-b", element);
		return tuple;
	}

	private double value(Tm1Client client, String element) {
		String a = cube + "-a";
		String b = cube + "-b";
		String mdx = "SELECT {[" + b + "].[" + b + "].[" + element + "]} ON 0, {[" + a + "].[" + a + "].[" + element
				+ "]} ON 1 FROM [" + cube + "]";
		JsonNode value = client.cellsets()
			.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 10,
					(cellset, cells) -> cells.map(c -> c.value()))
			.next()
			.block(WAIT);
		return value == null || value.isNull() ? 0 : value.asDouble();
	}

}
