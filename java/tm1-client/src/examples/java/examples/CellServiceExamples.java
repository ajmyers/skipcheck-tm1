// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.CubeUpdatePatch;
import io.skipcheck.tm1.client.service.data.Tuples;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.data.CellService}, quoted in its
 * Javadoc.
 */
final class CellServiceExamples {

	Mono<Void> update(Tm1Client tm1) {
		// @start region = update
		// The cell: an element for every dimension of the cube, in the cube's order
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Version", "Budget");
		cell.put("Region", "North");
		cell.put("Month", "Jan");
		cell.put("Measure", "Revenue");
		return tm1.cells().update("Sales", cell, 1200);
		// @end
	}

	Mono<Void> updateCells(Tm1Client tm1) {
		// @start region = update-cells
		// A budget for North, one value per month, written in one request
		Map<String, Integer> budget = new LinkedHashMap<>();
		budget.put("Jan", 1200);
		budget.put("Feb", 1350);
		budget.put("Mar", 1100);
		List<CubeUpdatePatch> updates = new ArrayList<>();
		budget.forEach((month, value) -> {
			Map<String, String> cell = new LinkedHashMap<>();
			cell.put("Version", "Budget");
			cell.put("Region", "North");
			cell.put("Month", month);
			cell.put("Measure", "Revenue");
			updates.add(CubeUpdatePatch.builder().bindTuple(Tuples.of(cell)).value(value).build());
		});
		return tm1.cells().updateCells("Sales", updates);
		// @end
	}

	private CellServiceExamples() {
	}

}
