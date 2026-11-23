// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CubeUpdatePatch;
import io.skipcheck.tm1.client.model.UpdateOrder;
import io.skipcheck.tm1.client.odata.ODataPath;

/**
 * CellService against a fake TM1: what each write sends. Every call is recorded to
 * {@code spec/conformance/calls/CellServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class CellServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static Map<String, String> cell(String region, String measure) {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Region", region);
		cell.put("Measure", measure);
		return cell;
	}

	private static final String NORTH = "Dimensions('Region')/Hierarchies('Region')/Elements('North')";

	@Test
	void writesACellByItsTuple() {
		tm1().status(204);
		conformance.call("cells", "update", "Sales", cell("North", "Amount"), 42);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Cubes('Sales')/tm1.Update");
		assertThat(post.text()).isEqualTo("{\"Cells\":[{\"Tuple@odata.bind\":[\"" + NORTH
				+ "\",\"Dimensions('Measure')/Hierarchies('Measure')/Elements('Amount')\"]}],\"Value\":42}");
	}

	@Test
	void writesTextDecimalsAndNulls() {
		tm1().status(204);
		conformance.call("cells", "update", "Sales", cell("North", "Note"), "it's");
		assertThat(tm1().last().json().get("Value").asString()).isEqualTo("it's");

		tm1().status(204);
		conformance.call("cells", "update", "Sales", cell("North", "Amount"), 12.5);
		assertThat(tm1().last().text()).endsWith(",\"Value\":12.5}");

		tm1().status(204);
		conformance.call("cells", "update", "Sales", cell("North", "Amount"), null);
		assertThat(tm1().last().text()).endsWith(",\"Value\":null}");
	}

	@Test
	void quotesNamesThatNeedIt() {
		tm1().status(204);
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Sales Region", "Joe's");
		cell.put("Measure", "100%");
		conformance.call("cells", "update", "Joe's Cube", cell, 1);
		assertThat(tm1().last().target()).isEqualTo("Cubes('Joe''s%20Cube')/tm1.Update");
		assertThat(tm1().last().text()).isEqualTo("{\"Cells\":[{\"Tuple@odata.bind\":["
				+ "\"Dimensions('Sales%20Region')/Hierarchies('Sales%20Region')/Elements('Joe''s')\","
				+ "\"Dimensions('Measure')/Hierarchies('Measure')/Elements('100%25')\"]}],\"Value\":1}");
	}

	@Test
	void refusesAConsolidatedCellOrAMissingCube() {
		tm1().error(400, "CubeCellWriteStatusElementIsConsolidated", "consolidated");
		Throwable error = conformance.fail("cells", "update", "Sales", cell("Total", "Amount"), 1);
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Cube'.");
		assertThat(conformance.fail("cells", "update", "Nope", cell("North", "Amount"), 1))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void writesManyCellsInOneRequest() {
		tm1().status(204);
		List<CubeUpdatePatch> updates = List.of(
				CubeUpdatePatch.builder().bindTuple(Tuples.of(cell("North", "Amount"))).value(1).build(),
				CubeUpdatePatch.builder().bindTuple(Tuples.element("Region", "Region", "South")).value("x").build(),
				CubeUpdatePatch.builder()
					.bindTuple(Tuples.of(cell("North", "Amount")))
					.value(2)
					.bindReferenceCube(ODataPath.of("Cubes").key("Plan"))
					.bindReferenceCell(Tuples.of(cell("South", "Amount")))
					.build());
		conformance.call("cells", "updateCells", "Sales", updates);
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Sales')/tm1.UpdateCells");
		assertThat(tm1().last().text()).startsWith("{\"Updates\":[{\"Tuple@odata.bind\":[\"" + NORTH);

		tm1().status(204);
		conformance.call("cells", "updateCells", "Sales", updates.subList(0, 1), UpdateOrder.BY_LEVEL);
		assertThat(tm1().last().text()).endsWith("],\"Order\":\"ByLevel\"}");

		tm1().status(204);
		conformance.call("cells", "updateCells", "Sales", List.of());
		assertThat(tm1().last().text()).isEqualTo("{\"Updates\":[]}");
	}

	@Test
	void needsAValueInEveryUpdate() {
		tm1().error(400, "278", "Value is required");
		Throwable error = conformance.fail("cells", "updateCells", "Sales",
				List.of(CubeUpdatePatch.builder().bindTuple(Tuples.of(cell("North", "Amount"))).build()));
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void refusesAMissingCube() {
		Throwable error = conformance.fail("cells", "update", null, cell("North", "Amount"), 1);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
