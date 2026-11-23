"""Examples for SandboxService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import CellsetCell, SandboxPatch


def publish(tm1: Tm1Client) -> None:
    # @start region = publish
    # Write to a new sandbox, leaving base data alone until the changes are published
    cell = {"Version": "Budget", "Region": "North", "Month": "Jan", "Measure": "Revenue"}
    tm1.sandboxes.create(SandboxPatch.builder().name("What If").build())
    tm1.with_sandbox("What If").cells.update("Sales", cell, 1500)
    tm1.sandboxes.publish("What If")
    # @end


def compare(tm1: Tm1Client) -> str:
    # @start region = compare
    # The same cell read in base data and in a sandbox: the same query, through a
    # client for each
    mdx = (
        "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales] WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])"
    )
    values = ODataQuery.none().select(CellsetCell.VALUE)

    def first(cellset, cells):
        return next(cells).value

    base = tm1.cellsets.stream(mdx, values, 1, first)
    what_if = tm1.with_sandbox("What If").cellsets.stream(mdx, values, 1, first)
    return f"base {base}, what-if {what_if}"
    # @end
