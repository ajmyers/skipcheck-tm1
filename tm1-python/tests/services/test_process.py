"""ProcessService, ProcessDebugService, ChoreService and SqlDataSourceService, in both flavors,
against a fake TM1: each method sends what the Java client's does."""

from __future__ import annotations

import json
from datetime import date, datetime, time, timedelta, timezone
from typing import Any

import httpx
import pytest

from tm1_client import ODataPath, ODataQuery
from tm1_client.model import (
    ChoreExecutionMode,
    ChorePatch,
    ChoreTaskParameterPatch,
    ChoreTaskPatch,
    ProcessDebugContextLineBreakpointPatch,
    ProcessDebugContextStatus,
    ProcessErrorLog,
    ProcessExecuteResult,
    ProcessPatch,
    ProcessProcedure,
)

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> Any:
    return json.loads(request.content) if request.content else None


# ProcessService


def test_lists_gets_creates_updates_and_deletes_processes(api: Api, fake: FakeTm1) -> None:
    processes = api.client.processes
    fake.respond = lambda r: {"value": [{"Name": "Load"}]}
    assert [p.name for p in api.run(processes.list(select="Name"))] == ["Load"]
    assert target(fake.last) == "GET Processes?$select=Name"
    fake.respond = lambda r: {"Name": "Load"}
    assert api.run(processes.get("Load")).name == "Load"
    assert target(fake.last) == "GET Processes('Load')"
    patch = ProcessPatch.builder().name("Load").prolog_procedure("x;").build()
    api.run(processes.create(patch))
    assert (target(fake.last), body(fake.last)) == ("POST Processes", {"Name": "Load", "PrologProcedure": "x;"})
    fake.respond = lambda r: None
    api.run(processes.update("Load", ProcessPatch.builder().epilog_procedure("y;").build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Processes('Load')", {"EpilogProcedure": "y;"})
    assert "if-match" not in fake.last.headers
    api.run(processes.update("Load", ProcessPatch.builder().epilog_procedure("y;").build(), 'W/"2"'))
    assert fake.last.headers["if-match"] == 'W/"2"'
    api.run(processes.delete("Load"))
    assert target(fake.last) == "DELETE Processes('Load')"


def test_compiles_saved_and_unsaved_processes(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Procedure": "Prolog", "LineNumber": 3, "Message": "bad"}]}
    errors = api.run(api.client.processes.compile("Load"))
    assert (target(fake.last), body(fake.last)) == ("POST Processes('Load')/tm1.Compile", {})
    assert errors[0].line_number == 3
    definition = ProcessPatch.builder().name("New").prolog_procedure("x;").build()
    api.run(api.client.processes.compile(definition))
    assert (target(fake.last), body(fake.last)) == ("POST CompileProcess", {"Process": {"Name": "New", "PrologProcedure": "x;"}})
    api.run(api.client.processes.compile(definition=definition))
    assert target(fake.last) == "POST CompileProcess"
    with pytest.raises(TypeError):
        api.run(api.client.processes.compile())


def test_runs_with_return_saved_and_unsaved(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"ProcessExecuteStatusCode": "Aborted", "ErrorLogFile": {"Filename": "tm1processerror_x.log"}}
    parameters = {"pVersion": "Forecast", "pYear": 2026}
    result = api.run(api.client.processes.execute_with_return("Load", parameters, ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE)))
    assert target(fake.last) == "POST Processes('Load')/tm1.ExecuteWithReturn?$expand=ErrorLogFile"
    assert body(fake.last) == {"Parameters": [{"Name": "pVersion", "Value": "Forecast"}, {"Name": "pYear", "Value": 2026}]}
    assert result.error_log_file.filename == "tm1processerror_x.log"
    api.run(api.client.processes.execute_with_return("Load"))
    assert body(fake.last) == {}

    cleanup = ProcessPatch.builder().name("Clear").prolog_procedure("CubeClearData('s');").build()
    api.run(api.client.processes.execute_with_return(cleanup))
    assert (target(fake.last), body(fake.last)) == (
        "POST ExecuteProcessWithReturn",
        {"Process": {"Name": "Clear", "PrologProcedure": "CubeClearData('s');"}},
    )
    api.run(api.client.processes.execute_with_return(cleanup, parameters={"p": 1}))
    assert body(fake.last)["Parameters"] == [{"Name": "p", "Value": 1}]
    # Java's positional shape, for a definition too
    api.run(api.client.processes.execute_with_return(cleanup, {"p": 2}, ODataQuery.none().expand("ErrorLogFile")))
    assert target(fake.last) == "POST ExecuteProcessWithReturn?$expand=ErrorLogFile"
    assert body(fake.last)["Parameters"] == [{"Name": "p", "Value": 2}]
    # And by keyword
    api.run(api.client.processes.execute_with_return(name="Load", parameters={"p": 3}, expand="ErrorLogFile"))
    assert target(fake.last) == "POST Processes('Load')/tm1.ExecuteWithReturn?$expand=ErrorLogFile"


def test_runs_without_return(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.processes.execute("Load", {"p": "x"}))
    assert (target(fake.last), body(fake.last)) == ("POST Processes('Load')/tm1.Execute", {"Parameters": [{"Name": "p", "Value": "x"}]})
    api.run(api.client.processes.execute(ProcessPatch.builder().name("Tmp").build()))
    assert (target(fake.last), body(fake.last)) == ("POST ExecuteProcess", {"Process": {"Name": "Tmp"}})


def test_reads_error_logs_by_their_timestamp(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Timestamp": "2026-11-21T10:15:30.5Z"}]}
    logs = api.run(api.client.processes.error_logs("Load"))
    assert target(fake.last) == "GET Processes('Load')/ErrorLogs"
    fake.respond = lambda r: "the log"
    assert api.run(api.client.processes.error_log_content("Load", logs[0])) == "the log"
    # Keyed by the timestamp as a quoted string, as Java's OffsetDateTime writes it
    assert target(fake.last) == "GET Processes('Load')/ErrorLogs('2026-11-21T10%3A15%3A30.500Z')/Content"
    api.run(api.client.processes.error_log_content("Load", ProcessErrorLog(timestamp=datetime(2026, 1, 2, 3, 4, tzinfo=timezone.utc))))
    assert target(fake.last) == "GET Processes('Load')/ErrorLogs('2026-01-02T03%3A04Z')/Content"


# ProcessDebugService


def test_debugs_and_steps(api: Api, fake: FakeTm1) -> None:
    debug = api.client.process_debugging
    fake.respond = lambda r: {"ID": "c1", "Status": "Paused"}
    context = api.run(debug.debug("Load", {"pVersion": "Forecast"}, expand="Breakpoints"))
    assert target(fake.last) == "POST Processes('Load')/tm1.Debug?$expand=Breakpoints"
    assert body(fake.last) == {"Parameters": [{"Name": "pVersion", "Value": "Forecast"}]}
    assert context.id == "c1"
    api.run(debug.debug("Load"))
    assert body(fake.last) == {}
    for method, action in (("resume", "Continue"), ("step_in", "StepIn"), ("step_out", "StepOut"), ("step_over", "StepOver")):
        api.run(getattr(debug, method)("c1"))
        assert (target(fake.last), body(fake.last)) == (f"POST ProcessDebugContexts('c1')/tm1.{action}", {})
    api.run(debug.get("c1"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')"
    fake.respond = lambda r: {"value": []}
    assert api.run(debug.list()) == []
    assert target(fake.last) == "GET ProcessDebugContexts"


def test_breakpoints_need_an_id(api: Api, fake: FakeTm1) -> None:
    debug = api.client.process_debugging
    line3 = ProcessDebugContextLineBreakpointPatch.builder().id(1).process_name("Load").procedure(ProcessProcedure.PROLOG).line_number(3).enabled(True).build()
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint", "ID": 1}
    api.run(debug.add_breakpoint("c1", line3))
    assert target(fake.last) == "POST ProcessDebugContexts('c1')/Breakpoints"
    assert body(fake.last) == {
        "@odata.type": "#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint",
        "ID": 1,
        "ProcessName": "Load",
        "Procedure": "Prolog",
        "LineNumber": 3,
        "Enabled": True,
    }
    sent = len(fake.requests)
    with pytest.raises(ValueError, match="ID of 0 or more"):
        api.run(debug.add_breakpoint("c1", ProcessDebugContextLineBreakpointPatch.builder().line_number(3).build()))
    with pytest.raises(ValueError):
        api.run(debug.add_breakpoint("c1", ProcessDebugContextLineBreakpointPatch.builder().id(-1).build()))
    assert len(fake.requests) == sent

    fake.respond = lambda r: None
    api.run(debug.update_breakpoint("c1", 1, ProcessDebugContextLineBreakpointPatch.builder().enabled(False).build()))
    assert (target(fake.last), body(fake.last)) == (
        "PATCH ProcessDebugContexts('c1')/Breakpoints(1)",
        {"@odata.type": "#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint", "Enabled": False},
    )
    api.run(debug.remove_breakpoint("c1", 1))
    assert target(fake.last) == "DELETE ProcessDebugContexts('c1')/Breakpoints(1)"
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint", "ID": 1}]}
    (only,) = api.run(debug.breakpoints("c1"))
    assert type(only).__name__ == "ProcessDebugContextLineBreakpoint"


def test_reads_where_the_run_is(api: Api, fake: FakeTm1) -> None:
    debug = api.client.process_debugging
    fake.respond = lambda r: None  # not paused at a breakpoint: 204
    assert api.run(debug.current_breakpoint("c1")) is None
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/CurrentBreakpoint"
    fake.respond = lambda r: {"value": [{"ID": "f0", "LineNumber": 3}]}
    frames = api.run(debug.call_stack("c1", expand="Variables"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/CallStack?$expand=Variables"
    assert frames[0].line_number == 3
    fake.respond = lambda r: {"value": [{"Name": "NVALUE"}]}
    api.run(debug.variables("c1", "f0"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/CallStack('f0')/Variables"
    fake.respond = lambda r: {"Name": "Load"}
    api.run(debug.frame_process("c1", "f0"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/CallStack('f0')/Process"
    api.run(debug.process("c1"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/Process"
    fake.respond = lambda r: {"ID": 7}
    api.run(debug.thread("c1"))
    assert target(fake.last) == "GET ProcessDebugContexts('c1')/Thread"


def _finishing(fake: FakeTm1, pauses: int) -> None:
    """A fake debug run with one breakpoint (and one TM1 stored as -1), paused ``pauses`` times."""
    left = [pauses]

    def respond(request: httpx.Request) -> Any:
        t = target(request)
        if t.endswith("/Breakpoints"):
            return {"value": [{"ID": 1}, {"ID": -1}]}
        if t.endswith("tm1.Continue"):
            left[0] -= 1
            return {"ID": "c1", "Status": "Paused" if left[0] > 0 else "Complete"}
        if t.endswith("tm1.Debug"):
            return {"ID": "c1", "Status": "Paused"}
        return None

    fake.respond = respond


def test_finishes_a_run_by_removing_breakpoints_and_resuming(api: Api, fake: FakeTm1) -> None:
    _finishing(fake, pauses=3)
    context = api.run(api.client.process_debugging.finish("c1"))
    assert context.status is ProcessDebugContextStatus.COMPLETE
    assert [target(r) for r in fake.requests] == [
        "GET ProcessDebugContexts('c1')/Breakpoints",
        # The -1 one can't be addressed, so it's left
        "DELETE ProcessDebugContexts('c1')/Breakpoints(1)",
        "POST ProcessDebugContexts('c1')/tm1.Continue",
        "POST ProcessDebugContexts('c1')/tm1.Continue",
        "POST ProcessDebugContexts('c1')/tm1.Continue",
    ]


def test_debugging_finishes_however_the_work_ends(api: Api, fake: FakeTm1) -> None:
    _finishing(fake, pauses=1)
    seen = []

    if api.flavor == "async":

        async def work(context: Any) -> str:
            seen.append(context.id)
            raise RuntimeError("the work failed")

    else:

        def work(context: Any) -> str:
            seen.append(context.id)
            raise RuntimeError("the work failed")

    with pytest.raises(RuntimeError, match="the work failed"):
        api.run(api.client.process_debugging.debugging("Load", {}, work))
    assert seen == ["c1"]
    assert target(fake.last) == "POST ProcessDebugContexts('c1')/tm1.Continue"

    _finishing(fake, pauses=1)
    if api.flavor == "async":

        async def ok(context: Any) -> str:
            return "done " + context.id

    else:

        def ok(context: Any) -> str:
            return "done " + context.id

    assert api.run(api.client.process_debugging.debugging("Load", {"p": 1}, ok)) == "done c1"
    # As the Java client's ProcessDebugServiceTest.debuggingFinishesTheRunAfterTheWork: debugged
    # with the parameters, then finished
    debug = next(r for r in reversed(fake.requests) if r.url.path.endswith("tm1.Debug"))
    assert json.loads(debug.content) == {"Parameters": [{"Name": "p", "Value": 1}]}
    assert target(fake.last) == "POST ProcessDebugContexts('c1')/tm1.Continue"


# ChoreService


def test_chores(api: Api, fake: FakeTm1) -> None:
    chores = api.client.chores
    fake.respond = lambda r: {"value": [{"Name": "Nightly"}]}
    api.run(chores.list())
    assert target(fake.last) == "GET Chores"
    fake.respond = lambda r: {"Name": "Nightly"}
    api.run(chores.get("Nightly", expand="Tasks($expand=Process)"))
    assert target(fake.last) == "GET Chores('Nightly')?$expand=Tasks($expand%3DProcess)"
    task = (
        ChoreTaskPatch.builder()
        .bind_process(ODataPath.of("Processes").key("Load Sales"))
        .parameters([ChoreTaskParameterPatch.builder().name("pVersion").value("Actual").build()])
        .build()
    )
    api.run(
        chores.create(
            ChorePatch.builder()
            .name("Nightly")
            .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=timezone.utc))
            .dst_sensitive(False)
            .frequency(timedelta(days=1))
            .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
            .active(False)
            .tasks([task])
            .build()
        )
    )
    assert target(fake.last) == "POST Chores"
    assert body(fake.last) == {
        "Name": "Nightly",
        "StartTime": "2026-01-01T02:00:00Z",
        "DSTSensitive": False,
        "Frequency": "PT24H",
        "ExecutionMode": "SingleCommit",
        "Active": False,
        "Tasks": [{"Process@odata.bind": "Processes('Load%20Sales')", "Parameters": [{"Name": "pVersion", "Value": "Actual"}]}],
    }
    fake.respond = lambda r: None
    api.run(chores.update("Nightly", ChorePatch.builder().active(True).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Chores('Nightly')", {"Active": True})
    api.run(chores.update("Nightly", ChorePatch.builder().active(True).build(), etag="e"))
    assert fake.last.headers["if-match"] == "e"
    api.run(chores.delete("Nightly"))
    assert target(fake.last) == "DELETE Chores('Nightly')"


def test_chore_tasks(api: Api, fake: FakeTm1) -> None:
    chores = api.client.chores
    fake.respond = lambda r: {"value": [{"Step": 0}]}
    api.run(chores.tasks("Nightly"))
    assert target(fake.last) == "GET Chores('Nightly')/Tasks"
    fake.respond = lambda r: {"Step": 1}
    assert api.run(chores.add_task("Nightly", ChoreTaskPatch.builder().bind_process(ODataPath.of("Processes").key("p")).build())).step == 1
    assert (target(fake.last), body(fake.last)) == ("POST Chores('Nightly')/Tasks", {"Process@odata.bind": "Processes('p')"})
    fake.respond = lambda r: None
    api.run(chores.update_task("Nightly", 1, ChoreTaskPatch.builder().parameters([]).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Chores('Nightly')/Tasks(1)", {"Parameters": []})
    api.run(chores.delete_task("Nightly", 1))
    assert target(fake.last) == "DELETE Chores('Nightly')/Tasks(1)"
    fake.respond = lambda r: {"Name": "p"}
    api.run(chores.task_process("Nightly", 0, select="Name"))
    assert target(fake.last) == "GET Chores('Nightly')/Tasks(0)/Process?$select=Name"
    api.run(chores.task_chore("Nightly", 0))
    assert target(fake.last) == "GET Chores('Nightly')/Tasks(0)/Chore"


def test_chore_operations(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    for method, action in (("execute", "Execute"), ("activate", "Activate"), ("deactivate", "Deactivate")):
        api.run(getattr(api.client.chores, method)("Nightly"))
        assert (target(fake.last), body(fake.last)) == (f"POST Chores('Nightly')/tm1.{action}", {})
    api.run(api.client.chores.set_server_local_start_time("Nightly", date(2026, 3, 1), time(2, 0)))
    # As Java's LocalDate and LocalTime write them
    assert (target(fake.last), body(fake.last)) == (
        "POST Chores('Nightly')/tm1.SetServerLocalStartTime",
        {"StartDate": "2026-03-01", "StartTime": "02:00"},
    )
    api.run(api.client.chores.set_server_local_start_time("Nightly", date(2026, 3, 1), time(2, 0, 30)))
    assert body(fake.last)["StartTime"] == "02:00:30"


# SqlDataSourceService


def test_sql_data_sources(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Name": "dsn"}]}
    assert [s.name for s in api.run(api.client.sql_data_sources.list())] == ["dsn"]
    assert target(fake.last) == "GET SQLDataSources"
    fake.respond = lambda r: {"Name": "dsn"}
    api.run(api.client.sql_data_sources.get("dsn", select="Name"))
    assert target(fake.last) == "GET SQLDataSources('dsn')?$select=Name"
