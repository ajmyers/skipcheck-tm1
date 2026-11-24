// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.CellsetCell;
import io.skipcheck.tm1.client.model.ErrorLogFile;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.ProcessSyntaxError;
import io.skipcheck.tm1.client.model.ProcessVariableType;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * The walkthrough "Run a process and read its result"
 * (api-site/content/walkthroughs/tm1-client), on SData's SalesCube. The live tests run
 * its steps and record what they print (WalkthroughExamplesTm1Test).
 */
public final class ProcessWalkthrough {

	public static void main(String[] args) {
		Tm1Client tm1 = ConnectWalkthrough.connect();
		try {
			create(tm1);
			run(tm1);
			check(tm1);
			fail(tm1);
			delete(tm1);
		}
		finally {
			tm1.close().block();
		}
	}

	static void create(Tm1Client tm1) {
		// @start region = create
		// A process that sets a month's budget to its actual units plus 10%
		ProcessCreate process = ProcessCreate.builder()
			.name("Budget From Actual")
			.parameters(List.of(ProcessParameterPatch.builder()
				.name("pMonth")
				.value("Jan")
				.type(ProcessVariableType.STRING)
				.build()))
			.prologProcedure(
					"""
							nActual = CellGetN('SalesCube', 'Actual', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);
							CellPutN(Round(nActual * 1.1), 'SalesCube', 'Budget', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);
							""")
			.build();
		// TM1 saves a process even when its code has errors, so compile it first
		List<ProcessSyntaxError> errors = tm1.processes().compile(process).collectList().block();
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException("The process doesn't compile: " + errors);
		}
		tm1.processes().create(process).block();
		// @end
	}

	static void run(Tm1Client tm1) {
		// @start region = run
		// Parameters by name; TM1 waits until the process ends to answer
		ProcessExecuteResult result = tm1.processes()
			.executeWithReturn("Budget From Actual", Map.of("pMonth", "Feb"), ODataQuery.none())
			.block();
		System.out.println("Status: " + result.processExecuteStatusCode().json());
		// @end
	}

	static void check(Tm1Client tm1) {
		// @start region = check
		String mdx = "SELECT {[actvsbud].[actvsbud].[Actual], [actvsbud].[actvsbud].[Budget]} ON COLUMNS FROM [SalesCube]"
				+ " WHERE ([region].[region].[France], [model].[model].[S Series 1.8 L Sedan],"
				+ " [account1].[account1].[Units], [month].[month].[Feb])";
		Cellset cellset = tm1.cellsets()
			.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, cells -> cells.select(CellsetCell.VALUE)))
			.block();
		System.out.println("Actual: " + cellset.cells().get(0).value());
		System.out.println("Budget: " + cellset.cells().get(1).value());
		tm1.cellsets().delete(cellset.id()).block();
		// @end
	}

	static void fail(Tm1Client tm1) {
		// @start region = fail
		// A month that isn't in the month dimension: TM1 runs the process, which fails
		ProcessExecuteResult result = tm1.processes()
			.executeWithReturn("Budget From Actual", Map.of("pMonth", "Febr"),
					ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE))
			.block();
		System.out.println("Status: " + result.processExecuteStatusCode().json());
		// A run with errors writes a log, which the result names
		ErrorLogFile log = result.errorLogFile();
		if (log != null) {
			System.out.println(tm1.logs().errorLogContent(log.filename()).block());
		}
		// @end
	}

	static void delete(Tm1Client tm1) {
		// @start region = delete
		tm1.processes().delete("Budget From Actual").block();
		// @end
	}

	private ProcessWalkthrough() {
	}

}
