// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetUpdatePatch;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.CubeUpdatePatch;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.EdgeCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.UpdateOrder;
import io.skipcheck.tm1.client.model.View;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import tools.jackson.databind.JsonNode;

/** Cell writes, on Region (Total = North + South) by Measure (Amount, Note as text). */
@LiveTm1
class CellServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static CellService cells;

	private static String region;

	private static String measure;

	private String cube;

	@BeforeAll
	static void createDimensions(LiveServer server) {
		tm1 = server.clientBuilder().build();
		cells = tm1.cells();
		String id = UUID.randomUUID().toString().substring(0, 8);
		region = "tm1-client-" + id + "-region";
		measure = "tm1-client-" + id + "-measure";
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(region)
				.hierarchies(List.of(HierarchyCreate.builder()
					.name(region)
					.elements(List.of(element("Total", ElementType.CONSOLIDATED), element("North", ElementType.NUMERIC),
							element("South", ElementType.NUMERIC)))
					.edges(List.of(edge("Total", "North"), edge("Total", "South")))
					.build()))
				.build())
			.block(WAIT);
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(measure)
				.hierarchies(List.of(HierarchyCreate.builder()
					.name(measure)
					.elements(List.of(element("Amount", ElementType.NUMERIC), element("Note", ElementType.STRING)))
					.build()))
				.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteDimensions() {
		tm1.dimensions().delete(region).block(WAIT);
		tm1.dimensions().delete(measure).block(WAIT);
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createCube() {
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.cubes()
			.create(CubeCreate.builder()
				.name(cube)
				.bindDimensions(ODataPath.of("Dimensions").key(region), ODataPath.of("Dimensions").key(measure))
				.build())
			.block(WAIT);
	}

	@AfterEach
	void deleteCube() {
		tm1.cubes().delete(cube).block(WAIT);
	}

	@Test
	void writesNumbersTextAndNullsCellByCell() {
		cells.update(cube, cell("North", "Amount"), 5).block(WAIT);
		cells.update(cube, cell("South", "Amount"), 7.5).block(WAIT);
		cells.update(cube, cell("North", "Note"), "hello").block(WAIT);
		assertThat(number("North", "Amount")).isEqualTo(5.0);
		assertThat(number("Total", "Amount")).as("consolidated").isEqualTo(12.5);
		assertThat(read("North", "Note").asString()).isEqualTo("hello");

		cells.update(cube, cell("North", "Amount"), null).block(WAIT);
		assertThat(number("North", "Amount")).as("null clears").isEqualTo(0.0);
	}

	@Test
	void needsAValueInEveryUpdateAndDoesntCopyFromAReference() {
		cells.update(cube, cell("North", "Amount"), 40).block(WAIT);
		CubeUpdatePatch.Builder withReference = CubeUpdatePatch.builder()
			.bindTuple(Tuples.of(cell("South", "Amount")))
			.bindReferenceCube(ODataPath.of("Cubes").key(cube))
			.bindReferenceCell(Tuples.of(cell("North", "Amount")));
		assertThatThrownBy(() -> cells.updateCells(cube, List.of(withReference.build())).block(WAIT))
			.isInstanceOf(Tm1BadRequestException.class)
			.hasMessageContaining("Value");
		cells.updateCells(cube, List.of(withReference.value(5).build())).block(WAIT);
		assertThat(number("South", "Amount")).as("the value, not the reference's 40").isEqualTo(5.0);
	}

	@Test
	void writesOnlyTheFirstOfSeveralCellsAndRefusesConsolidations() {
		// Through the raw session: the service only offers what TM1 does
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Cells", List.of(Map.of("Tuple@odata.bind", paths(cell("North", "Amount"))),
				Map.of("Tuple@odata.bind", paths(cell("South", "Amount")))));
		body.put("Value", 3);
		tm1.session()
			.execute(Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"), body, Void.class))
			.block(WAIT);
		assertThat(number("North", "Amount")).isEqualTo(3.0);
		assertThat(number("South", "Amount")).as("only the first cell is written").isEqualTo(0.0);

		assertThatThrownBy(() -> cells.update(cube, cell("Total", "Amount"), 100).block(WAIT))
			.as("no spread to a consolidation")
			.isInstanceOf(Tm1BadRequestException.class)
			.hasMessageContaining("Consolidated");
	}

	@Test
	void writesManyCellsInOneRequest() {
		cells
			.updateCells(cube,
					List.of(write("North", "Amount", 10), write("South", "Amount", 20), write("North", "Note", "n")))
			.block(WAIT);
		assertThat(number("Total", "Amount")).isEqualTo(30.0);
		assertThat(read("North", "Note").asString()).isEqualTo("n");
		cells.updateCells(cube, List.of(write("North", "Amount", 1), write("South", "Amount", 2)), UpdateOrder.BY_LEVEL)
			.block(WAIT);
		assertThat(number("Total", "Amount")).isEqualTo(3.0);
	}

	@Test
	void writesThroughACellsetAndSavesItAsAView() {
		String mdx = "SELECT {[" + measure + "].[" + measure + "].[Amount]} ON 0, {[" + region + "].[" + region
				+ "].[North], [" + region + "].[" + region + "].[South]} ON 1 FROM [" + cube + "]";
		Cellset cellset = tm1.cellsets().execute(mdx, ODataQuery.none()).block(WAIT);
		String id = cellset.id();
		try {
			tm1.cellsets().update(id, 0, 4).block(WAIT);
			assertThat(number("North", "Amount")).isEqualTo(4.0);
			tm1.cellsets().update(id, 1, 6).block(WAIT);
			assertThat(number("Total", "Amount")).isEqualTo(10.0);
			Map<String, Object> range = new LinkedHashMap<>();
			range.put("BeginOrdinal", 0);
			range.put("EndOrdinal", 1);
			range.put("Value", 9);
			tm1.session()
				.execute(Tm1Request.post(ODataPath.of("Cellsets").key(id).action("tm1.Update"), range, Void.class))
				.block(WAIT);
			assertThat(number("Total", "Amount")).as("a range writes only its first cell").isEqualTo(15.0);
			tm1.cellsets()
				.updateCells(id,
						List.of(CellsetUpdatePatch.builder().ordinal(0L).value(1).build(),
								CellsetUpdatePatch.builder().ordinal(1L).value(2).build()))
				.block(WAIT);
			assertThat(number("Total", "Amount")).isEqualTo(3.0);

			View saved = tm1.cellsets().saveViewAs(id, "Saved").block(WAIT);
			assertThat(saved.name()).isEqualTo("Saved");
			assertThat(tm1.views().get(cube, ViewScope.PUBLIC, "Saved", ODataQuery.none()).block(WAIT).name())
				.isEqualTo("Saved");
			assertThat(tm1.cellsets().saveViewAs(id, "Mine", false, true).block(WAIT).name()).isEqualTo("Mine");
			assertThat(tm1.views().get(cube, ViewScope.PRIVATE, "Mine", ODataQuery.none()).block(WAIT).name())
				.isEqualTo("Mine");
		}
		finally {
			tm1.cellsets().delete(id).block(WAIT);
		}
	}

	private Map<String, String> cell(String regionElement, String measureElement) {
		Map<String, String> tuple = new LinkedHashMap<>();
		tuple.put(region, regionElement);
		tuple.put(measure, measureElement);
		return tuple;
	}

	private static List<String> paths(Map<String, String> tuple) {
		return Arrays.stream(Tuples.of(tuple)).map(ODataPath::toString).toList();
	}

	private CubeUpdatePatch write(String regionElement, String measureElement, Object value) {
		return CubeUpdatePatch.builder().bindTuple(Tuples.of(cell(regionElement, measureElement))).value(value).build();
	}

	private double number(String regionElement, String measureElement) {
		JsonNode value = read(regionElement, measureElement);
		return value.isNull() ? 0 : value.asDouble();
	}

	private JsonNode read(String regionElement, String measureElement) {
		String mdx = "SELECT {[" + measure + "].[" + measure + "].[" + measureElement + "]} ON 0, {[" + region + "].["
				+ region + "].[" + regionElement + "]} ON 1 FROM [" + cube + "]";
		return tm1.cellsets()
			.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 10, (cellset, all) -> all.map(c -> c.value()))
			.next()
			.block(WAIT);
	}

	private static ElementCreate element(String name, ElementType type) {
		return ElementCreate.builder().name(name).type(type).build();
	}

	private static EdgeCreate edge(String parent, String component) {
		return EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}
