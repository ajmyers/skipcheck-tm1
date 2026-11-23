# SPDX-License-Identifier: Apache-2.0

"""ProcessService against a real TM1: a port of the Java client's ProcessServiceTm1Test. The
process's numeric parameter picks how it ends."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import (
    Filter,
    ODataQuery,
    Tm1Client,
    Tm1Error,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
)
from skipcheck_tm1.model import (
    Process,
    ProcessExecuteStatusCode,
    ProcessPatch,
    ProcessProcedure,
)

from .conftest import CONFIGURED, REASON
from .support import (
    process_definition,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

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


def parameters(count: int) -> dict[str, Any]:
    return {"pName": "x", "pCount": count}


@pytest.fixture
def process(tm1: Tm1Client, name: str) -> Iterator[str]:
    tm1.processes.create(process_definition(name, PROLOG))
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
    (error,) = processes.compile(process_definition("unsaved", "nX = ;\n"))
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
    result = processes.execute_with_return(process_definition("unsaved", PROLOG), parameters(2))
    assert result.process_execute_status_code is ProcessExecuteStatusCode.QUIT_CALLED
    processes.execute_with_return(process, parameters(3))
    processes.execute(process, parameters(1))
    processes.execute(process_definition("unsaved", PROLOG), parameters(1))
    logs = processes.error_logs(process)
    assert len(logs) == 1, "the aborted run's"
    assert "ProcessError" in processes.error_log_content(process, logs[0]), "keyed by its quoted timestamp"
