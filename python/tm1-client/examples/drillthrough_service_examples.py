# SPDX-License-Identifier: Apache-2.0

"""Examples for DrillthroughService, quoted in the guide."""

from __future__ import annotations

from typing import Any

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cellset, CellsetCell, CubeDrillthrough, ProcessParameterPatch


def cell(tm1: Tm1Client) -> list[Any]:
    # @start region = cell
    # The detail behind one cell: query it, run its first cube drillthrough, and read
    # the cellset that returns. Both cellsets are deleted at the end.
    mdx = (
        "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales] WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])"
    )
    cells = ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.ORDINAL, CellsetCell.VALUE))
    source = tm1.cellsets.execute(mdx)
    try:
        script = next(s for s in tm1.drillthrough.scripts(source.id, 0) if isinstance(s, CubeDrillthrough))
        detail = tm1.drillthrough.execute(source.id, 0, script.name, cells)
        try:
            return list(detail.cells)
        finally:
            tm1.cellsets.delete(detail.id)
    finally:
        tm1.cellsets.delete(source.id)
    # @end


def process(tm1: Tm1Client) -> list[Any]:
    # @start region = process
    # A drillthrough process run directly, with the parameters it declares
    parameters = [
        ProcessParameterPatch.builder().name("pRegion").value("North").build(),
        ProcessParameterPatch.builder().name("pMonth").value("Jan").build(),
    ]
    values = ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.VALUE))
    detail = tm1.drillthrough.execute_process("}Drill_Sales Detail", parameters, values)
    try:
        return list(detail.cells)
    finally:
        tm1.cellsets.delete(detail.id)
    # @end


def rowset(tm1: Tm1Client) -> list[Any]:
    # @start region = rowset
    # Rows from a relational drillthrough, kept as a rowset and read from it.
    # Each row's columns are in its model_extra.
    orders = tm1.drillthrough.execute_relational_process_with_rowset("}Drill_Sales Orders", [])
    try:
        return [(row.model_extra or {}).get("OrderNumber") for row in tm1.drillthrough.rows(orders.id)]
    finally:
        tm1.drillthrough.delete_rowset(orders.id)
    # @end
