"""Examples for ThreadService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Filter, ODataQuery, Tm1Client
from skipcheck_tm1.model import Thread


def busy(tm1: Tm1Client) -> list[str]:
    # @start region = busy
    # What the server is doing now: every thread that isn't idle
    not_idle = ODataQuery.none().select(Thread.ID, Thread.NAME, Thread.FUNCTION, Thread.ELAPSED_TIME).filter(Thread.STATE.ne("Idle"))
    return [f"{thread.name}: {thread.function} for {thread.elapsed_time}" for thread in tm1.threads.list(not_idle)]
    # @end


def cancel_process(tm1: Tm1Client) -> None:
    # @start region = cancel-process
    # A process run through REST shows as its request, not by its name
    running = ODataQuery.none().select(Thread.ID).filter(Filter.contains(Thread.FUNCTION, "Processes('Sales.Load')/tm1.ExecuteWithReturn"))
    for thread in tm1.threads.list(running):
        if thread.id is not None:
            tm1.threads.cancel(thread.id)
    # @end
