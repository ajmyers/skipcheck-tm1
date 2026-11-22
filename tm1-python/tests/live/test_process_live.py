"""ProcessService, ProcessDebugService and ChoreService against a real TM1: ports of the Java
client's ProcessServiceTm1Test, ProcessDebugTm1Test and ChoreServiceTm1Test (see conftest.py
for how to point them at a server)."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from typing import Any, Iterator

import pytest

from tm1_client import (
    Filter,
    ODataPath,
    ODataQuery,
    Tm1BadRequestError,
    Tm1Client,
    Tm1Error,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
    Tm1Request,
)
from tm1_client.model import (
    Chore,
    ChoreExecutionMode,
    ChorePatch,
    ChoreTask,
    ChoreTaskParameterPatch,
    ChoreTaskPatch,
    Process,
    ProcessDebugContextLineBreakpoint,
    ProcessDebugContextLineBreakpointPatch,
    ProcessDebugContextStatus,
    ProcessExecuteStatusCode,
    ProcessParameterPatch,
    ProcessPatch,
    ProcessProcedure,
    ProcessVariableType,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

# ProcessServiceTm1Test: a process whose numeric parameter picks how it ends

PROLOG = """If(pCount = 2);
  ProcessQuit;
EndIf;
If(pCount = 3);
  ProcessError;
EndIf;
If(pCount = 4);
  CellPutN(1, 'NoSuchCube', 'x');
EndIf;
"""


def definition(name: str, prolog: str) -> Any:
    return (
        ProcessPatch.builder()
        .name(name)
        .parameters(
            [
                ProcessParameterPatch.builder().name("pName").value("").type(ProcessVariableType.STRING).build(),
                ProcessParameterPatch.builder().name("pCount").value(0).type(ProcessVariableType.NUMERIC).build(),
            ]
        )
        .prolog_procedure(prolog)
        .build()
    )


def parameters(count: int) -> dict[str, Any]:
    return {"pName": "x", "pCount": count}


@pytest.fixture
def process(tm1: Tm1Client, name: str) -> Iterator[str]:
    tm1.processes.create(definition(name, PROLOG))
    yield name
    try:
        tm1.processes.delete(name)
    except Tm1Error:
        pass


def test_creates_reads_updates_and_deletes_a_process(tm1: Tm1Client, process: str) -> None:
    processes = tm1.processes
    read = processes.get(process)
    assert "ProcessQuit" in read.prolog_procedure
    assert [p.name for p in read.parameters] == ["pName", "pCount"]
    listed = processes.list(ODataQuery.none().select(Process.NAME).filter(Filter.starts_with(Process.NAME, process)))
    assert [p.name for p in listed] == [process]

    processes.update(process, ProcessPatch.builder().epilog_procedure("nX = 1;\n").build(), read.etag)
    assert "nX = 1;" in processes.get(process).epilog_procedure
    with pytest.raises(Tm1PreconditionFailedError):
        processes.update(process, ProcessPatch.builder().epilog_procedure("").build(), read.etag)

    processes.delete(process)
    with pytest.raises(Tm1NotFoundError):
        processes.get(process)


def test_compiles_saved_and_unsaved_code(tm1: Tm1Client, process: str) -> None:
    processes = tm1.processes
    assert processes.compile(process) == []
    (error,) = processes.compile(definition("unsaved", "nX = ;\n"))
    assert error.procedure is ProcessProcedure.PROLOG and error.line_number == 1
    processes.update(process, ProcessPatch.builder().prolog_procedure("nX = ;\n").build())
    assert len(processes.compile(process)) == 1, "saved, though it doesn't compile"


def test_runs_and_says_how_it_ended(tm1: Tm1Client, process: str) -> None:
    processes = tm1.processes
    for count in (3, 4):
        result = processes.execute_with_return(process, parameters(count), ODataQuery.none().expand("ErrorLogFile"))
        assert result.process_execute_status_code is ProcessExecuteStatusCode.ABORTED, "ProcessError, then a runtime error"
        assert result.error_log_file.filename.startswith("tm1processerror_")
        assert result.error_log_file.filename.endswith(f"_{process}.log")

    def status(count: int) -> Any:
        return processes.execute_with_return(process, parameters(count)).process_execute_status_code

    assert status(1) is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
    assert status(2) is ProcessExecuteStatusCode.QUIT_CALLED
    assert status(3) is ProcessExecuteStatusCode.ABORTED


def test_runs_unsaved_code_and_the_deprecated_ways(tm1: Tm1Client, process: str) -> None:
    processes = tm1.processes
    result = processes.execute_with_return(definition("unsaved", PROLOG), parameters(2))
    assert result.process_execute_status_code is ProcessExecuteStatusCode.QUIT_CALLED
    processes.execute_with_return(process, parameters(3))
    processes.execute(process, parameters(1))
    processes.execute(definition("unsaved", PROLOG), parameters(1))
    logs = processes.error_logs(process)
    assert len(logs) == 1, "the aborted run's"
    assert "ProcessError" in processes.error_log_content(process, logs[0]), "keyed by its quoted timestamp"


# ProcessDebugTm1Test: a process of three lines, nA = 1; nB = nA + 1; nC = nB + 1


@pytest.fixture
def three_lines(tm1: Tm1Client, name: str) -> Iterator[str]:
    tm1.processes.create(ProcessPatch.builder().name(name).prolog_procedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n").build())
    yield name
    tm1.processes.delete(name)


def line(process: str, id: int, number: int) -> Any:
    return (
        ProcessDebugContextLineBreakpointPatch.builder()
        .id(id)
        .process_name(process)
        .procedure(ProcessProcedure.PROLOG)
        .line_number(number)
        .enabled(True)
        .build()
    )


def test_stops_at_a_breakpoint_reads_the_state_and_runs_to_the_end(tm1: Tm1Client, three_lines: str) -> None:
    debug = tm1.process_debugging
    started = debug.debug(three_lines)
    id = started.id
    assert started.status is ProcessDebugContextStatus.PAUSED, "paused before the first line"
    debug.add_breakpoint(id, line(three_lines, 1, 3))
    (breakpoint,) = debug.breakpoints(id)
    assert isinstance(breakpoint, ProcessDebugContextLineBreakpoint) and breakpoint.line_number == 3

    assert debug.resume(id).status is ProcessDebugContextStatus.PAUSED
    current = debug.current_breakpoint(id)
    assert isinstance(current, ProcessDebugContextLineBreakpoint) and current.line_number == 3
    (frame,) = debug.call_stack(id)
    assert frame.procedure is ProcessProcedure.PROLOG and frame.line_number == 3
    variables = [v.name for v in debug.variables(id, frame.id)]
    assert {"NVALUE", "DATASOURCETYPE"} <= set(variables), "TI's own variables"
    assert "NA" not in variables and "NB" not in variables, "not the process's"
    assert debug.frame_process(id, frame.id).name == three_lines
    assert debug.process(id).name == three_lines
    assert debug.thread(id).object_name == three_lines
    assert debug.list() == [], "lists none"

    assert debug.step_over(id).status is ProcessDebugContextStatus.RUNNING
    done = debug.finish(id)
    assert done.status is ProcessDebugContextStatus.COMPLETE
    assert done.result.process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
    assert debug.get(id).status is ProcessDebugContextStatus.COMPLETE, "still readable, complete"
    with pytest.raises(Tm1BadRequestError):
        # TM1 refuses to delete one
        tm1.session.execute(Tm1Request.delete(ODataPath.of("ProcessDebugContexts").key(id)))


def test_needs_a_chosen_breakpoint_id_and_updates_and_removes_by_it(tm1: Tm1Client, three_lines: str) -> None:
    debug = tm1.process_debugging

    def work(context: Any) -> list[Any]:
        id = context.id
        with pytest.raises(ValueError):
            debug.add_breakpoint(id, ProcessDebugContextLineBreakpointPatch.builder().process_name(three_lines).line_number(2).build())
        debug.add_breakpoint(id, line(three_lines, 1, 2))
        debug.add_breakpoint(id, line(three_lines, 2, 3))
        debug.remove_breakpoint(id, 1)
        debug.update_breakpoint(id, 2, ProcessDebugContextLineBreakpointPatch.builder().enabled(False).build())
        return debug.breakpoints(id)

    (left,) = debug.debugging(three_lines, {}, work)
    assert left.id == 2 and left.enabled is False


def test_finishes_the_run_after_debugging_even_with_breakpoints_left(tm1: Tm1Client, three_lines: str) -> None:
    debug = tm1.process_debugging
    ids = []

    def work(context: Any) -> int:
        ids.append(context.id)
        debug.add_breakpoint(context.id, line(three_lines, 1, 2))
        debug.step_in(context.id)
        return 1

    assert debug.debugging(three_lines, {}, work) == 1
    assert debug.get(ids[0]).status is ProcessDebugContextStatus.COMPLETE


# ChoreServiceTm1Test: a process that creates the dimension its pName parameter names


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
    tm1.processes.create(
        definition(c.process, "If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n")
    )
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
    service.update_task(
        chore, 1, ChoreTaskPatch.builder().parameters([parameter("pName", "third_" + chores.id), parameter("pCount", 2)]).build()
    )
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
            ChoreTaskPatch.builder()
            .bind_process(ODataPath.of("Processes").key(chores.process))
            .parameters([parameter("pName", "x")])
            .build(),
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
