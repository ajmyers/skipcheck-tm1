# SPDX-License-Identifier: Apache-2.0

"""Examples for CellsetService, quoted in the guide."""

from __future__ import annotations

from typing import Any

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cellset, CellsetCell, CellsetUpdatePatch
from skipcheck_tm1.services import ViewScope


def stream(tm1: Tm1Client) -> list[str]:
    # @start region = stream
    mdx = """
        SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb], [Month].[Month].[Mar]} ON 0,
          {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1
        FROM [Sales]
        WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])"""

    def lines(cellset, cells):
        # Axis 0 is the columns, axis 1 the rows; ordinals run along a row
        columns, rows = cellset.axes[0].tuples, cellset.axes[1].tuples
        out = []
        # The cells arrive a page at a time as the loop asks for them
        for cell in cells:
            row, column = rows[cell.ordinal // len(columns)], columns[cell.ordinal % len(columns)]
            out.append(f"{row.members[0].name}, {column.members[0].name}: {cell.value}")
        return out

    # The cellset is deleted when the function returns, and what it returned comes back
    return tm1.cellsets.stream(mdx, ODataQuery.none().select(CellsetCell.ORDINAL, CellsetCell.VALUE), 10_000, lines)
    # @end


def write(tm1: Tm1Client) -> None:
    # @start region = write
    # Write by ordinal into the cells of a query, then delete its cellset; the cells
    # must be leaves, as TM1 doesn't spread to a consolidation
    mdx = (
        "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0, {[Region].[Region].[North]} ON 1"
        " FROM [Sales] WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])"
    )
    cellset = tm1.cellsets.execute(mdx)
    try:
        january = CellsetUpdatePatch.builder().ordinal(0).value(1200).build()
        february = CellsetUpdatePatch.builder().ordinal(1).value(1350).build()
        tm1.cellsets.update_cells(cellset.id, [january, february])
    finally:
        tm1.cellsets.delete(cellset.id)
    # @end


def view(tm1: Tm1Client) -> list[Any]:
    # @start region = view
    # The values of a saved view, read in the same round trip as its cellset
    values = ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.VALUE))
    cellset = tm1.cellsets.execute_view("Sales", ViewScope.PUBLIC, "Revenue by Region", values)
    try:
        return [cell.value for cell in cellset.cells]
    finally:
        tm1.cellsets.delete(cellset.id)
    # @end
