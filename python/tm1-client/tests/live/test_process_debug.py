# SPDX-License-Identifier: Apache-2.0

"""ProcessDebugService against a real TM1: a port of the Java client's ProcessDebugTm1Test. The
process has three lines: nA = 1; nB = nA + 1; nC = nB + 1."""

from __future__ import annotations

import contextlib
import time as clock
from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import (
    ODataPath,
    Tm1BadRequestError,
    Tm1Client,
    Tm1Request,
)
from skipcheck_tm1.model import (
    ProcessCreate,
    ProcessDebugContextLineBreakpoint,
    ProcessDebugContextLineBreakpointCreate,
    ProcessDebugContextLineBreakpointPatch,
    ProcessDebugContextStatus,
    ProcessExecuteStatusCode,
    ProcessProcedure,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def three_lines(tm1: Tm1Client, name: str) -> Iterator[str]:
    tm1.processes.create(ProcessCreate.builder().name(name).prolog_procedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n").build())
    yield name
    tm1.processes.delete(name)


def line(process: str, id: int, number: int) -> Any:
    return (
        ProcessDebugContextLineBreakpointCreate.builder()
        .id(id)
        .process_name(process)
        .procedure(ProcessProcedure.PROLOG)
        .line_number(number)
        .enabled(True)
        .build()
    )


def settled(debug: Any, id: str) -> Any:
    """The context once the run has stopped moving. TM1 answers a start, a resume or a step with
    the status of that moment, RUNNING or already PAUSED."""
    deadline = clock.monotonic() + 30
    while (context := debug.get(id)).status is ProcessDebugContextStatus.RUNNING and clock.monotonic() < deadline:
        clock.sleep(0.1)
    return context


def test_stops_at_a_breakpoint_reads_the_state_and_runs_to_the_end(tm1: Tm1Client, three_lines: str) -> None:
    debug = tm1.process_debugging
    id = debug.debug(three_lines).id
    try:
        _stops_and_runs_to_the_end(tm1, three_lines, id)
    finally:
        # A run left paused holds the process, and its delete would wait for it
        with contextlib.suppress(Exception):
            debug.finish(id)


def _stops_and_runs_to_the_end(tm1: Tm1Client, three_lines: str, id: str) -> None:
    debug = tm1.process_debugging
    assert settled(debug, id).status is ProcessDebugContextStatus.PAUSED, "paused before the first line"
    debug.add_breakpoint(id, line(three_lines, 1, 3))
    (breakpoint,) = debug.breakpoints(id)
    assert isinstance(breakpoint, ProcessDebugContextLineBreakpoint) and breakpoint.line_number == 3

    assert debug.resume(id).status in (ProcessDebugContextStatus.RUNNING, ProcessDebugContextStatus.PAUSED)
    assert settled(debug, id).status is ProcessDebugContextStatus.PAUSED, "at the breakpoint"
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

    # Past the last line: still running, paused with no line to be on, or already complete
    assert debug.step_over(id).status in (ProcessDebugContextStatus.RUNNING, ProcessDebugContextStatus.PAUSED, ProcessDebugContextStatus.COMPLETE)
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
            debug.add_breakpoint(id, ProcessDebugContextLineBreakpointCreate.builder().process_name(three_lines).line_number(2).build())
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
