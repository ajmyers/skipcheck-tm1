// SPDX-License-Identifier: Apache-2.0

import { CellsetCell, ODataQuery, SandboxPatch, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for SandboxService, quoted in its documentation.

export async function publish(tm1: Tm1Client): Promise<void> {
  // @start region = publish
  // Write to a new sandbox, leaving base data alone until the changes are published
  const cell = { Version: "Budget", Region: "North", Month: "Jan", Measure: "Revenue" };
  await tm1.sandboxes.create(SandboxPatch.builder().name("What If").build());
  await tm1.withSandbox("What If").cells.update("Sales", cell, 1500);
  await tm1.sandboxes.publish("What If");
  // @end
}

export async function compare(tm1: Tm1Client): Promise<string> {
  // @start region = compare
  // The same cell read in base data and in a sandbox: the same query, through a client
  // for each
  const mdx =
    "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]" +
    " WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
  const values = ODataQuery.none().select(CellsetCell.Fields.Value);
  const first = (client: Tm1Client): Promise<unknown> => client.cellsets.stream(mdx, values, 1, (_cellset, cells) => cells.map((cell) => cell.Value)).first();
  const [base, whatIf] = await Promise.all([first(tm1), first(tm1.withSandbox("What If"))]);
  return `base ${String(base)}, what-if ${String(whatIf)}`;
  // @end
}
