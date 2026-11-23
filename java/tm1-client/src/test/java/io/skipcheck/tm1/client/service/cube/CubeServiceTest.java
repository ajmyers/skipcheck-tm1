// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.cube;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.CalculationComponent;
import io.skipcheck.tm1.client.model.CheckFeedersSelection;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.FedCellDescriptor;
import io.skipcheck.tm1.client.model.FeederTrace;
import io.skipcheck.tm1.client.model.RuleSyntaxError;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * CubeService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/CubeServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
class CubeServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String ELEMENTS = "[\"Dimensions('Region')/Hierarchies('Region')/Elements('North')\","
			+ "\"Dimensions('Month')/Hierarchies('Month')/Elements('Jan')\"]";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static Map<String, String> cell() {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Region", "North");
		cell.put("Month", "Jan");
		return cell;
	}

	@Test
	void listsAllCubesModelCubesAndControlCubes() {
		tm1().json("{\"value\":[{\"Name\":\"Sales\"},{\"Name\":\"}Stats\"}]}");
		List<Cube> all = conformance.call("cubes", "list", ODataQuery.none().top(2));
		assertThat(all).extracting(Cube::name).containsExactly("Sales", "}Stats");
		assertThat(tm1().last().key()).isEqualTo("GET Cubes?$top=2");

		tm1().json("{\"value\":[{\"Name\":\"Sales\"}]}");
		ODataQuery query = ODataQuery.none()
			.select(Cube.NAME)
			.filter(Filter.startsWith(Cube.NAME, "Sales"))
			.orderBy(Cube.NAME);
		List<Cube> model = conformance.call("cubes", "modelCubes", query);
		assertThat(model).extracting(Cube::name).containsExactly("Sales");
		assertThat(tm1().last().target())
			.isEqualTo("ModelCubes()?$select=Name&$filter=startswith(Name,'Sales')&$orderby=Name");

		tm1().json("{\"value\":[{\"Name\":\"}Stats\"}]}");
		List<Cube> control = conformance.call("cubes", "controlCubes", ODataQuery.none());
		assertThat(control).extracting(Cube::name).containsExactly("}Stats");
		assertThat(tm1().last().key()).isEqualTo("GET ControlCubes()");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Cubes?$skiptoken=2\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Cube> cubes = conformance.call("cubes", "list", ODataQuery.none().select("Name"));
		assertThat(cubes).extracting(Cube::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly("Cubes?$select=Name", "Cubes?$skiptoken=2");
	}

	@Test
	void getsACubeWithItsEtag() {
		tm1().json("{\"@odata.etag\":\"W/\\\"1\\\"\",\"Name\":\"Sales\",\"Rules\":\"SKIPCHECK;\"}");
		Cube cube = conformance.call("cubes", "get", "Sales", ODataQuery.none().select("Name", "Rules"));
		assertThat(cube.name()).isEqualTo("Sales");
		assertThat(cube.etag()).isEqualTo("W/\"1\"");
		assertThat(cube.rules()).isEqualTo("SKIPCHECK;");
		ConformanceTm1.Recorded get = tm1().last();
		assertThat(get.key()).isEqualTo("GET Cubes('Sales')?$select=Name,Rules");
		assertThat(get.header("Accept")).isEqualTo("application/json");
	}

	@Test
	void quotesANameWithAnApostrophe() {
		tm1().json("{\"Name\":\"Joe's\"}");
		conformance.call("cubes", "get", "Joe's", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Cubes('Joe''s')");
	}

	@Test
	void aMissingCubeIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Cube'.");
		Throwable error = conformance.fail("cubes", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsACubeOverDimensions() {
		ODataPath dimensions = ODataPath.of("Dimensions");
		tm1().json(201, "{\"Name\":\"Sales\"}");
		Cube created = conformance.call("cubes", "create",
				CubePatch.builder()
					.name("Sales")
					.bindDimensions(dimensions.key("Region"), dimensions.key("Month"))
					.build());
		assertThat(created.name()).isEqualTo("Sales");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Cubes");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo(
				"{\"Name\":\"Sales\",\"Dimensions@odata.bind\":[\"Dimensions('Region')\",\"Dimensions('Month')\"]}");
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		Object none = conformance.call("cubes", "update", "Sales", CubePatch.builder().rules(null).build());
		assertThat(none).isNull();
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH Cubes('Sales')");
		assertThat(patch.text()).isEqualTo("{\"Rules\":null}");
		assertThat(patch.header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("cubes", "update", "Sales", CubePatch.builder().rules("SKIPCHECK;").build(), "W/\"1\"");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"1\"");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("cubes", "update", "Sales", CubePatch.builder().rules("x").build(),
				"W/\"0\"");
		assertThat(error).isInstanceOfSatisfying(Tm1PreconditionFailedException.class, e -> {
			assertThat(e.kind()).isEqualTo(Tm1ErrorKind.PRECONDITION_FAILED);
			assertThat(e.error().code()).isEqualTo("278");
		});
	}

	@Test
	void deletesACube() {
		tm1().status(204);
		conformance.call("cubes", "delete", "Sales");
		assertThat(tm1().last().key()).isEqualTo("DELETE Cubes('Sales')");
	}

	@Test
	void runsEachCubeOperation() {
		for (String operation : List.of("load", "unload", "lock", "unlock", "reserve", "release")) {
			tm1().status(204);
			conformance.call("cubes", operation, "Sales");
			ConformanceTm1.Recorded post = tm1().last();
			String action = Character.toUpperCase(operation.charAt(0)) + operation.substring(1);
			assertThat(post.key()).isEqualTo("POST Cubes('Sales')/tm1." + action);
			assertThat(post.text()).isEqualTo("{}");
		}
	}

	@Test
	void reordersDimensions() {
		tm1().json("{\"@odata.context\":\"$metadata#Edm.Double\",\"value\":12.5}");
		Double time = conformance.call("cubes", "reorderDimensions", "Sales", List.of("Month", "Region"));
		assertThat(time).isEqualTo(12.5);
		assertThat(tm1().last().text())
			.isEqualTo("{\"Dimensions@odata.bind\":[\"Dimensions('Month')\",\"Dimensions('Region')\"]}");
	}

	@Test
	void readsDimensionsInBothOrders() {
		tm1().json("{\"value\":[{\"Name\":\"Month\"},{\"Name\":\"Region\"}]}");
		List<Dimension> dimensions = conformance.call("cubes", "dimensions", "Sales", ODataQuery.none());
		assertThat(dimensions).extracting(Dimension::name).containsExactly("Month", "Region");
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/Dimensions");

		tm1().json("{\"value\":[{\"Name\":\"Region\"},{\"Name\":\"Month\"}]}");
		List<Dimension> stored = conformance.call("cubes", "dimensionsStorageOrder", "Sales",
				ODataQuery.none().select("Name"));
		assertThat(stored).extracting(Dimension::name).containsExactly("Region", "Month");
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/tm1.DimensionsStorageOrder()?$select=Name");
	}

	@Test
	void checksRulesAsSavedOrAsGiven() {
		tm1().json("{\"value\":[{\"LineNumber\":2,\"Message\":\"Syntax error\"}]}");
		List<RuleSyntaxError> errors = conformance.call("cubes", "checkRules", "Sales", "SKIPCHECK");
		assertThat(errors).extracting(RuleSyntaxError::lineNumber).containsExactly(2);
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Sales')/tm1.CheckRules");
		assertThat(tm1().last().text()).isEqualTo("{\"Rules\":\"SKIPCHECK\"}");

		tm1().json("{\"value\":[]}");
		List<RuleSyntaxError> none = conformance.call("cubes", "checkRules", "Sales");
		assertThat(none).isEmpty();
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void tracesACellsCalculationAndFeeders() {
		tm1().json("{\"Type\":\"Rule\",\"Statements\":[\"['Margin'] = N: 1;\"]}");
		CalculationComponent component = conformance.call("cubes", "traceCellCalculation", "Sales", cell(),
				ODataQuery.none().expand("Components"));
		assertThat(component.statements()).containsExactly("['Margin'] = N: 1;");
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/tm1.TraceCellCalculation?$expand=Components");
		assertThat(tm1().last().text()).isEqualTo("{\"Tuple@odata.bind\":" + ELEMENTS + "}");

		tm1().json("{\"FedCells\":[{\"Fed\":true}],\"Statements\":[]}");
		FeederTrace trace = conformance.call("cubes", "traceFeeders", "Sales", cell(), ODataQuery.none());
		assertThat(trace.fedCells()).extracting(FedCellDescriptor::fed).containsExactly(true);
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Sales')/tm1.TraceFeeders");

		tm1().json("{\"value\":[{\"Fed\":false}]}");
		List<FedCellDescriptor> unfed = conformance.call("cubes", "checkFeeders", "Sales", cell(),
				CheckFeedersSelection.UNFED, ODataQuery.none().expand("Tuple"));
		assertThat(unfed).extracting(FedCellDescriptor::fed).containsExactly(false);
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/tm1.CheckFeeders?$expand=Tuple");
		assertThat(tm1().last().text()).isEqualTo("{\"Tuple@odata.bind\":" + ELEMENTS + ",\"Selection\":\"Unfed\"}");
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("cubes", "delete", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
