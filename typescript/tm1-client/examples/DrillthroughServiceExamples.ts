// SPDX-License-Identifier: Apache-2.0

import { Cellset, CellsetCell, Drillthrough, ODataQuery, ProcessParameterPatch, typeOf, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for DrillthroughService, quoted in its documentation.

export async function cell(tm1: Tm1Client): Promise<CellsetCell[]> {
  // @start region = cell
  // The detail behind one cell: query it, run its first cube drillthrough, and read the
  // cellset that returns. Both cellsets are deleted at the end.
  const mdx =
    "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]" +
    " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
  const cells = ODataQuery.none().expand(Cellset.Fields.Cells, (c) => c.select(CellsetCell.Fields.Ordinal, CellsetCell.Fields.Value));
  const source = await tm1.cellsets.execute(mdx);
  try {
    const scripts = await tm1.drillthrough.scripts(source.ID!, 0).toArray();
    const script = scripts.find((s) => typeOf(Drillthrough, s) === "CubeDrillthrough");
    if (!script) return [];
    const detail = await tm1.drillthrough.execute(source.ID!, 0, script.Name!, cells);
    try {
      return detail.Cells ?? [];
    } finally {
      await tm1.cellsets.delete(detail.ID!);
    }
  } finally {
    await tm1.cellsets.delete(source.ID!);
  }
  // @end
}

export async function process(tm1: Tm1Client): Promise<CellsetCell[]> {
  // @start region = process
  // A drillthrough process run directly, with the parameters it declares
  const parameters = [
    ProcessParameterPatch.builder().name("pRegion").value("North").build(),
    ProcessParameterPatch.builder().name("pMonth").value("Jan").build(),
  ];
  const values = ODataQuery.none().expand(Cellset.Fields.Cells, (c) => c.select(CellsetCell.Fields.Value));
  const detail = await tm1.drillthrough.executeProcess("}Drill_Sales Detail", parameters, values);
  try {
    return detail.Cells ?? [];
  } finally {
    await tm1.cellsets.delete(detail.ID!);
  }
  // @end
}

export async function rowset(tm1: Tm1Client): Promise<string[]> {
  // @start region = rowset
  // Rows from a relational drillthrough, kept as a rowset and streamed from it. Each row's
  // columns are properties of the row, by column name.
  const rowset = await tm1.drillthrough.executeRelationalProcessWithRowset("}Drill_Sales Orders", []);
  try {
    const orders: string[] = [];
    for await (const row of tm1.drillthrough.rows(rowset.ID!)) orders.push(String(row["OrderNumber"]));
    return orders;
  } finally {
    await tm1.drillthrough.deleteRowset(rowset.ID!);
  }
  // @end
}
