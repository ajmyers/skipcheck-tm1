# SPDX-License-Identifier: Apache-2.0

"""Examples for ProcessDebugService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import ProcessDebugContext, ProcessDebugContextLineBreakpointCreate, ProcessProcedure


def breakpoint(tm1: Tm1Client) -> list[str]:
    # @start region = breakpoint
    # Where the run stops at a breakpoint on line 3 of the prolog. debugging()
    # runs it to the end afterwards.
    debug = tm1.process_debugging

    def stop_at_line_3(context: ProcessDebugContext) -> list[str]:
        assert context.id is not None
        line3 = (
            ProcessDebugContextLineBreakpointCreate.builder()
            .id(1)  # TM1 needs an ID chosen by the caller
            .process_name("Load Sales")
            .procedure(ProcessProcedure.PROLOG)
            .line_number(3)
            .enabled(True)
            .build()
        )
        debug.add_breakpoint(context.id, line3)
        debug.resume(context.id)
        return [f"{frame.procedure}, line {frame.line_number}" for frame in debug.call_stack(context.id)]

    return debug.debugging("Load Sales", {"pVersion": "Forecast"}, stop_at_line_3)
    # @end


def step(tm1: Tm1Client) -> ProcessDebugContext:
    # @start region = step
    # Without debugging(), end the run with finish(): TM1 can't delete it
    debug = tm1.process_debugging
    context = debug.debug("Load Sales")
    assert context.id is not None
    debug.step_over(context.id)
    debug.step_over(context.id)
    return debug.finish(context.id)
    # @end
