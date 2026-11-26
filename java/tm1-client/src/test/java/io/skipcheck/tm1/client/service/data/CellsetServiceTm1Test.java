// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetCell;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.MDXViewCreate;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.model.NativeViewCreate;
import io.skipcheck.tm1.client.model.SubsetCreate;
import io.skipcheck.tm1.client.model.ViewAxisSelectionPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;

/**
 * CellsetService, on Region (N, S, E, W) by Measure (Amount, Count), cell values 1 to 8
 * in ordinal order.
 */
@LiveTm1
class CellsetServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final List<String> REGIONS = List.of("N", "S", "E", "W");

	private static final List<String> MEASURES = List.of("Amount", "Count");

	private static Tm1Client tm1;

	private static CellsetService cellsets;

	private static String cube;

	private static String region;

	private static String measure;

	private static String mdx;

	@BeforeAll
	static void createCube(LiveServer server) {
		tm1 = server.clientBuilder().build();
		cellsets = tm1.cellsets();
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		region = dimension(cube + "-region", REGIONS);
		measure = dimension(cube + "-measure", MEASURES);
		tm1.cubes()
			.create(CubeCreate.builder()
				.name(cube)
				.bindDimensions(ODataPath.of("Dimensions").key(region), ODataPath.of("Dimensions").key(measure))
				.build())
			.block(WAIT);
		// Ordinals run across the columns (measures) first: cell (row r, column c) is r *
		// 2 + c
		int value = 1;
		for (String r : REGIONS) {
			for (String m : MEASURES) {
				write(r, m, value++);
			}
		}
		mdx = "SELECT {[" + measure + "].[" + measure + "].Members} ON 0, {[" + region + "].[" + region
				+ "].Members} ON 1 FROM [" + cube + "]";
	}

	@AfterAll
	static void deleteCube() {
		tm1.cubes().delete(cube).block(WAIT);
		tm1.dimensions().delete(region).block(WAIT);
		tm1.dimensions().delete(measure).block(WAIT);
		tm1.close().block(WAIT);
	}

	@Test
	void runsMdxAndReadsTheCellsetPieceByPiece() {
		Cellset cellset = cellsets
			.execute(mdx, ODataQuery.none().expand("Axes").expand("Cells", c -> c.select("Ordinal", "Value")))
			.block(WAIT);
		String id = cellset.id();
		assertThat(id).isNotBlank();
		assertThat(cellset.axes()).as("columns, rows and the slicer TM1 adds").hasSize(3);
		assertThat(cellset.cells()).hasSize(8);
		try {
			assertThat(cellsets.list(ODataQuery.none()).collectList().block(WAIT))
				.as("TM1 lists none, though each reads by ID")
				.isEmpty();
			assertThat(cellsets.get(id, ODataQuery.none()).block(WAIT).id()).isEqualTo(id);
			assertThat(cellsets.axes(id, ODataQuery.none()).collectList().block(WAIT)).extracting(a -> a.cardinality())
				.containsExactly(2, 4, 1);
			assertThat(cellsets.hierarchies(id, 1, ODataQuery.none()).map(h -> h.name()).collectList().block(WAIT))
				.containsExactly(region);
			assertThat(cellsets.tuples(id, 1, ODataQuery.none()).collectList().block(WAIT)).hasSize(4);
			assertThat(cellsets.tupleMembers(id, 1, 2, ODataQuery.none()).map(Member::name).collectList().block(WAIT))
				.containsExactly("E");
			assertThat(
					values(cellsets.cells(id, ODataQuery.none().select("Ordinal", "Value")).collectList().block(WAIT)))
				.containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
			assertThat(cellsets.cellMembers(id, 5, ODataQuery.none()).map(Member::name).collectList().block(WAIT))
				.as("Sandboxes' Base first")
				.containsExactly("Base", "E", "Count");
			assertThat(cellsets.cube(id, ODataQuery.none()).block(WAIT).name()).isEqualTo(cube);
			assertThat(cellsets.partition(id, 2, 4, ODataQuery.none().select("Ordinal")).collectList().block(WAIT))
				.as("a rectangle: column 0 of rows 1 and 2")
				.extracting(CellsetCell::ordinal)
				.containsExactly(2L, 4L);
			assertThat(cellsets.partition(id, 2, 5, ODataQuery.none().select("Ordinal")).collectList().block(WAIT))
				.as("whole rows")
				.extracting(CellsetCell::ordinal)
				.containsExactly(2L, 3L, 4L, 5L);
		}
		finally {
			cellsets.delete(id).block(WAIT);
		}
		assertThatThrownBy(() -> cellsets.get(id, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void streamsCellsInOrderAcrossPagesAndDeletesTheCellset() {
		AtomicReference<String> id = new AtomicReference<>();
		List<Double> values = cellsets
			.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 3, (cellset, cells) -> {
				id.set(cellset.id());
				return cells.map(cell -> cell.value().asDouble());
			})
			.collectList()
			.block(WAIT);
		assertThat(values).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
		assertGone(id.get());
	}

	@Test
	void streamsAtLeastARowPerPage() {
		assertThat(cellsets
			.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 1,
					(cellset, cells) -> cells.map(CellsetCell::ordinal))
			.collectList()
			.block(WAIT)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
	}

	@Test
	void deletesTheCellsetWhenTheStreamIsCancelled() {
		AtomicReference<String> id = new AtomicReference<>();
		List<Long> first = cellsets.stream(mdx, ODataQuery.none().select("Ordinal"), 2, (cellset, cells) -> {
			id.set(cellset.id());
			return cells.map(CellsetCell::ordinal);
		}).take(3).collectList().block(WAIT);
		assertThat(first).containsExactly(0L, 1L, 2L);
		// The delete runs as the cancel propagates; give it a moment
		for (int i = 0; i < 50 && exists(id.get()); i++) {
			sleep();
		}
		assertGone(id.get());
	}

	@Test
	void executesMdxAndNativeViews() {
		tm1.views().create(cube, ViewScope.PUBLIC, MDXViewCreate.builder().name("Mdx").mdx(mdx).build()).block(WAIT);
		for (String d : List.of(region, measure)) {
			tm1.subsets()
				.create(d, d, SubsetScope.PUBLIC,
						SubsetCreate.builder()
							.name("All")
							.expression("{TM1SUBSETALL([" + d + "].[" + d + "])}")
							.build())
				.block(WAIT);
		}
		tm1.views()
			.create(cube, ViewScope.PUBLIC,
					NativeViewCreate.builder()
						.name("Native")
						.columns(List.of(axis(measure)))
						.rows(List.of(axis(region)))
						.build())
			.block(WAIT);
		for (String view : List.of("Mdx", "Native")) {
			Cellset cellset = cellsets
				.executeView(cube, ViewScope.PUBLIC, view, ODataQuery.none().expand("Cells", c -> c.select("Value")))
				.block(WAIT);
			try {
				assertThat(values(cellset.cells())).as(view).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
			}
			finally {
				cellsets.delete(cellset.id()).block(WAIT);
			}
		}
	}

	private static boolean exists(String id) {
		return cellsets.get(id, ODataQuery.none())
			.map(c -> true)
			.onErrorReturn(Tm1NotFoundException.class::isInstance, false)
			.block(WAIT);
	}

	private static void assertGone(String id) {
		assertThatThrownBy(() -> cellsets.get(id, ODataQuery.none()).block(WAIT)).as("the cellset is deleted")
			.isInstanceOf(Tm1NotFoundException.class);
	}

	private static List<Double> values(List<CellsetCell> cells) {
		return cells.stream().map(c -> c.value().asDouble()).toList();
	}

	private static ViewAxisSelectionPatch axis(String dimension) {
		return ViewAxisSelectionPatch.builder()
			.bindSubset(ODataPath.of("Dimensions")
				.key(dimension)
				.nav("Hierarchies")
				.key(dimension)
				.nav("Subsets")
				.key("All"))
			.build();
	}

	private static void write(String regionElement, String measureElement, int value) {
		// Writing cells is the next step's; the raw session does it here
		tm1.session()
			.execute(Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"),
					Map.of("Cells",
							List.of(Map.of("Tuple@odata.bind",
									List.of(element(region, regionElement), element(measure, measureElement)))),
							"Value", String.valueOf(value)),
					Void.class))
			.block(WAIT);
	}

	private static String element(String dimension, String element) {
		return ODataPath.of("Dimensions")
			.key(dimension)
			.nav("Hierarchies")
			.key(dimension)
			.nav("Elements")
			.key(element)
			.toString();
	}

	private static String dimension(String name, List<String> elements) {
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(name)
				.hierarchies(List.of(HierarchyCreate.builder()
					.name(name)
					.elements(elements.stream()
						.map(e -> ElementCreate.builder().name(e).type(ElementType.NUMERIC).build())
						.toList())
					.build()))
				.build())
			.block(WAIT);
		return name;
	}

	private static void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
