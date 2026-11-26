// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.cube;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.CalculationComponent;
import io.skipcheck.tm1.client.model.CheckFeedersSelection;
import io.skipcheck.tm1.client.model.CubeCreate;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.EdgeCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.FedCellDescriptor;
import io.skipcheck.tm1.client.model.FeederTrace;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.RuleSyntaxError;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * Rule checks and traces, on Region (Total = North + South) by Measure (Amount, Double =
 * Amount * 2).
 */
@LiveTm1
class CubeRulesTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final String RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\nFEEDERS;\n['Amount'] => ['Double'];\n";

	private static final String UNFED_RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\n";

	private static Tm1Client tm1;

	private static CubeService cubes;

	private static String cube;

	private static String region;

	private static String measure;

	@BeforeAll
	static void createCube(LiveServer server) {
		tm1 = server.clientBuilder().build();
		cubes = tm1.cubes();
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		region = cube + "-region";
		measure = cube + "-measure";
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
					.elements(Arrays.asList(element("Amount", ElementType.NUMERIC),
							element("Double", ElementType.NUMERIC)))
					.build()))
				.build())
			.block(WAIT);
		cubes
			.create(CubeCreate.builder()
				.name(cube)
				.rules(RULES)
				.bindDimensions(ODataPath.of("Dimensions").key(region), ODataPath.of("Dimensions").key(measure))
				.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteCube() {
		cubes.delete(cube).block(WAIT);
		tm1.dimensions().delete(region).block(WAIT);
		tm1.dimensions().delete(measure).block(WAIT);
		tm1.close().block(WAIT);
	}

	@Test
	void checksSavedAndProposedRules() {
		assertThat(cubes.checkRules(cube).collectList().block(WAIT)).isEmpty();
		List<RuleSyntaxError> errors = cubes.checkRules(cube, "SKIPCHECK;\n['Double'] = N: ['Amount'] *;\n")
			.collectList()
			.block(WAIT);
		assertThat(errors).isNotEmpty().allSatisfy(e -> {
			assertThat(e.lineNumber()).isEqualTo(2);
			assertThat(e.message()).isNotBlank();
		});
		assertThat(cubes.get(cube, ODataQuery.none()).block(WAIT).rules()).as("checking doesn't save").isEqualTo(RULES);
	}

	@Test
	void tracesACalculationAndFeeders() {
		CalculationComponent calculation = cubes
			.traceCellCalculation(cube, tuple("North", "Double"), ODataQuery.none().expand("Tuple").expand("Cube"))
			.block(WAIT);
		assertThat(calculation.statements()).anySatisfy(s -> assertThat(s).contains("['Amount'] * 2"));
		assertThat(calculation.cube().name()).isEqualTo(cube);
		assertThat(calculation.tuple()).extracting(e -> e.name()).containsExactly("North", "Double");

		FeederTrace feeders = cubes
			.traceFeeders(cube, tuple("North", "Amount"), ODataQuery.none().expand("FedCells/Tuple"))
			.block(WAIT);
		assertThat(feeders.fedCells())
			.anySatisfy(c -> assertThat(c.tuple()).extracting(e -> e.name()).containsExactly("North", "Double"));
	}

	@Test
	void checksFeedersUnderAConsolidation() {
		// Both needs Selection, which servers before schema 11.8.32 don't have
		ServerAssumptions.assumeSupportsParameter(tm1, Tm1Operations.CUBE_CHECK_FEEDERS, "Selection");
		// A value to feed from; writing cells is phase 4's, so the raw session does it
		// here
		tm1.session()
			.execute(Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"), Map.of("Cells",
					List.of(Map.of("Tuple@odata.bind", List.of(element(region, "North"), element(measure, "Amount")))),
					"Value", "5"), Void.class))
			.block(WAIT);
		List<FedCellDescriptor> cells = cubes
			.checkFeeders(cube, tuple("Total", "Double"), CheckFeedersSelection.BOTH, ODataQuery.none().expand("Tuple"))
			.collectList()
			.block(WAIT);
		assertThat(cells).anySatisfy(c -> {
			assertThat(c.tuple()).extracting(e -> e.name()).containsExactly("North", "Double");
			assertThat(c.fed()).isTrue();
		});
	}

	@Test
	void listsUnfedCellsOnEveryServer() {
		// Unfed is what servers without Selection (before schema 11.8.32) list, so it
		// works on every server: there it's sent without Selection. South written after
		// the
		// feeder is taken out makes South/Double a cell the rules calculate and nothing
		// feeds (a cell fed once stays fed, so not North)
		cubes.update(cube, CubePatch.builder().rules(UNFED_RULES).build()).block(WAIT);
		try {
			tm1.session()
				.execute(Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"),
						Map.of("Cells",
								List.of(Map.of("Tuple@odata.bind",
										List.of(element(region, "South"), element(measure, "Amount")))),
								"Value", "5"),
						Void.class))
				.block(WAIT);
			List<FedCellDescriptor> cells = cubes
				.checkFeeders(cube, tuple("Total", "Double"), CheckFeedersSelection.UNFED,
						ODataQuery.none().expand("Tuple"))
				.collectList()
				.block(WAIT);
			assertThat(cells).allSatisfy(c -> assertThat(c.fed()).isNotEqualTo(true))
				.anySatisfy(c -> assertThat(c.tuple()).extracting(e -> e.name()).containsExactly("South", "Double"));
		}
		finally {
			cubes.update(cube, CubePatch.builder().rules(RULES).build()).block(WAIT);
		}
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

	private static Map<String, String> tuple(String regionElement, String measureElement) {
		Map<String, String> tuple = new LinkedHashMap<>();
		tuple.put(region, regionElement);
		tuple.put(measure, measureElement);
		return tuple;
	}

	private static ElementCreate element(String name, ElementType type) {
		return ElementCreate.builder().name(name).type(type).build();
	}

	private static EdgeCreate edge(String parent, String component) {
		return EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();
	}

}
