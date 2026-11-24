// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.CubeDrillthrough;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.Drillthrough;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.NativeViewCreate;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.SubsetCreate;
import io.skipcheck.tm1.client.model.ViewAxisSelectionPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * Cube drillthrough: a cube with a view "Detail", a }Drill_Detail process returning it,
 * and rules naming it.
 */
@RequiresTm1
class DrillthroughTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static String cube;

	private static String process;

	private static List<String> dimensions;

	@BeforeAll
	static void createFixtures(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		String id = UUID.randomUUID().toString().substring(0, 8);
		cube = "tm1-client-" + id;
		process = "}Drill_Detail" + id;
		dimensions = List.of(cube + "-a", cube + "-b");
		for (String d : dimensions) {
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
			tm1.subsets()
				.create(d, d, SubsetScope.PUBLIC,
						SubsetCreate.builder()
							.name("All")
							.expression("{TM1SUBSETALL([" + d + "].[" + d + "])}")
							.build())
				.block(WAIT);
		}
		tm1.cubes()
			.create(CubeCreate.builder()
				.name(cube)
				.bindDimensions(ODataPath.of("Dimensions").key(dimensions.get(0)),
						ODataPath.of("Dimensions").key(dimensions.get(1)))
				.drillthroughRules("[] = S: '" + process.substring("}Drill_".length()) + "';\n")
				.build())
			.block(WAIT);
		tm1.views()
			.create(cube, ViewScope.PUBLIC,
					NativeViewCreate.builder()
						.name("Detail")
						.columns(List.of(axis(dimensions.get(1))))
						.rows(List.of(axis(dimensions.get(0))))
						.build())
			.block(WAIT);
		// Processes are phase 5's; the raw session makes the drillthrough process here
		tm1.session()
			.execute(Tm1Request.post(ODataPath.of("Processes"),
					ProcessCreate.builder()
						.name(process)
						.parameters(List.of(parameter("pCube"), parameter("p0"), parameter("p1")))
						.epilogProcedure("ReturnViewHandle('" + cube + "', 'Detail');\n")
						.build(),
					Void.class))
			.block(WAIT);
	}

	@AfterAll
	static void deleteFixtures() {
		tm1.session().execute(Tm1Request.delete(ODataPath.of("Processes").key(process))).block(WAIT);
		tm1.cubes().delete(cube).block(WAIT);
		dimensions.forEach(d -> tm1.dimensions().delete(d).block(WAIT));
		tm1.close().block(WAIT);
	}

	@Test
	void runsADrillthroughProcessDirectly() {
		Cellset cellset = tm1.drillthrough()
			.executeProcess(process, List.of(), ODataQuery.none().expand("Cells", c -> c.select("Ordinal")))
			.block(WAIT);
		try {
			assertThat(cellset.cells()).as("the Detail view's 2 x 2 cells").hasSize(4);
		}
		finally {
			tm1.cellsets().delete(cellset.id()).block(WAIT);
		}
	}

	@Test
	void listsAndRunsACellsDrillthrough() {
		String a = dimensions.get(0);
		String b = dimensions.get(1);
		Cellset source = tm1.cellsets()
			.execute(
					"SELECT {[" + b + "].[" + b + "].[X]} ON 0, {[" + a + "].[" + a + "].[X]} ON 1 FROM [" + cube + "]",
					ODataQuery.none())
			.block(WAIT);
		try {
			List<Drillthrough> scripts = tm1.drillthrough()
				.scripts(source.id(), 0, ODataQuery.none())
				.collectList()
				.block(WAIT);
			assertThat(scripts).singleElement().isInstanceOf(CubeDrillthrough.class);
			Cellset detail = tm1.drillthrough()
				.execute(source.id(), 0, scripts.get(0).name(),
						ODataQuery.none().expand("Cells", c -> c.select("Ordinal")))
				.block(WAIT);
			try {
				assertThat(detail.cells()).hasSize(4);
			}
			finally {
				tm1.cellsets().delete(detail.id()).block(WAIT);
			}
		}
		finally {
			tm1.cellsets().delete(source.id()).block(WAIT);
		}
	}

	@Test
	@Tag("odbc")
	@Disabled("Relational drillthrough reads an ODBC data source, which the TM1 test container can't host")
	void runsRelationalDrillthrough() {
		List<?> rows = tm1.drillthrough()
			.executeRelationalProcess("}Drill_Relational", List.of())
			.collectList()
			.block(WAIT);
		assertThat(rows).isNotNull();
		String rowset = tm1.drillthrough()
			.executeRelationalProcessWithRowset("}Drill_Relational", List.of())
			.block(WAIT)
			.id();
		assertThat(tm1.drillthrough().rows(rowset, ODataQuery.none()).collectList().block(WAIT)).isNotNull();
		tm1.drillthrough().deleteRowset(rowset).block(WAIT);
	}

	private static ProcessParameterPatch parameter(String name) {
		return ProcessParameterPatch.builder().name(name).value("").build();
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

}
