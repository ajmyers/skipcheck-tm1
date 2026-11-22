package net.ajmyers.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Cellset;
import net.ajmyers.tm1.client.model.Drillthrough;
import net.ajmyers.tm1.client.model.ProcessParameterPatch;
import net.ajmyers.tm1.client.model.Rowset;
import net.ajmyers.tm1.client.model.RowsetRow;
import net.ajmyers.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;

/**
 * DrillthroughService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/DrillthroughServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class DrillthroughServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static final String SCRIPT = "Cellsets('c1')/Cells(3)/DrillthroughScripts('Detail')";

	@Test
	void listsACellsDrillthroughs() {
		tm1().json("{\"value\":[{\"Name\":\"Detail\"},{\"Name\":\"Joe's\"}]}");
		List<Drillthrough> scripts = conformance.call("drillthrough", "scripts", "c1", 3L,
				ODataQuery.none().select("Name"));
		assertThat(scripts).extracting(Drillthrough::name).containsExactly("Detail", "Joe's");
		assertThat(tm1().last().key()).isEqualTo("GET Cellsets('c1')/Cells(3)/DrillthroughScripts?$select=Name");

		tm1().json("{\"value\":[]}");
		List<Drillthrough> none = conformance.call("drillthrough", "scripts", "c1", 0L, ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void runsACellsDrillthroughToACellset() {
		tm1().json("{\"ID\":\"d1\",\"Cells\":[{\"Value\":4}]}");
		Cellset detail = conformance.call("drillthrough", "execute", "c1", 3L, "Detail",
				ODataQuery.none().expand("Cells", c -> c.select("Value")));
		assertThat(detail.id()).isEqualTo("d1");
		assertThat(tm1().last().key()).isEqualTo("POST " + SCRIPT + "/tm1.Execute?$expand=Cells($select%3DValue)");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json("{\"ID\":\"d2\"}");
		conformance.call("drillthrough", "execute", "c'1", 0L, "Joe's", ODataQuery.none());
		assertThat(tm1().last().target())
			.isEqualTo("Cellsets('c''1')/Cells(0)/DrillthroughScripts('Joe''s')/tm1.Execute");
	}

	@Test
	void aMissingDrillthroughIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Drillthrough'.");
		Throwable error = conformance.fail("drillthrough", "execute", "c1", 3L, "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void runsARelationalDrillthroughToRowsOrARowset() {
		tm1().json("{\"value\":[{\"Region\":\"North\",\"Amount\":1},{\"Region\":\"South\",\"Amount\":2.5}]}");
		List<JsonNode> rows = conformance.call("drillthrough", "executeRelational", "c1", 3L, "Detail");
		assertThat(rows).hasSize(2);
		assertThat(rows.get(1).get("Amount").asDouble()).isEqualTo(2.5);
		assertThat(tm1().last().key()).isEqualTo("POST " + SCRIPT + "/tm1.Execute");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json("{\"ID\":\"r1\"}");
		Rowset rowset = conformance.call("drillthrough", "executeRelationalWithRowset", "c1", 3L, "Detail");
		assertThat(rowset.id()).isEqualTo("r1");
		assertThat(tm1().last().key()).isEqualTo("POST " + SCRIPT + "/tm1.ExecuteWithRowset");
	}

	@Test
	void runsADrillthroughProcessDirectly() {
		List<ProcessParameterPatch> parameters = List.of(
				ProcessParameterPatch.builder().name("pRegion").value("North").build(),
				ProcessParameterPatch.builder().name("pAmount").value(2).build());
		tm1().json("{\"ID\":\"d1\"}");
		Cellset cellset = conformance.call("drillthrough", "executeProcess", "Joe's Detail", parameters,
				ODataQuery.none().expand("Axes"));
		assertThat(cellset.id()).isEqualTo("d1");
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteCubeDrillthrough?$expand=Axes");
		assertThat(tm1().last().text())
			.isEqualTo("{\"DrillthroughProcess@odata.bind\":\"Processes('Joe''s%20Detail')\","
					+ "\"Parameters\":[{\"Name\":\"pRegion\",\"Value\":\"North\"},{\"Name\":\"pAmount\",\"Value\":2}]}");

		tm1().json("{\"ID\":\"d2\"}");
		conformance.call("drillthrough", "executeProcess", "Detail", List.of(), ODataQuery.none());
		assertThat(tm1().last().text()).isEqualTo("{\"DrillthroughProcess@odata.bind\":\"Processes('Detail')\"}");

		tm1().json("{\"value\":[{\"Region\":\"North\"}]}");
		List<JsonNode> rows = conformance.call("drillthrough", "executeRelationalProcess", "Detail", parameters);
		assertThat(rows).hasSize(1);
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteRelationalDrillthrough");

		tm1().json("{\"ID\":\"r1\"}");
		Rowset rowset = conformance.call("drillthrough", "executeRelationalProcessWithRowset", "Detail", List.of());
		assertThat(rowset.id()).isEqualTo("r1");
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteRelationalDrillthroughWithRowset");
		assertThat(tm1().last().text()).isEqualTo("{\"DrillthroughProcess@odata.bind\":\"Processes('Detail')\"}");
	}

	@Test
	void readsPagesAndDeletesARowset() {
		tm1().json("{\"ID\":\"r1\",\"Rows\":[{\"Region\":\"North\",\"Amount\":1}]}");
		Rowset rowset = conformance.call("drillthrough", "rowset", "r1", ODataQuery.none().expand("Rows"));
		assertThat(rowset.id()).isEqualTo("r1");
		assertThat(rowset.rows()).hasSize(1);
		assertThat(tm1().last().key()).isEqualTo("GET Rowsets('r1')?$expand=Rows");

		tm1().json("{\"value\":[{\"Region\":\"North\"}],\"@odata.nextLink\":\"Rowsets('r1')/Rows?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Region\":\"South\"}]}");
		List<RowsetRow> rows = conformance.call("drillthrough", "rows", "r1", ODataQuery.none().top(1));
		assertThat(rows).hasSize(2);
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.endsWith("Rowsets('r1')/Rows?$top=1", "Rowsets('r1')/Rows?$skiptoken=1");

		tm1().status(204);
		conformance.call("drillthrough", "deleteRowset", "r1");
		assertThat(tm1().last().key()).isEqualTo("DELETE Rowsets('r1')");

		tm1().error(404, "278", "'r1' can not be found in collection of type 'Rowset'.");
		conformance.fail("drillthrough", "rowset", "r1", ODataQuery.none());
	}

	@Test
	void refusesAMissingProcess() {
		Throwable error = conformance.fail("drillthrough", "executeRelationalProcess", null, List.of());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
