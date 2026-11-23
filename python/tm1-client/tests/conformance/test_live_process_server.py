# SPDX-License-Identifier: Apache-2.0

"""The live conformance runner, offline: the processes, chores and server scenarios against a fake
TM1 that answers as TM1 would, in both flavors (the Java client's ProcessAndServerScenariosTest,
with the same fake)."""

from __future__ import annotations

import asyncio
import json
import re
from typing import Any

import httpx
import pytest

from skipcheck_tm1 import Tm1Client, Tm1Session, auth
from skipcheck_tm1.aio import AsyncTm1Client, AsyncTm1Session

from ..conftest import BASE, FakeTm1
from . import scenarios as live

_ENTITY = re.compile(r"(Processes|Chores|Loggers|ErrorLogFiles|SQLDataSources)\('([^']*)'\)(\?.*)?")
_TOP = re.compile(r"(MessageLogEntries|MessageLog\(Reverse=true\))\?\$top=(\d+)")


def _tm1(names: dict[str, str]) -> FakeTm1:
    fake = FakeTm1()
    tasks = [0]

    def respond(request: httpx.Request) -> Any:
        target = request.url.raw_path.decode().removeprefix("/api/v1/")
        method = request.method
        if method == "POST" and target in ("Processes", "Chores"):
            body = json.loads(request.content)
            if target == "Chores":
                tasks[0] = len(body.get("Tasks", []))
            return httpx.Response(201, json={"Name": body["Name"]})
        if method == "GET" and (m := _ENTITY.fullmatch(target)):
            if m[2] in (names["missing"], names["missing"] + ".log"):
                return httpx.Response(404, json={"error": {"code": "278", "message": "no"}})
            return {"Name": m[2], "Active": False, "ExecutionMode": "SingleCommit"} if m[1] == "Chores" else {"Name": m[2]}
        if method == "GET" and target.startswith("Processes?"):
            return {"value": [{"Name": names["process"]}]}
        if method == "GET" and target.startswith("Chores?"):
            return {"value": [{"Name": names["chore"]}]}
        if target.endswith("/tm1.Compile"):
            return {"value": []}
        if target == "CompileProcess":
            return {"value": [{"Procedure": "Prolog", "LineNumber": 1, "Message": "Syntax error"}]}
        if "ExecuteWithReturn" in target or target.startswith("ExecuteProcessWithReturn"):
            return {"ProcessExecuteStatusCode": "CompletedSuccessfully"}
        if method == "GET" and target.startswith("ProcessDebugContexts"):
            return {"value": []}
        if re.fullmatch(r"Chores\('[^']*'\)/Tasks", target):
            tasks[0] += 1
            return httpx.Response(201, json={"Step": tasks[0] - 1})
        if method == "GET" and re.fullmatch(r"Chores\('[^']*'\)/Tasks\?.*", target):
            return {"value": [{"Step": i} for i in range(tasks[0])]}
        if method == "DELETE" and re.fullmatch(r"Chores\('[^']*'\)/Tasks\(\d+\)", target):
            tasks[0] -= 1
            return None
        if method == "GET" and re.fullmatch(r"Chores\('[^']*'\)/Tasks\(\d+\)/Process(\?.*)?", target):
            return {"Name": names["process"]}
        if method == "GET" and re.fullmatch(r"Chores\('[^']*'\)/Tasks\(\d+\)/Chore(\?.*)?", target):
            return {"Name": names["chore"]}
        if method == "GET" and target.startswith("Server?"):
            return {"Name": "tm1", "ProductVersion": fake.product_version}
        if method == "GET" and re.fullmatch(r"(StaticConfiguration|ActiveConfiguration|Configuration)(\?.*)?", target):
            return {"ServerName": "tm1"}
        if method == "GET" and target.startswith("Loggers?"):
            return {"value": [{"Name": "TM1.Server", "Level": "Info"}]}
        if method == "GET" and (m := _TOP.fullmatch(target)):
            return {"value": [{"ID": i, "Message": "m"} for i in range(int(m[2]))]}
        if method == "GET" and target == "SQLDataSources":
            return {"value": []}
        return None

    fake.respond = respond
    return fake


@pytest.fixture
def traffic_dir(tmp_path: Any, monkeypatch: pytest.MonkeyPatch) -> Any:
    monkeypatch.setattr(live, "TRAFFIC", tmp_path)
    return tmp_path


@pytest.mark.parametrize("name", ["server"])
def test_runs_the_scenario_in_both_flavors(name: str, traffic_dir: Any) -> None:
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))
    expected = len(scenario["steps"]) + len(scenario["finally"])

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    http = httpx.Client(transport=httpx.MockTransport(_tm1(names).handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert len(traffic.exchanges) == expected, "one request a step, the session's own left out"

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)
    fake2 = _tm1(names2)

    async def go() -> list[str]:
        http = httpx.AsyncClient(transport=httpx.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    assert len(traffic2.exchanges) == expected
