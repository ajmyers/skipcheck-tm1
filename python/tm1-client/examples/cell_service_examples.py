# SPDX-License-Identifier: Apache-2.0

"""Examples for CellService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import CubeUpdatePatch
from skipcheck_tm1.services import Tuples


def update(tm1: Tm1Client) -> None:
    # @start region = update
    # The cell: an element for every dimension of the cube, in the cube's order
    cell = {"Version": "Budget", "Region": "North", "Month": "Jan", "Measure": "Revenue"}
    tm1.cells.update("Sales", cell, 1200)
    # @end


def update_cells(tm1: Tm1Client) -> None:
    # @start region = update-cells
    # A budget for North, one value per month, written in one request
    budget = {"Jan": 1200, "Feb": 1350, "Mar": 1100}
    updates = []
    for month, value in budget.items():
        cell = {"Version": "Budget", "Region": "North", "Month": month, "Measure": "Revenue"}
        updates.append(CubeUpdatePatch.builder().bind_tuple(*Tuples.of(cell)).value(value).build())
    tm1.cells.update_cells("Sales", updates)
    # @end
