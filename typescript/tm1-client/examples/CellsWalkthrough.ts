// SPDX-License-Identifier: Apache-2.0

import { Cellset, CellsetCell, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";
import { connect } from "./ConnectWalkthrough.js";

// The walkthrough "Read and write cells" (api-site/content/walkthroughs/tm1-client), on SData's
// SalesCube. The live tests run its steps and record what they print (WalkthroughExamples.live.test.ts).

/** Runs the walkthrough's steps in order. */
export async function main(): Promise<void> {
  const tm1 = connect();
  try {
    await read(tm1);
    await write(tm1);
    await readBack(tm1);
  } finally {
    await tm1.close();
  }
}

export async function read(tm1: Tm1Client): Promise<void> {
  // @start region = read
  // Units of one model sold in France, January to March
  const mdx = `
    SELECT {[month].[month].[Jan], [month].[month].[Feb], [month].[month].[Mar]} ON COLUMNS
    FROM [SalesCube]
    WHERE ([actvsbud].[actvsbud].[Actual], [region].[region].[France],
      [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])`;
  // TM1 keeps the result as a cellset: get its cells' values with it, then delete it
  const cellset = await tm1.cellsets.execute(
    mdx,
    ODataQuery.none().expand(Cellset.Fields.Cells, (cells) => cells.select(CellsetCell.Fields.Value)),
  );
  const months = ["Jan", "Feb", "Mar"];
  months.forEach((month, i) => console.log(`${month}: ${String(cellset.Cells![i]!.Value)}`));
  await tm1.cellsets.delete(cellset.ID!);
  // @end
}

export async function write(tm1: Tm1Client): Promise<void> {
  // @start region = write
  // A cell is an element of each of the cube's dimensions, in the cube's order
  const cell = { actvsbud: "Budget", region: "France", model: "S Series 1.8 L Sedan", account1: "Units", month: "Jan" };
  await tm1.cells.update("SalesCube", cell, 150);
  // @end
}

export async function readBack(tm1: Tm1Client): Promise<void> {
  // @start region = read-back
  const mdx =
    "SELECT {[month].[month].[Jan]} ON COLUMNS FROM [SalesCube]" +
    " WHERE ([actvsbud].[actvsbud].[Budget], [region].[region].[France]," +
    " [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])";
  const cellset = await tm1.cellsets.execute(
    mdx,
    ODataQuery.none().expand(Cellset.Fields.Cells, (cells) => cells.select(CellsetCell.Fields.Value)),
  );
  console.log(`Budget for Jan: ${String(cellset.Cells![0]!.Value)}`);
  await tm1.cellsets.delete(cellset.ID!);
  // @end
}
