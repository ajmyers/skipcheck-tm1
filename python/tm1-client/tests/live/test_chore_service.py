# SPDX-License-Identifier: Apache-2.0

"""ChoreService against a real TM1: a port of the Java client's ChoreServiceTm1Test. Its process
creates the dimension its pName parameter names."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import date, datetime, time, timedelta, timezone
from typing import Any

import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1Client,
    Tm1Error,
    Tm1PreconditionFailedError,
)
from skipcheck_tm1.model import (
    Chore,
    ChoreExecutionMode,
    ChorePatch,
    ChoreTask,
    ChoreTaskParameterPatch,
    ChoreTaskPatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    process_definition,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


class Chores:
    def __init__(self, tm1: Tm1Client, name: str):
        self.tm1 = tm1
        self.id = name.removeprefix("tm1py_test_")
        self.process = name
        self.chore = name + "_chore"

    def task(self, marker: str) -> Any:
        return (
            ChoreTaskPatch.builder()
            .bind_process(ODataPath.of("Processes").key(self.process))
            .parameters([parameter("pName", marker), parameter("pCount", 1)])
            .build()
        )

    def active(self) -> bool:
        return self.tm1.chores.get(self.chore).active is True


def parameter(name: str, value: Any) -> Any:
    return ChoreTaskParameterPatch.builder().name(name).value(value).build()


@pytest.fixture
def chores(tm1: Tm1Client, name: str) -> Iterator[Chores]:
    c = Chores(tm1, name)
    tm1.processes.create(process_definition(c.process, "If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n"))
    tm1.chores.create(
        ChorePatch.builder()
        .name(c.chore)
        .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=timezone.utc))
        .dst_sensitive(False)
        .active(False)
        .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
        .frequency(timedelta(days=1))
        .tasks([c.task("marker_" + c.id)])
        .build()
    )
    yield c
    for cleanup in (lambda: tm1.chores.delete(c.chore), lambda: tm1.processes.delete(c.process)):
        try:
            cleanup()
        except Tm1Error:
            pass
    for d in ("marker_" + c.id, "second_" + c.id):
        try:
            tm1.api.dimensions[d].delete()
        except Tm1Error:
            pass


def test_creates_reads_updates_and_deletes_a_chore_and_its_tasks(tm1: Tm1Client, chores: Chores) -> None:
    service, chore = tm1.chores, chores.chore
    read = service.get(chore, ODataQuery.none().expand(Chore.TASKS, lambda t: t.expand(ChoreTask.PROCESS)))
    (task,) = read.tasks
    assert task.step == 0, "TM1 numbers the steps from 0"
    assert task.process.name == chores.process
    assert service.task_process(chore, 0).name == chores.process
    assert service.task_chore(chore, 0).name == chore

    assert service.add_task(chore, chores.task("second_" + chores.id)).step == 1
    assert [t.step for t in service.tasks(chore)] == [0, 1]
    service.update_task(chore, 1, ChoreTaskPatch.builder().parameters([parameter("pName", "third_" + chores.id), parameter("pCount", 2)]).build())
    assert any(p.value == "third_" + chores.id for p in service.tasks(chore)[1].parameters)
    service.delete_task(chore, 0)
    assert [t.step for t in service.tasks(chore)] == [0], "renumbered from 0"

    etag = service.get(chore).etag
    assert etag != read.etag, "task changes change the chore's ETag"
    service.update(chore, ChorePatch.builder().frequency(timedelta(hours=6)).build(), etag)
    assert service.get(chore).frequency == timedelta(hours=6)
    with pytest.raises(Tm1PreconditionFailedError):
        service.update(chore, ChorePatch.builder().frequency(timedelta(hours=1)).build(), etag)


def test_needs_every_process_parameter_in_a_task(tm1: Tm1Client, chores: Chores) -> None:
    with pytest.raises(Tm1Error):
        tm1.chores.add_task(
            chores.chore,
            ChoreTaskPatch.builder().bind_process(ODataPath.of("Processes").key(chores.process)).parameters([parameter("pName", "x")]).build(),
        )


def test_activates_by_patch_or_the_deprecated_actions(tm1: Tm1Client, chores: Chores) -> None:
    service, chore = tm1.chores, chores.chore
    service.update(chore, ChorePatch.builder().active(True).build())
    assert chores.active(), "by PATCH, as IBM recommends"
    service.deactivate(chore)
    assert not chores.active()
    service.activate(chore)
    assert chores.active()
    with pytest.raises(Tm1Error, match="active chore"):
        service.set_server_local_start_time(chore, date(2027, 3, 4), time(5, 30))
    service.deactivate(chore)
    service.set_server_local_start_time(chore, date(2027, 3, 4), time(5, 30))
    assert service.get(chore).start_time.date() == date(2027, 3, 4)


def test_runs_the_chore_now(tm1: Tm1Client, chores: Chores) -> None:
    tm1.chores.execute(chores.chore)
    assert tm1.api.dimensions["marker_" + chores.id].get().name == "marker_" + chores.id
