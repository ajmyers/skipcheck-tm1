"""ServerService and LogService against a real TM1: ports of the Java client's
ServerServiceTm1Test and LogServiceTm1Test (see conftest.py for how to point them at one)."""

from __future__ import annotations

import threading
import time
from typing import Any

import pytest

from tm1_client import ODataPath, ODataQuery, Tm1BadRequestError, Tm1Client, Tm1Request
from tm1_client.model import LogLevel, ProcessPatch, ServerSettingsPatch, UserPatch

from .conftest import CONFIGURED, REASON, client

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_the_server_and_its_configurations(tm1: Tm1Client) -> None:
    read = tm1.server.server()
    assert read.product_version
    assert read.etag is None, "none for the Server"
    fixed = tm1.server.static_configuration()
    active = tm1.server.active_configuration()
    assert fixed.server_name == read.name
    assert active.server_name == read.name
    assert tm1.server.configuration().server_name == read.name

    tm1.server.update_static_configuration(ServerSettingsPatch.builder().server_name(fixed.server_name).build())
    with pytest.raises(Tm1BadRequestError):
        # The active configuration is read only
        tm1.session.execute(
            Tm1Request.patch(ODataPath.of("ActiveConfiguration"), ServerSettingsPatch.builder().server_name(active.server_name).build())
        )
    # A duration here, though declared Int32
    assert isinstance(active.access.cam.client_ping_cam_passport, str)


def test_reads_and_sets_logger_levels(tm1: Tm1Client) -> None:
    loggers = tm1.server.loggers()
    assert loggers
    # Not the first, TM1's root logger: at DEBUG it floods the message log of the server every
    # test shares, with lines alike to the millisecond
    first = next(logger for logger in loggers if logger.name == "TM1.Blob")
    assert tm1.server.logger(first.name).level == first.level
    other = LogLevel.INFO if first.level == LogLevel.DEBUG else LogLevel.DEBUG
    tm1.server.set_logger_level(first.name, other)
    try:
        assert tm1.server.logger(first.name).level == other
    finally:
        tm1.server.set_logger_level(first.name, first.level)


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


def test_reads_a_passwords_hash_that_isnt_utf8_as_replacement_characters(tm1: Tm1Client, name: str) -> None:
    tm1.users.create(UserPatch.builder().name(name).password("pw").build())
    try:
        newest = tm1.logs.transaction_log(True, True, ODataQuery.none().top(50))
        # The password's hash, which TM1 sends as bytes that aren't UTF-8
        [entry] = [e for e in newest if e.cube == "}ClientProperties" and "PASSWORD" in e.tuple and name in e.tuple]
        assert chr(0xFFFD) in str(entry.new_value)
    finally:
        tm1.users.delete(name)


def test_reads_the_transaction_and_audit_logs(tm1: Tm1Client) -> None:
    assert tm1.logs.transaction_log_entries(ODataQuery.none().top(5))
    assert all(e.status_message is None for e in tm1.logs.transaction_log(True, True, ODataQuery.none().top(5)))
    assert tm1.logs.audit_log_entries(ODataQuery.none().expand("AuditDetails").top(5)) == [], "the audit log is off on the test server"


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
