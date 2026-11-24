// SPDX-License-Identifier: Apache-2.0

import { Cellset, CellsetCell, ODataQuery, ProcessCreate, ProcessExecuteResult, ProcessParameterPatch, type Tm1Client } from "@skipcheck/tm1-client";
import { connect } from "./ConnectWalkthrough.js";

// The walkthrough "Run a process and read its result" (api-site/content/walkthroughs/tm1-client), on
// SData's SalesCube. The live tests run its steps and record what they print (WalkthroughExamples.live.test.ts).

/** Runs the walkthrough's steps in order. */
export async function main(): Promise<void> {
  const tm1 = connect();
  try {
    await create(tm1);
    await run(tm1);
    await check(tm1);
    await fail(tm1);
    await remove(tm1);
  } finally {
    await tm1.close();
  }
}

export async function create(tm1: Tm1Client): Promise<void> {
  // @start region = create
  // A process that sets a month's budget to its actual units plus 10%
  const process = ProcessCreate.builder()
    .name("Budget From Actual")
    .parameters([ProcessParameterPatch.builder().name("pMonth").value("Jan").build()])
    .prologProcedure(
      "nActual = CellGetN('SalesCube', 'Actual', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);\n" +
        "CellPutN(Round(nActual * 1.1), 'SalesCube', 'Budget', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);\n",
    )
    .build();
  // TM1 saves a process even when its code has errors, so compile it first
  const errors = await tm1.processes.compile(process).toArray();
  if (errors.length > 0) throw new Error("The process doesn't compile: " + JSON.stringify(errors));
  await tm1.processes.create(process);
  // @end
}

export async function run(tm1: Tm1Client): Promise<void> {
  // @start region = run
  // Parameters by name; TM1 waits until the process ends to answer
  const result = await tm1.processes.executeWithReturn("Budget From Actual", { pMonth: "Feb" }, ODataQuery.none());
  console.log(`Status: ${result.ProcessExecuteStatusCode}`);
  // @end
}

export async function check(tm1: Tm1Client): Promise<void> {
  // @start region = check
  const mdx =
    "SELECT {[actvsbud].[actvsbud].[Actual], [actvsbud].[actvsbud].[Budget]} ON COLUMNS FROM [SalesCube]" +
    " WHERE ([region].[region].[France], [model].[model].[S Series 1.8 L Sedan]," +
    " [account1].[account1].[Units], [month].[month].[Feb])";
  const cellset = await tm1.cellsets.execute(
    mdx,
    ODataQuery.none().expand(Cellset.Fields.Cells, (cells) => cells.select(CellsetCell.Fields.Value)),
  );
  console.log(`Actual: ${String(cellset.Cells![0]!.Value)}`);
  console.log(`Budget: ${String(cellset.Cells![1]!.Value)}`);
  await tm1.cellsets.delete(cellset.ID!);
  // @end
}

export async function fail(tm1: Tm1Client): Promise<void> {
  // @start region = fail
  // A month that isn't in the month dimension: TM1 runs the process, which fails
  const result = await tm1.processes.executeWithReturn(
    "Budget From Actual",
    { pMonth: "Febr" },
    ODataQuery.none().expand(ProcessExecuteResult.Fields.ErrorLogFile),
  );
  console.log(`Status: ${result.ProcessExecuteStatusCode}`);
  // A run with errors writes a log, which the result names
  const log = result.ErrorLogFile;
  if (log) {
    console.log(await tm1.logs.errorLogContent(log.Filename!));
  }
  // @end
}

/** Java's delete: `delete` is a keyword here. */
export async function remove(tm1: Tm1Client): Promise<void> {
  // @start region = delete
  await tm1.processes.delete("Budget From Actual");
  // @end
}
