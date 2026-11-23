// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetCell;
import io.skipcheck.tm1.client.model.CubeDrillthrough;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.data.CellsetService;
import io.skipcheck.tm1.client.service.data.DrillthroughService;
import reactor.core.publisher.Flux;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.data.DrillthroughService}, quoted
 * in its Javadoc.
 */
final class DrillthroughServiceExamples {

	Flux<CellsetCell> cell(Tm1Client tm1) {
		// @start region = cell
		// The detail behind one cell: query it, run its first cube drillthrough, and read
		// the cellset that returns. Both cellsets are deleted at the end.
		CellsetService cellsets = tm1.cellsets();
		DrillthroughService drillthrough = tm1.drillthrough();
		String mdx = "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]"
				+ " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
		ODataQuery cells = ODataQuery.none()
			.expand(Cellset.CELLS, c -> c.select(CellsetCell.ORDINAL, CellsetCell.VALUE));
		return Flux.usingWhen(cellsets.execute(mdx, ODataQuery.none()),
				source -> drillthrough.scripts(source.id(), 0, ODataQuery.none())
					.filter(CubeDrillthrough.class::isInstance)
					.next()
					.flatMapMany(script -> Flux.usingWhen(drillthrough.execute(source.id(), 0, script.name(), cells),
							detail -> Flux.fromIterable(detail.cells()), detail -> cellsets.delete(detail.id()))),
				source -> cellsets.delete(source.id()));
		// @end
	}

	Flux<CellsetCell> process(Tm1Client tm1) {
		// @start region = process
		// A drillthrough process run directly, with the parameters it declares
		CellsetService cellsets = tm1.cellsets();
		List<ProcessParameterPatch> parameters = List.of(
				ProcessParameterPatch.builder().name("pRegion").value("North").build(),
				ProcessParameterPatch.builder().name("pMonth").value("Jan").build());
		return Flux.usingWhen(
				tm1.drillthrough()
					.executeProcess("}Drill_Sales Detail", parameters,
							ODataQuery.none().expand(Cellset.CELLS, c -> c.select(CellsetCell.VALUE))),
				detail -> Flux.fromIterable(detail.cells()), detail -> cellsets.delete(detail.id()));
		// @end
	}

	Flux<String> rowset(Tm1Client tm1) {
		// @start region = rowset
		// Rows from a relational drillthrough, kept as a rowset and streamed from it.
		// Each row's columns are in its dynamic properties.
		DrillthroughService drillthrough = tm1.drillthrough();
		return Flux.usingWhen(drillthrough.executeRelationalProcessWithRowset("}Drill_Sales Orders", List.of()),
				rowset -> drillthrough.rows(rowset.id(), ODataQuery.none())
					.map(row -> row.dynamicProperties().get("OrderNumber").asString()),
				rowset -> drillthrough.deleteRowset(rowset.id()));
		// @end
	}

	private DrillthroughServiceExamples() {
	}

}
