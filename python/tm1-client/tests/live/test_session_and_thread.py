# SPDX-License-Identifier: Apache-2.0

"""SessionService and ThreadService against a real TM1: a port of the Java client's
SessionAndThreadTm1Test."""

from __future__ import annotations

import threading
import time

import pytest

from skipcheck_tm1 import (
    Tm1Client,
    Tm1Error,
    Tm1ServerError,
)
from skipcheck_tm1.model import ProcessCreate

from .conftest import ADMIN, CONFIGURED, REASON, other_client
from .support import assume_v11, is_v12

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_sessions_their_users_and_threads_and_closes_another(tm1: Tm1Client, name: str) -> None:
    other = other_client(session_context=name)
    try:
        other.connect()
        id = other.sessions.active_session().id
        assert tm1.sessions.active_session().id != id, "each client its own"
        assert id in [s.id for s in tm1.sessions.list()]
        assert tm1.sessions.get(id).context == name
        assert tm1.sessions.user(id).name.lower() == ADMIN.lower()
        assert tm1.sessions.threads(id) is not None
        assert id in [s.id for s in tm1.users.sessions(ADMIN)]

        tm1.sessions.close(id)
        after = other.sessions.active_session().id
        if not is_v12(tm1):
            assert after != id, "the other client logs in again, as a new session"
    finally:
        other.close()


def test_cancels_a_running_process_through_its_thread(tm1: Tm1Client, name: str) -> None:
    assume_v11(tm1, "TM1 v12 tracks active work in Jobs rather than Threads")
    tm1.processes.create(ProcessCreate.builder().name(name).prolog_procedure("i = 0;\nWhile(i < 100000000);\n  i = i + 1;\nEnd;\n").build())
    runner = other_client()
    outcome: list[BaseException | object] = []

    def run() -> None:
        try:
            outcome.append(runner.processes.execute_with_return(name))
        except BaseException as e:  # noqa: BLE001
            outcome.append(e)

    worker = threading.Thread(target=run)
    worker.start()
    try:
        running = None
        for _ in range(100):
            running = next(
                (t for t in tm1.threads.list() if t.function and f"Processes('{name}')/tm1.ExecuteWithReturn" in t.function),
                None,
            )
            if running is not None:
                break
            time.sleep(0.1)
        assert running is not None, "the process's thread"
        assert running.object_name == "", "a process run through REST shows as its request"
        assert name in tm1.threads.get(running.id).function
        assert tm1.threads.session(running.id).id is not None
        tm1.threads.cancel(running.id)
        worker.join(60)
        (result,) = outcome
        # The run's request fails, rather than reporting Aborted
        assert isinstance(result, Tm1ServerError) and "Cancel" in str(result)
    finally:
        worker.join(120)
        try:
            runner.close()
        except Tm1Error:
            pass
        tm1.processes.delete(name)
