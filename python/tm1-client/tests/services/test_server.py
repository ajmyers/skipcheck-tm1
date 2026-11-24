# SPDX-License-Identifier: Apache-2.0

"""ServerService and LogService, in both flavors, against a fake TM1: each method sends what
the Java client's does, and reads TM1's answer the same way."""

from __future__ import annotations

import inspect
import itertools
import json
from typing import Any

import httpx2

from skipcheck_tm1 import ODataQuery
from skipcheck_tm1.model import AdministrationSettingsPatch, LoggerPatch, LogLevel, Server, ServerSettingsPatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx2.Request) -> object:
    return json.loads(request.content) if request.content else None


def take(api: Api, entries: Any, n: int) -> list[Any]:
    """The first ``n`` items of an iterator of either flavor."""
    if inspect.isasyncgen(entries) or hasattr(entries, "__anext__"):

        async def first() -> list[Any]:
            out = []
            async for entry in entries:
                out.append(entry)
                if len(out) == n:
                    break
            await entries.aclose()
            return out

        return api.run(first())
    out = list(itertools.islice(entries, n))
    entries.close()
    return out


def test_reads_the_server_and_its_configuration(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Name": "tm1", "ProductVersion": "11.8.03500.6"}
    server = api.run(api.client.server.server(ODataQuery.none().select(Server.NAME, Server.PRODUCT_VERSION)))
    assert (server.name, server.product_version) == ("tm1", "11.8.03500.6")
    assert target(fake.last) == "GET Server?$select=Name,ProductVersion"

    fake.respond = lambda r: {"ServerName": "tm1", "Administration": {"DataBaseDirectory": "/data"}}
    settings = api.run(api.client.server.active_configuration())
    assert settings.administration.data_base_directory == "/data"
    assert target(fake.last) == "GET ActiveConfiguration"
    api.run(api.client.server.static_configuration(select="ServerName"))
    assert target(fake.last) == "GET StaticConfiguration?$select=ServerName"
    api.run(api.client.server.configuration())
    assert target(fake.last) == "GET Configuration"

    fake.respond = lambda r: None
    patch = ServerSettingsPatch.builder().administration(AdministrationSettingsPatch.builder().server_name("tm2").build()).build()
    api.run(api.client.server.update_static_configuration(patch))
    assert (target(fake.last), body(fake.last)) == ("PATCH StaticConfiguration", {"Administration": {"ServerName": "tm2"}})


def test_loggers(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Name": "TM1.Server", "Level": "Info"}]}
    loggers = api.run(api.client.server.loggers())
    assert loggers[0].level is LogLevel.INFO
    assert target(fake.last) == "GET Loggers"
    fake.respond = lambda r: {"Name": "TM1.Process", "Level": "Debug"}
    assert api.run(api.client.server.logger("TM1.Process")).name == "TM1.Process"
    assert target(fake.last) == "GET Loggers('TM1.Process')"
    fake.respond = lambda r: None
    api.run(api.client.server.set_logger_level("TM1.Process", LogLevel.DEBUG))
    assert (target(fake.last), body(fake.last)) == ("PATCH Loggers('TM1.Process')", {"Level": "Debug"})


def test_updates_a_logger_with_a_patch(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.server.update_logger("TM1.Process", LoggerPatch.builder().level(LogLevel.DEBUG).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Loggers('TM1.Process')", {"Level": "Debug"})


def test_log_entries(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Level": "Error", "TimeStamp": "0000-00-00T00:00Z", "Message": "x"}]}
    entries = api.run(api.client.logs.message_log_entries(top=10))
    assert target(fake.last) == "GET MessageLogEntries?$top=10"
    # TM1 (11.8): an entry without a time reads as None
    assert entries[0].time_stamp is None and entries[0].level is LogLevel.ERROR
    api.run(api.client.logs.message_log(True, ODataQuery.none().top(50)))
    assert target(fake.last) == "GET MessageLog(Reverse=true)?$top=50"
    fake.respond = lambda r: {"value": [{"StatusMessage": "CubeSerialized: Sales"}]}
    api.run(api.client.logs.transaction_log_entries())
    assert target(fake.last) == "GET TransactionLogEntries"
    api.run(api.client.logs.transaction_log(False, True))
    assert target(fake.last) == "GET TransactionLog(Reverse=false,IgnoreStatusMessages=true)"
    api.run(api.client.logs.audit_log_entries(expand="AuditDetails"))
    assert target(fake.last) == "GET AuditLogEntries?$expand=AuditDetails"


def test_error_logs(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Filename": "tm1processerror_1_Load Sales.log"}]}
    files = api.run(api.client.logs.error_log_files())
    assert files[0].filename == "tm1processerror_1_Load Sales.log"
    assert target(fake.last) == "GET ErrorLogFiles"
    fake.respond = lambda r: {"Filename": "a.log"}
    api.run(api.client.logs.error_log_file("a.log"))
    assert target(fake.last) == "GET ErrorLogFiles('a.log')"
    # TM1 (11.8) starts the content with a byte order mark, which is dropped
    fake.respond = lambda r: httpx2.Response(200, content="﻿Line 1".encode())
    assert api.run(api.client.logs.error_log_content("a.log")) == "Line 1"
    assert target(fake.last) == "GET ErrorLogFiles('a.log')/Content"


def test_tails_a_log_skipping_what_it_has_yielded(api: Api, fake: FakeTm1) -> None:
    # TM1 (11.8) answers the delta link with every entry since it was made, each time
    polls = iter(
        [
            {"value": [{"Message": "a"}]},
            {"value": [{"Message": "a"}, {"Message": "b"}, {"Message": "c"}]},
        ]
    )

    def respond(request: httpx2.Request) -> Any:
        if target(request) == "GET TailMessageLog()":
            return {"value": "MessageLogEntries/!delta('0[4096]38@11!19')"}
        assert target(request) == "GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')"
        return next(polls)

    fake.respond = respond
    entries = take(api, api.client.logs.tail_message_log(0.001), 3)
    assert [e.message for e in entries] == ["a", "b", "c"]
    assert [target(r) for r in fake.requests].count("GET TailMessageLog()") == 1


def test_tails_the_transaction_and_audit_logs(api: Api, fake: FakeTm1) -> None:
    def respond(request: httpx2.Request) -> Any:
        if "Tail" in target(request):
            return {"value": "TransactionLogEntries/!delta('1')"}
        return {"value": [{"Cube": "Sales"}]}

    fake.respond = respond
    assert take(api, api.client.logs.tail_transaction_log(0.001), 1)[0].cube == "Sales"
    assert "GET TailTransactionLog()" in [target(r) for r in fake.requests]
    take(api, api.client.logs.tail_audit_log(0.001), 1)
    assert "GET TailAuditLog()" in [target(r) for r in fake.requests]
