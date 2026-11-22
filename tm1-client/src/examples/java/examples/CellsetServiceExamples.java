package examples;

import java.util.List;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Cellset;
import net.ajmyers.tm1.client.model.CellsetAxisTuple;
import net.ajmyers.tm1.client.model.CellsetCell;
import net.ajmyers.tm1.client.model.CellsetUpdatePatch;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.cube.ViewScope;
import net.ajmyers.tm1.client.service.data.CellsetService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.data.CellsetService}, quoted in its
 * Javadoc.
 */
final class CellsetServiceExamples {

	Flux<String> stream(Tm1Client tm1) {
		// @start region = stream
		String mdx = """
				SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb], [Month].[Month].[Mar]} ON 0,
				  {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1
				FROM [Sales]
				WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])""";
		return tm1.cellsets()
			.stream(mdx, ODataQuery.none().select(CellsetCell.ORDINAL, CellsetCell.VALUE), 10_000, (cellset, cells) -> {
				// Axis 0 is the columns, axis 1 the rows; ordinals run along a row
				List<CellsetAxisTuple> columns = cellset.axes().get(0).tuples();
				List<CellsetAxisTuple> rows = cellset.axes().get(1).tuples();
				return cells.map(cell -> {
					CellsetAxisTuple row = rows.get((int) (cell.ordinal() / columns.size()));
					CellsetAxisTuple column = columns.get((int) (cell.ordinal() % columns.size()));
					return row.members().get(0).name() + ", " + column.members().get(0).name() + ": " + cell.value();
				});
			});
		// @end
	}

	Mono<Void> write(Tm1Client tm1) {
		// @start region = write
		// Write by ordinal into the cells of a query, then delete its cellset; the cells
		// must be leaves, as TM1 doesn't spread to a consolidation
		CellsetService cellsets = tm1.cellsets();
		String mdx = "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0, {[Region].[Region].[North]} ON 1"
				+ " FROM [Sales] WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
		return Mono.usingWhen(cellsets.execute(mdx, ODataQuery.none()),
				cellset -> cellsets.updateCells(cellset.id(),
						List.of(CellsetUpdatePatch.builder().ordinal(0L).value(1200).build(),
								CellsetUpdatePatch.builder().ordinal(1L).value(1350).build())),
				cellset -> cellsets.delete(cellset.id()));
		// @end
	}

	Flux<JsonNode> view(Tm1Client tm1) {
		// @start region = view
		// The values of a saved view, read in the same round trip as its cellset
		CellsetService cellsets = tm1.cellsets();
		return Flux.usingWhen(
				cellsets.executeView("Sales", ViewScope.PUBLIC, "Revenue by Region",
						ODataQuery.none().expand(Cellset.CELLS, c -> c.select(CellsetCell.VALUE))),
				cellset -> Flux.fromIterable(cellset.cells()).map(CellsetCell::value),
				cellset -> cellsets.delete(cellset.id()));
		// @end
	}

	private CellsetServiceExamples() {
	}

}
