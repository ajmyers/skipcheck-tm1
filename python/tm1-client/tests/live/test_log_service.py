# SPDX-License-Identifier: Apache-2.0

"""LogService against a real TM1: a port of the Java client's LogServiceTm1Test."""

from __future__ import annotations

import threading
import time
from typing import Any

import pytest

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import ProcessPatch, UserPatch

from .conftest import CONFIGURED, REASON, client

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_the_message_log(tm1: Tm1Client) -> None:
    first = tm1.logs.message_log_entries(ODataQuery.none().top(20))
    assert first
    assert any(e.time_stamp is None for e in first), "an entry without a time reads as None rather than failing"
    again = tm1.logs.message_log_entries(ODataQuery.none().top(1))[0]
    assert again.message == first[0].message
    assert again.id != first[0].id, "an ID numbers the entry within its response"
    newest = tm1.logs.message_log(True, ODataQuery.none().top(3))
    assert len(newest) == 3
    assert newest[0].time_stamp >= newest[2].time_stamp


def test_follows_the_message_log_without_repeating(tm1: Tm1Client) -> None:
    def logins() -> None:
        for _ in range(2):
            time.sleep(1.5)
            with client() as c:
                c.cubes.list()

    threading.Thread(target=logins, daemon=True).start()
    seen: list[Any] = []
    tail = tm1.logs.tail_message_log(0.3)
    deadline = time.monotonic() + 6
    for entry in tail:
        seen.append(entry)
        if time.monotonic() > deadline or sum("Login Success" in (e.message or "") for e in seen) >= 2:
            break
    tail.close()
    assert sum("Login Success" in (e.message or "") for e in seen) >= 2
    distinct = {f"{e.time_stamp} {e.thread_id} {e.session_id} {e.message}" for e in seen}
    assert len(distinct) == len(seen), "no entry twice, though each poll returns them all again"


def test_reads_the_transaction_and_audit_logs(tm1: Tm1Client) -> None:
    assert tm1.logs.transaction_log_entries(ODataQuery.none().top(5))
    assert all(e.status_message is None for e in tm1.logs.transaction_log(True, True, ODataQuery.none().top(5)))
    assert tm1.logs.audit_log_entries(ODataQuery.none().expand("AuditDetails").top(5)) == [], "the audit log is off on the test server"


def test_reads_a_passwords_hash_that_isnt_utf8_as_replacement_characters(tm1: Tm1Client, name: str) -> None:
    tm1.users.create(UserPatch.builder().name(name).password("pw").build())
    try:
        newest = tm1.logs.transaction_log(True, True, ODataQuery.none().top(50))
        # The password's hash, which TM1 sends as bytes that aren't UTF-8
        [entry] = [e for e in newest if e.cube == "}ClientProperties" and "PASSWORD" in e.tuple and name in e.tuple]
        assert chr(0xFFFD) in str(entry.new_value)
    finally:
        tm1.users.delete(name)


def test_reads_a_process_error_log(tm1: Tm1Client, name: str) -> None:
    # Through the generated API, so this doesn't depend on ProcessService
    tm1.api.processes.create(ProcessPatch.builder().name(name).prolog_procedure("ProcessError;\n").build())
    try:
        result = tm1.api.processes[name].execute_with_return(query=ODataQuery.none().expand("ErrorLogFile"))
        filename = result.error_log_file.filename
        assert any(f.filename == filename for f in tm1.logs.error_log_files())
        assert tm1.logs.error_log_file(filename).filename == filename
        content = tm1.logs.error_log_content(filename)
        assert content.startswith("Error:"), "without the byte order mark"
        assert "ProcessError" in content
    finally:
        tm1.api.processes[name].delete()
