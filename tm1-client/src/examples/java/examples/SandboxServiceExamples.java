package examples;

import java.util.LinkedHashMap;
import java.util.Map;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.CellsetCell;
import net.ajmyers.tm1.client.model.SandboxPatch;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.data.SandboxService}, quoted in its
 * Javadoc.
 */
final class SandboxServiceExamples {

	Mono<Void> publish(Tm1Client tm1) {
		// @start region = publish
		// Write to a new sandbox, leaving base data alone until the changes are published
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Version", "Budget");
		cell.put("Region", "North");
		cell.put("Month", "Jan");
		cell.put("Measure", "Revenue");
		Tm1Client whatIf = tm1.withSandbox("What If");
		return tm1.sandboxes()
			.create(SandboxPatch.builder().name("What If").build())
			.then(whatIf.cells().update("Sales", cell, 1500))
			.then(tm1.sandboxes().publish("What If"));
		// @end
	}

	Mono<String> compare(Tm1Client tm1) {
		// @start region = compare
		// The same cell read in base data and in a sandbox: the same query, through a
		// client for each
		String mdx = "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]"
				+ " WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
		ODataQuery values = ODataQuery.none().select(CellsetCell.VALUE);
		Mono<JsonNode> base = tm1.cellsets()
			.stream(mdx, values, 1, (cellset, cells) -> cells.map(CellsetCell::value))
			.next();
		Mono<JsonNode> whatIf = tm1.withSandbox("What If")
			.cellsets()
			.stream(mdx, values, 1, (cellset, cells) -> cells.map(CellsetCell::value))
			.next();
		return Mono.zip(base, whatIf, (b, w) -> "base " + b + ", what-if " + w);
		// @end
	}

	private SandboxServiceExamples() {
	}

}
