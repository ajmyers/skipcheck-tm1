# SPDX-License-Identifier: Apache-2.0

"""The dimension scenarios (dimensions, hierarchies, elements, levels), offline in both flavors:
against a fake TM1 that answers each step with what the step expects, so every step's call is
made, sends one request, and reads its answer."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import httpx
import pytest

from skipcheck_tm1 import Tm1Client, Tm1Session, auth
from skipcheck_tm1.aio import AsyncTm1Client, AsyncTm1Session

from ..conftest import BASE, FakeTm1
from . import scenarios as live

SCENARIOS = ["dimensions", "hierarchies", "elements", "levels"]


def answer(expect: dict[str, Any] | None) -> Any:
    """What TM1 would answer for the call to meet the expectation."""
    if expect is None or expect.get("none"):
        return httpx.Response(204)
    if "error" in expect:
        error = expect["error"]
        kind = error.get("kind", "")
        status = error.get("status") or {"NOT_FOUND": 404, "CONFLICT": 409, "ALREADY_EXISTS": 409, "PRECONDITION_FAILED": 412}.get(kind, 400)
        message = {"NOT_FOUND": "'x' can not be found in collection", "ALREADY_EXISTS": "'x' already exists"}.get(kind, "refused")
        return httpx.Response(status, json={"error": {"code": "278", "message": message}})
    if "count" in expect:
        return {"value": [{} for _ in range(expect["count"])]}
    if "items" in expect:
        items: list[dict[str, Any]] = []
        for field, values in expect["items"].items():
            for i, v in enumerate(values):
                if len(items) <= i:
                    items.append({})
                items[i][field] = v
        return {"value": items}
    return expect["fields"]


def _fake(scenario: dict[str, Any], names: dict[str, str], traffic: live.Traffic) -> FakeTm1:
    fake = FakeTm1()

    def respond(request: httpx.Request) -> Any:
        step = traffic.step
        expect = None
        if step.startswith("steps["):
            expect = live.substitute(scenario["steps"][int(step[6:-1])]["expect"], names)
        return answer(expect)

    fake.respond = respond
    return fake


def _labels(scenario: dict[str, Any]) -> list[str]:
    return [f"steps[{i}]" for i in range(len(scenario["steps"]))] + [f"finally[{i}]" for i in range(len(scenario.get("finally", [])))]


@pytest.mark.parametrize("name", SCENARIOS)
def test_runs_each_step_once(name: str) -> None:
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    fake = _fake(scenario, names, traffic)
    http = httpx.Client(transport=httpx.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert [e["step"] for e in traffic.exchanges] == _labels(scenario)

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)
    fake2 = _fake(scenario, names2, traffic2)

    async def go() -> list[str]:
        http = httpx.AsyncClient(transport=httpx.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    assert [e["step"] for e in traffic2.exchanges] == _labels(scenario)
