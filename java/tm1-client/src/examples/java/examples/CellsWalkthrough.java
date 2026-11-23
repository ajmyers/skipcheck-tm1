// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetCell;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * The walkthrough "Read and write cells" (api-site/content/walkthroughs/tm1-client), on
 * SData's SalesCube. The live tests run its steps and record what they print
 * (WalkthroughExamplesTm1Test).
 */
public final class CellsWalkthrough {

	public static void main(String[] args) {
		Tm1Client tm1 = ConnectWalkthrough.connect();
		try {
			read(tm1);
			write(tm1);
			readBack(tm1);
		}
		finally {
			tm1.close().block();
		}
	}

	static void read(Tm1Client tm1) {
		// @start region = read
		// Units of one model sold in France, January to March
		String mdx = """
				SELECT {[month].[month].[Jan], [month].[month].[Feb], [month].[month].[Mar]} ON COLUMNS
				FROM [SalesCube]
				WHERE ([actvsbud].[actvsbud].[Actual], [region].[region].[France],
				  [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])""";
		// TM1 keeps the result as a cellset: get its cells' values with it,
		// then delete it
		Cellset cellset = tm1.cellsets()
			.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, cells -> cells.select(CellsetCell.VALUE)))
			.block();
		List<String> months = List.of("Jan", "Feb", "Mar");
		for (int i = 0; i < months.size(); i++) {
			System.out.println(months.get(i) + ": " + cellset.cells().get(i).value());
		}
		tm1.cellsets().delete(cellset.id()).block();
		// @end
	}

	static void write(Tm1Client tm1) {
		// @start region = write
		// A cell is an element of each of the cube's dimensions, in the cube's order
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("actvsbud", "Budget");
		cell.put("region", "France");
		cell.put("model", "S Series 1.8 L Sedan");
		cell.put("account1", "Units");
		cell.put("month", "Jan");
		tm1.cells().update("SalesCube", cell, 150).block();
		// @end
	}

	static void readBack(Tm1Client tm1) {
		// @start region = read-back
		String mdx = "SELECT {[month].[month].[Jan]} ON COLUMNS FROM [SalesCube]"
				+ " WHERE ([actvsbud].[actvsbud].[Budget], [region].[region].[France],"
				+ " [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])";
		Cellset cellset = tm1.cellsets()
			.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, cells -> cells.select(CellsetCell.VALUE)))
			.block();
		System.out.println("Budget for Jan: " + cellset.cells().get(0).value());
		tm1.cellsets().delete(cellset.id()).block();
		// @end
	}

	private CellsWalkthrough() {
	}

}
