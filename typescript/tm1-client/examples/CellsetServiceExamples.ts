// SPDX-License-Identifier: Apache-2.0

import { Cellset, CellsetCell, CellsetUpdatePatch, ODataQuery, ViewScope, type Tm1Client, type Tm1Stream } from "@skipcheck/tm1-client";

// Examples for CellsetService, quoted in its documentation.

export function stream(tm1: Tm1Client): Tm1Stream<string> {
  // @start region = stream
  const mdx = `
    SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb], [Month].[Month].[Mar]} ON 0,
      {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1
    FROM [Sales]
    WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])`;
  const values = ODataQuery.none().select(CellsetCell.Fields.Ordinal, CellsetCell.Fields.Value);
  return tm1.cellsets.stream(mdx, values, 10_000, (cellset, cells) => {
    // Axis 0 is the columns, axis 1 the rows; ordinals run along a row
    const columns = cellset.Axes?.[0]?.Tuples ?? [];
    const rows = cellset.Axes?.[1]?.Tuples ?? [];
    return cells.map((cell) => {
      const ordinal = cell.Ordinal ?? 0;
      const row = rows[Math.floor(ordinal / columns.length)];
      const column = columns[ordinal % columns.length];
      return `${row?.Members?.[0]?.Name}, ${column?.Members?.[0]?.Name}: ${String(cell.Value)}`;
    });
  });
  // @end
}

export async function write(tm1: Tm1Client): Promise<void> {
  // @start region = write
  // Write by ordinal into the cells of a query, then delete its cellset; the cells must be
  // leaves, as TM1 doesn't spread to a consolidation
  const mdx =
    "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0, {[Region].[Region].[North]} ON 1" +
    " FROM [Sales] WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
  const cellset = await tm1.cellsets.execute(mdx);
  try {
    await tm1.cellsets.updateCells(cellset.ID!, [
      CellsetUpdatePatch.builder().ordinal(0).value(1200).build(),
      CellsetUpdatePatch.builder().ordinal(1).value(1350).build(),
    ]);
  } finally {
    await tm1.cellsets.delete(cellset.ID!);
  }
  // @end
}

export async function view(tm1: Tm1Client): Promise<unknown[]> {
  // @start region = view
  // The values of a saved view, read in the same round trip as its cellset
  const withValues = ODataQuery.none().expand(Cellset.Fields.Cells, (cells) => cells.select(CellsetCell.Fields.Value));
  const cellset = await tm1.cellsets.executeView("Sales", ViewScope.Public, "Revenue by Region", withValues);
  try {
    return (cellset.Cells ?? []).map((cell) => cell.Value);
  } finally {
    await tm1.cellsets.delete(cellset.ID!);
  }
  // @end
}
