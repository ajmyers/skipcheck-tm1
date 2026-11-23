# SPDX-License-Identifier: Apache-2.0

"""Examples for ChangeSetService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client


def within(tm1: Tm1Client) -> str | None:
    # @start region = within
    # Record some writes in a change set, and keep its ID to undo them later
    cell = {"Version": "Forecast", "Region": "North", "Month": "Jan", "Measure": "Revenue"}
    with tm1.change_sets.recording() as session:
        Tm1Client.of(session).cells.update("Sales", cell, 1500)
    return session.change_set
    # @end


def undo(tm1: Tm1Client, change_set: str) -> None:
    # @start region = undo
    # Revert every write recorded in the change set; writes outside it stay
    tm1.change_sets.undo(change_set)
    # @end
