"""The live conformance runner, offline: the cubes scenario against a fake TM1 that answers as TM1
would, in both flavors, writing traffic that scripts/compare-live-traffic.py finds equal."""

from __future__ import annotations

import asyncio
import base64
import importlib.util
import json
import re
from pathlib import Path
from typing import Any

import httpx
import pytest

from tm1_client import Tm1Client, Tm1Session, auth
from tm1_client.aio import AsyncTm1Client, AsyncTm1Session

from . import conformance_live as live
from .conftest import BASE, FakeTm1


def _tm1(names: dict[str, str]) -> FakeTm1:
    fake = FakeTm1()

    def respond(request: httpx.Request) -> Any:
        target = request.url.raw_path.decode().removeprefix("/api/v1/")
        body = json.loads(request.content) if request.content else {}
        if request.method == "POST" and target in ("Dimensions", "Cubes"):
            return httpx.Response(201, json={"Name": body["Name"]})
        if request.method == "GET" and (m := re.fullmatch(r"Cubes\('([^']*)'\)(\?.*)?", target)):
            return httpx.Response(404, json={"error": {"code": "278", "message": "no"}}) if m[1] == names["missing"] else {"Name": m[1]}
        if request.method == "GET" and target.endswith("/Dimensions?$select=Name"):
            return {"value": [{"Name": names["region"]}, {"Name": names["month"]}]}
        if target.endswith("tm1.CheckRules"):
            return {"value": []}
        return None

    fake.respond = respond
    return fake


@pytest.fixture
def traffic_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setattr(live, "TRAFFIC", tmp_path)
    return tmp_path


def test_runs_the_cubes_scenario_in_both_flavors_and_the_traffic_compares_equal(traffic_dir: Path) -> None:
    scenario = json.loads((live.SCENARIOS / "cubes.json").read_text(encoding="utf-8"))

    names = live.names_for(scenario)
    fake = _tm1(names)
    traffic = live.Traffic(BASE)
    http = httpx.Client(transport=httpx.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    traffic.write("cubes", "python-sync", names)

    names2 = live.names_for(scenario)
    fake2 = _tm1(names2)
    traffic2 = live.Traffic(BASE)

    async def go() -> list[str]:
        http = httpx.AsyncClient(transport=httpx.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    traffic2.write("cubes", "python-async", names2)

    lines = (traffic_dir / "python-sync" / "cubes.jsonl").read_text().splitlines()
    assert json.loads(lines[0])["names"] == names
    steps = [json.loads(line)["step"] for line in lines[1:]]
    assert steps[0] == "steps[0]" and steps[-1] == "finally[2]"
    assert len(steps) == len(scenario["steps"]) + len(scenario["finally"]), "the session's own requests are left out"

    spec = importlib.util.spec_from_file_location("compare", live.REPO / "scripts" / "compare-live-traffic.py")
    assert spec and spec.loader
    compare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compare)
    assert compare.main(["x", str(traffic_dir)]) == 0


def test_a_step_that_misses_its_expectation_fails_and_still_cleans_up(traffic_dir: Path) -> None:
    scenario = json.loads((live.SCENARIOS / "cubes.json").read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    fake = _tm1(names)
    fake.respond = lambda request: httpx.Response(500, json={"error": {"code": "1", "message": "down"}})
    traffic = live.Traffic(BASE)
    http = httpx.Client(transport=httpx.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    failures = live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names)
    assert len(failures) == 1 and failures[0].startswith("step 0 dimensions.create")
    assert [e["step"] for e in traffic.exchanges][-3:] == ["finally[0]", "finally[1]", "finally[2]"]


def _expected(expect: dict[str, Any]) -> httpx.Response:
    """What a step expects, as TM1's answer to its one request."""
    if "error" in expect:
        error = expect["error"]
        status = error.get("status") or {"NOT_FOUND": 404, "CONFLICT": 409, "PRECONDITION_FAILED": 412}.get(error.get("kind"), 400)
        message = "can not be found" if error.get("kind") == "NOT_FOUND" else "refused"
        return httpx.Response(status, json={"error": {"code": "278", "message": message}})
    if "$bytes" in expect.get("fields", {}):
        content = base64.b64decode(expect["fields"]["$bytes"])
        return httpx.Response(200, content=content, headers={"Content-Type": "application/octet-stream"})
    if "fields" in expect:
        return httpx.Response(200, json=expect["fields"])
    if "count" in expect or "items" in expect:
        value: list[dict[str, Any]] = [{} for _ in range(expect.get("count", 0))]
        for field, values in expect.get("items", {}).items():
            for i, v in enumerate(values):
                if i == len(value):
                    value.append({})
                value[i][field] = v
        return httpx.Response(200, json={"value": value})
    return httpx.Response(204)


def _answering_as_expected(scenario: dict[str, Any], names: dict[str, str]) -> FakeTm1:
    """Answers each step's one request with what the step expects, and a finally step's with 204."""
    answers = [_expected(live.substitute(step["expect"], names)) for step in scenario["steps"]]
    answers += [httpx.Response(204) for _ in scenario.get("finally", [])]
    fake = FakeTm1()
    fake.respond = lambda request: answers.pop(0) if answers else httpx.Response(599, text="unscripted")
    return fake


@pytest.mark.parametrize("name", ["subsets", "members", "edges", "member-sets", "content", "encryption", "git"])
def test_runs_a_scenario_in_both_flavors_sending_one_request_per_step(traffic_dir: Path, name: str) -> None:
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))
    expected = len(scenario["steps"]) + len(scenario.get("finally", []))

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    fake = _answering_as_expected(scenario, names)
    http = httpx.Client(transport=httpx.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert len(traffic.exchanges) == expected
    traffic.write(name, "python-sync", names)

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)
    fake2 = _answering_as_expected(scenario, names2)

    async def go() -> list[str]:
        http = httpx.AsyncClient(transport=httpx.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    assert len(traffic2.exchanges) == expected
    traffic2.write(name, "python-async", names2)

    spec = importlib.util.spec_from_file_location("compare", live.REPO / "scripts" / "compare-live-traffic.py")
    assert spec and spec.loader
    compare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compare)
    assert compare.main(["x", str(traffic_dir)]) == 0


def _data_tm1(names: dict[str, str]) -> FakeTm1:
    """Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of 3 × 2
    cells (and the slicer), names that exist but ``missing``."""
    fake = FakeTm1()
    missing = names["missing"]

    def cells(begin: int, end: int) -> list[dict[str, Any]]:
        return [{"Ordinal": n, "Value": n} for n in range(begin, end + 1)]

    def respond(request: httpx.Request) -> Any:
        target = request.url.raw_path.decode().removeprefix("/api/v1/")
        method = request.method
        body = json.loads(request.content) if request.content else {}
        if f"('{missing}')" in target or (method == "POST" and target.startswith("ExecuteRelationalDrillthrough")):
            return httpx.Response(404, json={"error": {"code": "278", "message": "no"}})
        if method == "POST" and re.fullmatch(r"Dimensions|Cubes|Sandboxes|Cubes\('[^']*'\)/(Private)?Views", target):
            return httpx.Response(201, json={"Name": body["Name"]})
        if method == "POST" and target.split("?")[0] == "ExecuteMDX":
            if "SELEKT" in body["MDX"]:
                return httpx.Response(400, json={"error": {"code": "501", "message": "syntax"}})
            top = int(re.search(r"\$top%3D(\d+)", target)[1])
            axes = [{"Ordinal": 0, "Cardinality": 3}, {"Ordinal": 1, "Cardinality": 2}, {"Ordinal": 2, "Cardinality": 1}]
            return {"ID": "cs1", "Axes": axes, "Cells": cells(0, top - 1)}
        if method == "POST" and target.endswith("/tm1.Execute?$select=ID"):
            return {"ID": "cs2"}
        if method == "GET" and (m := re.match(r"Cellsets\('cs1'\)/tm1\.GetPartition\(Begin=(\d+),End=(\d+)\)", target)):
            return {"value": cells(int(m[1]), int(m[2]))}
        if method == "GET" and (m := re.match(r"Cubes\('([^']*)'\)/[A-Za-z]+\('[^']*'\)/Cube", target)):
            return {"Name": m[1]}
        if method == "GET" and (m := re.match(r"Cubes\('[^']*'\)/(Private)?Views\('([^']*)'\)", target)):
            native = {"@odata.type": "#ibm.tm1.api.v1.NativeView", "SuppressEmptyRows": True} if "NativeView" in target else {}
            return {**native, "Name": m[2]}
        if method == "GET" and re.match(r"Cubes\('[^']*'\)/PrivateViews\?", target):
            return {"value": [{"Name": names["native"]}]}
        if method == "GET" and (m := re.match(r"Sandboxes\('([^']*)'\)", target)):
            return {"Name": m[1], "IsLoaded": True}
        return None

    fake.respond = respond
    return fake


@pytest.mark.parametrize("name", ["cellsets", "views", "sandboxes"])
def test_runs_the_data_scenarios_in_both_flavors_and_the_traffic_compares_equal(traffic_dir: Path, name: str) -> None:
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    http = httpx.Client(transport=httpx.MockTransport(_data_tm1(names).handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    traffic.write(name, "python-sync", names)

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)

    async def go() -> list[str]:
        http = httpx.AsyncClient(transport=httpx.MockTransport(_data_tm1(names2).handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    traffic2.write(name, "python-async", names2)

    steps = {x["step"] for x in traffic.exchanges}
    assert steps == {f"steps[{i}]" for i in range(len(scenario["steps"]))} | {f"finally[{i}]" for i in range(len(scenario["finally"]))}

    spec = importlib.util.spec_from_file_location("compare", live.REPO / "scripts" / "compare-live-traffic.py")
    assert spec and spec.loader
    compare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compare)
    assert compare.main(["x", str(traffic_dir)]) == 0
