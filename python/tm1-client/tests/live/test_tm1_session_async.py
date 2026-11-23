# SPDX-License-Identifier: Apache-2.0

"""The async view against TM1: results through the monitor, and cancelling a run. A port of the
Java client's Tm1SessionAsyncTm1Test."""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from typing import Any

import pytest

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import ProcessExecuteStatusCode, ProcessPatch

from .conftest import CONFIGURED, REASON, async_client

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_runs_a_process_and_reads_a_collection_through_the_monitor(tm1: Tm1Client) -> None:
    process = "tm1-client-" + uuid.uuid4().hex[:8]
    polled = tm1.with_async(0.2)
    tm1.processes.create(ProcessPatch.builder().name(process).prolog_procedure("x = 1;\n").build())
    try:
        assert polled.processes.execute_with_return(process).process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
        assert polled.cubes.list(select="Name")
    finally:
        tm1.processes.delete(process)


def test_stops_the_work_on_tm1_when_cancelled() -> None:
    process = "tm1-client-" + uuid.uuid4().hex[:8]
    loop = "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"

    async def wait_for(tm1: Any, running: bool) -> bool:
        """Waits up to 30 s for a thread running the process to be there, or gone."""
        for _ in range(150):
            threads = await tm1.threads.list()
            if any(t.function and f"Processes('{process}')" in t.function for t in threads) == running:
                return True
            await asyncio.sleep(0.2)
        return False

    async def go() -> None:
        async with async_client() as tm1:
            await tm1.processes.create(ProcessPatch.builder().name(process).prolog_procedure(loop).build())
            try:
                # Cancelling the task is this flavor's way to cancel a run
                run = asyncio.ensure_future(tm1.with_async(0.2).processes.execute_with_return(process))
                assert await wait_for(tm1, True), "the process runs"
                run.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await run
                assert await wait_for(tm1, False), "cancelling deleted the monitor, which stopped it"
            finally:
                await wait_for(tm1, False)
                await tm1.processes.delete(process)

    asyncio.run(go())
