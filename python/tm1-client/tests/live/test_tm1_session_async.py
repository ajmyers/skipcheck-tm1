# SPDX-License-Identifier: Apache-2.0

"""The async view against TM1: results through the monitor, and cancelling a run. A port of the
Java client's Tm1SessionAsyncTm1Test."""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1Request
from skipcheck_tm1.model import ProcessCreate, ProcessExecuteStatusCode

from .conftest import CONFIGURED, REASON, async_client
from .support import TM1_12

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_runs_a_process_and_reads_a_collection_through_the_monitor(tm1: Tm1Client) -> None:
    process = "tm1-client-" + uuid.uuid4().hex[:8]
    polled = tm1.with_async(0.2)
    tm1.processes.create(ProcessCreate.builder().name(process).prolog_procedure("x = 1;\n").build())
    try:
        assert polled.processes.execute_with_return(process).process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
        assert polled.cubes.list(select="Name")
    finally:
        tm1.processes.delete(process)


def test_waits_before_going_async_when_requested(tm1: Tm1Client) -> None:
    fast = "tm1-client-" + uuid.uuid4().hex[:8]
    slow = "tm1-client-" + uuid.uuid4().hex[:8]
    try:
        tm1.processes.create(ProcessCreate.builder().name(fast).prolog_procedure("x = 1;\n").build())
        tm1.processes.create(ProcessCreate.builder().name(slow).prolog_procedure("Sleep(2000);\n").build())
        long_wait = tm1.with_async(0.2, wait=10)
        assert long_wait.processes.execute_with_return(fast).process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
        short_wait = tm1.with_async(0.2, wait=1)
        assert short_wait.processes.execute_with_return(slow).process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
    finally:
        for proc in (fast, slow):
            with contextlib.suppress(Exception):
                tm1.processes.delete(proc)


def test_stops_the_work_on_tm1_when_cancelled() -> None:
    process = "tm1-client-" + uuid.uuid4().hex[:8]
    loop = "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"

    async def wait_for(tm1: Any, running: bool, v12: bool) -> bool:
        """Waits up to 30 s for a thread (v11) or job (v12) running the process to be there, or gone."""
        for _ in range(150):
            if v12:
                jobs = [job async for job in tm1.session.stream(Tm1Request.list(ODataPath.of("Jobs"), ODataQuery.none(), dict))]
                found = any(f"Processes('{process}')" in str(j.get("Description") or "") for j in jobs)
            else:
                threads = await tm1.threads.list()
                found = any(t.function and f"Processes('{process}')" in t.function for t in threads)
            if found == running:
                return True
            await asyncio.sleep(0.2)
        return False

    async def go() -> None:
        async with async_client() as tm1:
            version = (await tm1.session.capabilities()).product_version
            v12 = version is not None and version >= TM1_12
            await tm1.processes.create(ProcessCreate.builder().name(process).prolog_procedure(loop).build())
            try:
                # Cancelling the task is this flavor's way to cancel a run
                run = asyncio.ensure_future(tm1.with_async(0.2).processes.execute_with_return(process))
                assert await wait_for(tm1, True, v12), "the process runs"
                run.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await run
                assert await wait_for(tm1, False, v12), "cancelling deleted the monitor, which stopped it"
            finally:
                await wait_for(tm1, False, v12)
                await tm1.processes.delete(process)

    asyncio.run(go())
