package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceHandler;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetAxis;
import io.skipcheck.tm1.client.model.CellsetAxisTuple;
import io.skipcheck.tm1.client.model.CellsetCell;
import io.skipcheck.tm1.client.model.CellsetUpdatePatch;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.model.View;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import reactor.core.publisher.Flux;

/**
 * CellsetService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every recorded call is in
 * {@code spec/conformance/calls/CellsetServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay. A stream's pages are in flight a few at a time, so they're
 * scripted as an unordered group.
 */
class CellsetServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String MDX = "SELECT {[Month].[Jan]} ON 0 FROM [Sales]";

	private static final ODataQuery CELLS = ODataQuery.none().select("Ordinal", "Value");

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void executesMdxAndExpandsItsAxesAndCells() {
		tm1().json("{\"ID\":\"c1\",\"Cells\":[{\"Ordinal\":0,\"Value\":5}]}");
		ODataQuery query = ODataQuery.none()
			.expand("Axes", a -> a.expand("Tuples", t -> t.expand("Members", m -> m.select("Name"))))
			.expand("Cells", c -> c.select("Ordinal", "Value").top(10));
		Cellset cellset = conformance.call("cellsets", "execute", MDX, query);
		assertThat(cellset.id()).isEqualTo("c1");
		assertThat(cellset.cells()).extracting(CellsetCell::ordinal).containsExactly(0L);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.method()).isEqualTo("POST");
		assertThat(post.target()).startsWith("ExecuteMDX?$expand=Axes(");
		assertThat(post.json().get("MDX").asString()).isEqualTo(MDX);

		tm1().json("{\"ID\":\"c2\"}");
		Cellset plain = conformance.call("cellsets", "execute", "SELECT {} ON 0 FROM [Joe's]", ODataQuery.none());
		assertThat(plain.id()).isEqualTo("c2");
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteMDX");
	}

	@Test
	void badMdxIsABadRequest() {
		tm1().error(400, "501", "Syntax error in MDX");
		Throwable error = conformance.fail("cellsets", "execute", "SELEKT", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void runsAStatementForItsEffect() {
		tm1().status(204);
		Object none = conformance.call("cellsets", "executeStatement", "SELECT {} ON 0 FROM [}ClientGroups]");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteMDXStatement");
		assertThat(tm1().last().json().get("MDX").asString()).isEqualTo("SELECT {} ON 0 FROM [}ClientGroups]");

		tm1().error(400, "501", "Syntax error");
		conformance.fail("cellsets", "executeStatement", "CREATE SET [x] AS {}");
	}

	@Test
	void executesPublicAndPrivateViews() {
		tm1().json("{\"ID\":\"c1\"}");
		Cellset cellset = conformance.call("cellsets", "executeView", "Sales", ViewScope.PUBLIC, "By Region",
				ODataQuery.none().expand("Cells", c -> c.select("Value")));
		assertThat(cellset.id()).isEqualTo("c1");
		assertThat(tm1().last().key())
			.isEqualTo("POST Cubes('Sales')/Views('By%20Region')/tm1.Execute?$expand=Cells($select%3DValue)");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json("{\"ID\":\"c2\"}");
		conformance.call("cellsets", "executeView", "Joe's", ViewScope.PRIVATE, "Mine", ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("POST Cubes('Joe''s')/PrivateViews('Mine')/tm1.Execute");

		tm1().error(404, "278", "'Gone' can not be found in collection of type 'View'.");
		Throwable error = conformance.fail("cellsets", "executeView", "Sales", ViewScope.PUBLIC, "Gone",
				ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void listsGetsAndDeletesCellsets() {
		tm1().json("{\"value\":[]}");
		List<Cellset> none = conformance.call("cellsets", "list", ODataQuery.none().select("ID"));
		assertThat(none).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET Cellsets?$select=ID");

		tm1().json("{\"ID\":\"c1\",\"Cells\":[]}");
		Cellset cellset = conformance.call("cellsets", "get", "c1", ODataQuery.none().expand("Cells"));
		assertThat(cellset.id()).isEqualTo("c1");
		assertThat(tm1().last().key()).isEqualTo("GET Cellsets('c1')?$expand=Cells");

		tm1().status(204);
		conformance.call("cellsets", "delete", "c1");
		assertThat(tm1().last().key()).isEqualTo("DELETE Cellsets('c1')");
	}

	@Test
	void aDeletedCellsetIsNotFound() {
		tm1().error(404, "278", "'c1' can not be found in collection of type 'Cellset'.");
		Throwable error = conformance.fail("cellsets", "get", "c1", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);

		tm1().error(404, "278", "gone");
		conformance.fail("cellsets", "delete", "c1");
	}

	@Test
	void quotesAnIdWithAnApostrophe() {
		tm1().status(204);
		conformance.call("cellsets", "delete", "c'1");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c''1')");
	}

	@Test
	void readsAxesHierarchiesTuplesAndTheirMembers() {
		tm1().json("{\"value\":[{\"Ordinal\":0,\"Cardinality\":2},{\"Ordinal\":1,\"Cardinality\":1}]}");
		List<CellsetAxis> axes = conformance.call("cellsets", "axes", "c1",
				ODataQuery.none().expand("Tuples", t -> t.expand("Members")));
		assertThat(axes).extracting(CellsetAxis::cardinality).containsExactly(2, 1);
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Axes?$expand=Tuples($expand%3DMembers)");

		tm1().json("{\"value\":[{\"Name\":\"Month\"}]}");
		List<Hierarchy> hierarchies = conformance.call("cellsets", "hierarchies", "c1", 0,
				ODataQuery.none().select("Name"));
		assertThat(hierarchies).extracting(Hierarchy::name).containsExactly("Month");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Axes(0)/Hierarchies?$select=Name");

		tm1().json("{\"value\":[{\"Ordinal\":0},{\"Ordinal\":1}]}");
		List<CellsetAxisTuple> tuples = conformance.call("cellsets", "tuples", "c1", 1, ODataQuery.none().top(2));
		assertThat(tuples).hasSize(2);
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Axes(1)/Tuples?$top=2");

		tm1().json("{\"value\":[{\"Name\":\"Jan\",\"UniqueName\":\"[Month].[Month].[Jan]\"}]}");
		List<Member> members = conformance.call("cellsets", "tupleMembers", "c1", 0, 1,
				ODataQuery.none().select("Name", "UniqueName"));
		assertThat(members).extracting(Member::name).containsExactly("Jan");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Axes(0)/Tuples(1)/Members?$select=Name,UniqueName");
	}

	@Test
	void readsCellsTheirMembersAndTheCube() {
		tm1().json("{\"value\":[{\"Ordinal\":3,\"Value\":\"x\"}]}");
		List<CellsetCell> cells = conformance.call("cellsets", "cells", "c1", CELLS.skip(3).top(1));
		assertThat(cells.get(0).value().asString()).isEqualTo("x");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Cells?$select=Ordinal,Value&$top=1&$skip=3");

		tm1().json("{\"value\":[{\"Name\":\"Base\"},{\"Name\":\"Jan\"}]}");
		List<Member> members = conformance.call("cellsets", "cellMembers", "c1", 7L, ODataQuery.none().select("Name"));
		assertThat(members).extracting(Member::name).containsExactly("Base", "Jan");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Cells(7)/Members?$select=Name");

		tm1().json("{\"Name\":\"Sales\"}");
		Cube cube = conformance.call("cellsets", "cube", "c1", ODataQuery.none().select("Name"));
		assertThat(cube.name()).isEqualTo("Sales");
		assertThat(tm1().last().target()).isEqualTo("Cellsets('c1')/Cube?$select=Name");
	}

	@Test
	void readsAPartition() {
		tm1().json("{\"value\":[{\"Ordinal\":2,\"Value\":1},{\"Ordinal\":4,\"Value\":2}]}");
		List<CellsetCell> cells = conformance.call("cellsets", "partition", "c1", 2L, 4L, CELLS);
		assertThat(cells).extracting(CellsetCell::ordinal).containsExactly(2L, 4L);
		assertThat(tm1().last().key())
			.isEqualTo("GET Cellsets('c1')/tm1.GetPartition(Begin=2,End=4)?$select=Ordinal,Value");
	}

	@Test
	void writesNumbersTextAndNullsByOrdinal() {
		tm1().status(204);
		conformance.call("cellsets", "update", "c1", 0L, 4);
		assertThat(tm1().last().key()).isEqualTo("POST Cellsets('c1')/tm1.Update");
		assertThat(tm1().last().text()).isEqualTo("{\"BeginOrdinal\":0,\"EndOrdinal\":0,\"Value\":4}");

		tm1().status(204);
		conformance.call("cellsets", "update", "c1", 5L, "it's");
		assertThat(tm1().last().text()).isEqualTo("{\"BeginOrdinal\":5,\"EndOrdinal\":5,\"Value\":\"it's\"}");

		tm1().status(204);
		conformance.call("cellsets", "update", "c1", 1L, 2.5);
		assertThat(tm1().last().text()).isEqualTo("{\"BeginOrdinal\":1,\"EndOrdinal\":1,\"Value\":2.5}");

		tm1().status(204);
		conformance.call("cellsets", "update", "c1", 1L, null);
		assertThat(tm1().last().text()).isEqualTo("{\"BeginOrdinal\":1,\"EndOrdinal\":1,\"Value\":null}");
	}

	@Test
	void writesManyCellsAndRefusesAConsolidation() {
		tm1().status(204);
		conformance.call("cellsets", "updateCells", "c1",
				List.of(CellsetUpdatePatch.builder().ordinal(0L).value("x").build(),
						CellsetUpdatePatch.builder().ordinal(1L).value(2.5).build(),
						CellsetUpdatePatch.builder()
							.ordinal(2L)
							.value(1)
							.bindReferenceCube(ODataPath.of("Cubes").key("Plan"))
							.build()));
		assertThat(tm1().last().key()).isEqualTo("POST Cellsets('c1')/tm1.UpdateCells");
		assertThat(tm1().last().text()).isEqualTo("{\"Updates\":[{\"Ordinal\":0,\"Value\":\"x\"},"
				+ "{\"Ordinal\":1,\"Value\":2.5},{\"Ordinal\":2,\"Value\":1,\"ReferenceCube@odata.bind\":\"Cubes('Plan')\"}]}");

		tm1().error(400, "CubeCellWriteStatusElementIsConsolidated", "consolidated");
		Throwable error = conformance.fail("cellsets", "updateCells", "c1",
				List.of(CellsetUpdatePatch.builder().ordinal(3L).value(1).build()));
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void savesItsQueryAsAView() {
		tm1().json(201, "{\"Name\":\"Saved\"}");
		View saved = conformance.call("cellsets", "saveViewAs", "c1", "Saved");
		assertThat(saved.name()).isEqualTo("Saved");
		assertThat(tm1().last().key()).isEqualTo("POST Cellsets('c1')/tm1.SaveViewAs");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Saved\"}");

		tm1().json(201, "{\"Name\":\"Mine\"}");
		conformance.call("cellsets", "saveViewAs", "c1", "Mine", true, true);
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Mine\",\"Overwrite\":true,\"MakePrivate\":true}");

		tm1().json(201, "{\"Name\":\"Public\"}");
		conformance.call("cellsets", "saveViewAs", "c1", "Public", false, false);
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Public\",\"Overwrite\":false,\"MakePrivate\":false}");

		tm1().error(409, "278", "'Saved' already exists");
		conformance.fail("cellsets", "saveViewAs", "c1", "Saved");
	}

	/**
	 * A first answer to a stream's ExecuteMDX: axes of these cardinalities, and the first
	 * cells.
	 */
	private static String executed(String id, int inline, long... cardinalities) {
		StringBuilder axes = new StringBuilder();
		for (int i = 0; i < cardinalities.length; i++) {
			axes.append(i == 0 ? "" : ",")
				.append("{\"Ordinal\":")
				.append(i)
				.append(",\"Cardinality\":")
				.append(cardinalities[i])
				.append('}');
		}
		return "{" + (id == null ? "" : "\"ID\":\"" + id + "\",") + "\"Axes\":[" + axes + "],\"Cells\":"
				+ cells(0, inline - 1) + "}";
	}

	/** Cells {@code from} to {@code to}, each its ordinal as its value. */
	private static String cells(long from, long to) {
		return LongStream.rangeClosed(from, to)
			.mapToObj(i -> "{\"Ordinal\":" + i + ",\"Value\":" + i + "}")
			.collect(Collectors.joining(",", "[", "]"));
	}

	private static String page(long from, long to) {
		return "{\"value\":" + cells(from, to) + "}";
	}

	private static String partition(long begin, long end) {
		return "GET Cellsets('c1')/tm1.GetPartition(Begin=" + begin + ",End=" + end + ")?$select=Ordinal,Value";
	}

	private static List<Long> ordinals(List<CellsetCell> cells) {
		return cells.stream().map(CellsetCell::ordinal).toList();
	}

	@Test
	void streamsTwoAxesInPagesOfWholeRowsAndDeletesTheCellset() {
		// 3 columns × 4 rows; the first page of 5 ends mid-row, so row 1 comes again
		tm1().json(executed("c1", 5, 3, 4, 1));
		tm1().unordered(pages -> pages.json(partition(3, 5), page(3, 5))
			.json(partition(6, 8), page(6, 8))
			.json(partition(9, 11), page(9, 11)));
		tm1().status(204);
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 5, ConformanceHandler.CELLS);
		assertThat(ordinals(cells)).containsExactlyElementsOf(LongStream.range(0, 12).boxed().toList());
		List<ConformanceTm1.Recorded> requests = tm1().requests();
		assertThat(requests.get(0).target()).startsWith("ExecuteMDX?$expand=Axes(")
			.endsWith("Cells($select%3DOrdinal,Value;$top%3D5)");
		assertThat(requests.get(requests.size() - 1).key()).isEqualTo("DELETE Cellsets('c1')");
	}

	@Test
	void streamsManyRowsPerPage() {
		// 2 columns × 7 rows in pages of 4 cells: 2 rows a page
		tm1().json(executed("c1", 4, 2, 7));
		tm1().unordered(pages -> pages.json(partition(4, 7), page(4, 7))
			.json(partition(8, 11), page(8, 11))
			.json(partition(12, 13), page(12, 13)));
		tm1().status(204);
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 4, ConformanceHandler.CELLS);
		assertThat(ordinals(cells)).containsExactlyElementsOf(LongStream.range(0, 14).boxed().toList());
	}

	@Test
	void streamsAtLeastARowPerPage() {
		// 4 columns in pages of 2 cells: a page is still a whole row
		tm1().json(executed("c1", 2, 4, 2));
		tm1().unordered(pages -> pages.json(partition(0, 3), page(0, 3)).json(partition(4, 7), page(4, 7)));
		tm1().status(204);
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 2, ConformanceHandler.CELLS);
		assertThat(ordinals(cells)).containsExactlyElementsOf(LongStream.range(0, 8).boxed().toList());
	}

	@Test
	void streamsMoreAxesBySkipAndTop() {
		// 2 × 2 × 2: a third axis isn't a rectangle, so the pages are $skip and $top of
		// Cells
		tm1().json(executed("c1", 3, 2, 2, 2));
		tm1().unordered(pages -> pages.json("GET Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=3", page(3, 5))
			.json("GET Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=6", page(6, 7)));
		tm1().status(204);
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 3, ConformanceHandler.CELLS);
		assertThat(ordinals(cells)).containsExactlyElementsOf(LongStream.range(0, 8).boxed().toList());
	}

	@Test
	void aStreamThatFitsInOnePageMakesNoMoreRequests() {
		tm1().json(executed("c1", 4, 2, 2));
		tm1().status(204);
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 10, ConformanceHandler.CELLS);
		assertThat(cells).hasSize(4);
		assertThat(tm1().requests()).hasSize(2);
	}

	@Test
	void aStreamWhosePageFailsStillDeletesItsCellset() {
		tm1().json(executed("c1", 2, 2, 2));
		tm1().error(404, "278", "'c1' can not be found in collection of type 'Cellset'.");
		tm1().status(204);
		Throwable error = conformance.fail("cellsets", "stream", MDX, CELLS, 2, ConformanceHandler.CELLS);
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
		assertThat(tm1().last().key()).isEqualTo("DELETE Cellsets('c1')");
	}

	@Test
	void aStreamOfACellsetWithoutAnIdHasNothingToPageOrDelete() {
		tm1().json(executed(null, 2, 2, 1));
		List<CellsetCell> cells = conformance.call("cellsets", "stream", MDX, CELLS, 2, ConformanceHandler.CELLS);
		assertThat(cells).hasSize(2);
		assertThat(tm1().requests()).hasSize(1);
	}

	@Test
	void aStreamFailsOnABadQueryAndHasNothingToDelete() {
		tm1().error(400, "501", "Syntax error");
		Throwable error = conformance.fail("cellsets", "stream", "SELEKT", CELLS, 2, ConformanceHandler.CELLS);
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
		assertThat(tm1().requests()).hasSize(1);
	}

	@Test
	void aStreamNeedsAPositivePageSize() {
		Throwable error = conformance.fail("cellsets", "stream", MDX, CELLS, 0, ConformanceHandler.CELLS);
		assertThat(error).isInstanceOf(IllegalArgumentException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void quotesAnApostropheInAViewName() {
		tm1().json("{\"ID\":\"c1\"}");
		conformance.call("cellsets", "executeView", "Sales", ViewScope.PUBLIC, "Joe's view", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Cubes('Sales')/Views('Joe''s%20view')/tm1.Execute");
	}

	// Handlers that aren't a recordable name: plain tests, hand-ported to every client

	@Test
	void givesTheHandlersResultAndDeletesTheCellsetAfter() {
		tm1().json(executed("c1", 4, 2, 2));
		tm1().status(204);
		List<String> summary = conformance.client()
			.cellsets()
			.stream(MDX, CELLS, 10,
					(cellset, cells) -> cells.count().map(n -> cellset.id() + ":" + n + ":" + cellset.axes().size()))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertThat(summary).containsExactly("c1:4:2");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly(tm1().requests().get(0).key(), "DELETE Cellsets('c1')");
	}

	@Test
	void deletesTheCellsetWhenTheHandlerFails() {
		tm1().json(executed("c1", 4, 2, 2));
		tm1().status(204);
		Flux<Object> failing = conformance.client()
			.cellsets()
			.stream(MDX, CELLS, 10, (cellset, cells) -> Flux.error(new IllegalStateException("handler")));
		assertThatThrownBy(() -> failing.blockLast(Duration.ofSeconds(5))).hasMessageContaining("handler");
		assertThat(tm1().last().key()).isEqualTo("DELETE Cellsets('c1')");
	}

	@Test
	void deletesTheCellsetWhenTheStreamIsCancelled() {
		tm1().json(executed("c1", 3, 3, 4));
		tm1().unordered(pages -> pages.json(partition(3, 5), page(3, 5)).json(partition(6, 8), page(6, 8)));
		tm1().status(204);
		List<CellsetCell> first = new ArrayList<>();
		conformance.client()
			.cellsets()
			.stream(MDX, CELLS, 3, (cellset, cells) -> cells)
			.take(4)
			.doOnNext(first::add)
			.blockLast(Duration.ofSeconds(5));
		assertThat(ordinals(first)).containsExactly(0L, 1L, 2L, 3L);
		assertThat(tm1().last().key()).isEqualTo("DELETE Cellsets('c1')");
	}

}
