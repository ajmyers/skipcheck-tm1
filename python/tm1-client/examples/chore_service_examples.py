# SPDX-License-Identifier: Apache-2.0

"""Examples for ChoreService, quoted in the guide."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from skipcheck_tm1 import ODataPath, Tm1Client
from skipcheck_tm1.model import (
    Chore,
    ChoreCreate,
    ChoreExecutionMode,
    ChorePatch,
    ChoreTaskCreate,
    ChoreTaskParameterPatch,
)


def create(tm1: Tm1Client) -> Chore:
    # @start region = create
    # A daily chore at 02:00 UTC, inactive until it's switched on. A task gives
    # every parameter of its process a value.
    load_actuals = (
        ChoreTaskCreate.builder()
        .bind_process(ODataPath.of("Processes").key("Load Sales"))
        .parameters(
            [
                ChoreTaskParameterPatch.builder().name("pVersion").value("Actual").build(),
                ChoreTaskParameterPatch.builder().name("pYear").value(2026).build(),
            ]
        )
        .build()
    )
    nightly = (
        ChoreCreate.builder()
        .name("Nightly Load")
        .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=UTC))
        .dst_sensitive(False)
        .frequency(timedelta(days=1))
        .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
        .active(False)
        .tasks([load_actuals])
        .build()
    )
    return tm1.chores.create(nightly)
    # @end


def add_task(tm1: Tm1Client) -> int | None:
    # @start region = add-task
    # A task goes at the end; TM1 numbers the steps from 0
    load_forecast = (
        ChoreTaskCreate.builder()
        .bind_process(ODataPath.of("Processes").key("Load Sales"))
        .parameters(
            [
                ChoreTaskParameterPatch.builder().name("pVersion").value("Forecast").build(),
                ChoreTaskParameterPatch.builder().name("pYear").value(2026).build(),
            ]
        )
        .build()
    )
    return tm1.chores.add_task("Nightly Load", load_forecast).step
    # @end


def activate(tm1: Tm1Client) -> None:
    # @start region = activate
    # Scheduling a chore is a patch of Active
    tm1.chores.update("Nightly Load", ChorePatch.builder().active(True).build())
    # @end
