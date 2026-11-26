# SPDX-License-Identifier: Apache-2.0

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

import httpx2
import pytest

from skipcheck_tm1 import Tm1Client, Tm1Session, Tm1Version, auth
from skipcheck_tm1.aio import AsyncTm1Client, AsyncTm1Session

from ..conftest import BASE, FakeTm1
from . import scenarios as live


def _tm1(names: dict[str, str]) -> FakeTm1:
    fake = FakeTm1()

    def respond(request: httpx2.Request) -> Any:
        target = request.url.raw_path.decode().removeprefix("/api/v1/")
        body = json.loads(request.content) if request.content else {}
        if request.method == "POST" and target in ("Dimensions", "Cubes"):
            return httpx2.Response(201, json={"Name": body["Name"]})
        if request.method == "GET" and (m := re.fullmatch(r"Cubes\('([^']*)'\)(\?.*)?", target)):
            return httpx2.Response(404, json={"error": {"code": "278", "message": "no"}}) if m[1] == names["missing"] else {"Name": m[1]}
        if request.method == "GET" and target.endswith("/Dimensions?$select=Name"):
            return {"value": [{"Name": "Sandboxes"}, {"Name": names["region"]}, {"Name": names["month"]}]}
        if target.endswith("tm1.CheckRules"):
            return {"value": []}
        return None

    fake.respond = respond
    return fake


@pytest.fixture
def traffic_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setattr(live, "TRAFFIC", tmp_path)
    return tmp_path


def test_runs_the_cubes_scenario_and_records_its_traffic(traffic_dir: Path) -> None:
    # In both flavors, and the traffic they record compares equal
    scenario = json.loads((live.SCENARIOS / "cubes.json").read_text(encoding="utf-8"))

    names = live.names_for(scenario)
    fake = _tm1(names)
    traffic = live.Traffic(BASE)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    traffic.write("cubes", "python-sync", names)

    names2 = live.names_for(scenario)
    fake2 = _tm1(names2)
    traffic2 = live.Traffic(BASE)

    async def go() -> list[str]:
        http = httpx2.AsyncClient(transport=httpx2.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
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


def test_stops_at_a_step_that_misses_its_expectation_and_still_cleans_up(traffic_dir: Path) -> None:
    scenario = json.loads((live.SCENARIOS / "cubes.json").read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    fake = _tm1(names)
    fake.respond = lambda request: httpx2.Response(500, json={"error": {"code": "1", "message": "down"}})
    traffic = live.Traffic(BASE)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    failures = live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names)
    assert len(failures) == 1 and failures[0].startswith("step 0 dimensions.create")
    assert [e["step"] for e in traffic.exchanges][-3:] == ["finally[0]", "finally[1]", "finally[2]"]


def test_applies_what_the_server_has_and_is() -> None:
    scenario = {"requires": ["Action Cube/Reserve"], "tm1From": "11.8.03000", "tm1Before": "11.8.04000"}
    reserve = {"Action Cube/Reserve"}
    assert live.applies(scenario, reserve, Tm1Version.parse("11.8.03500.6"))
    assert not live.applies(scenario, set(), Tm1Version.parse("11.8.03500.6")), "an endpoint the server lacks"
    assert not live.applies(scenario, reserve, Tm1Version.parse("11.8.02400.7")), "too old"
    assert not live.applies(scenario, reserve, Tm1Version.parse("11.8.04000.1")), "tm1Before is exclusive"
    assert live.applies({}, set(), Tm1Version.parse("11.8.02400.7")), "one with neither applies everywhere"


def test_skips_the_steps_that_dont_apply_and_sends_nothing_for_them(traffic_dir: Path) -> None:
    # cubes.json without its two dimension creates: they send nothing, the rest run
    scenario = json.loads((live.SCENARIOS / "cubes.json").read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    fake = _tm1(names)
    traffic = live.Traffic(BASE)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    live.run(
        Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)),
        traffic,
        scenario,
        names,
        lambda step: not (step.get("service") == "dimensions" and step.get("method") == "create"),
    )
    steps = [e["step"] for e in traffic.exchanges]
    assert "steps[0]" not in steps and "steps[1]" not in steps and "steps[2]" in steps


def _expected(expect: dict[str, Any]) -> httpx2.Response:
    """What a step expects, as TM1's answer to its one request."""
    if "error" in expect:
        error = expect["error"]
        status = error.get("status") or {"NOT_FOUND": 404, "CONFLICT": 409, "PRECONDITION_FAILED": 412}.get(error.get("kind"), 400)
        message = "can not be found" if error.get("kind") == "NOT_FOUND" else "refused"
        code = "248" if error.get("kind") == "INVALID_UNIQUE_NAME" else "278"
        return httpx2.Response(status, json={"error": {"code": code, "message": message}})
    if "$bytes" in expect.get("fields", {}):
        content = base64.b64decode(expect["fields"]["$bytes"])
        return httpx2.Response(200, content=content, headers={"Content-Type": "application/octet-stream"})
    if "fields" in expect:
        return httpx2.Response(200, json=expect["fields"])
    if "count" in expect or "items" in expect:
        value: list[dict[str, Any]] = [{} for _ in range(expect.get("count", 0))]
        for field, values in expect.get("items", {}).items():
            for i, v in enumerate(values):
                if i == len(value):
                    value.append({})
                value[i][field] = v
        return httpx2.Response(200, json={"value": value})
    return httpx2.Response(204)


def _sends(step: dict[str, Any]) -> bool:
    """Whether a step sends a request: all but those refused before one (an argument error)."""
    error = step["expect"].get("error")
    return error is None or error.get("category") == "api"


def _saving(step: dict[str, Any], expect: dict[str, Any]) -> dict[str, Any]:
    """The expectation with the fields the step saves, as ``saved_<name>``, if it lacks them."""
    if "save" not in step:
        return expect
    fields = dict(expect.get("fields", {}))
    for name, field in step["save"].items():
        fields.setdefault(field, "saved_" + name)
    return {**expect, "fields": fields}


def _answering_as_expected(scenario: dict[str, Any], names: dict[str, str]) -> FakeTm1:
    """Answers each step's one request with what the step expects (with what it saves too, when it
    doesn't expect it), and a finally step's with 204."""
    answers = [_expected(_saving(step, live.substitute(step["expect"], names))) for step in scenario["steps"] if _sends(step)]
    answers += [httpx2.Response(204) for _ in scenario.get("finally", [])]
    fake = FakeTm1()
    fake.respond = lambda request: answers.pop(0) if answers else httpx2.Response(599, text="unscripted")
    return fake


@pytest.mark.parametrize(
    "name",
    [
        "subsets",
        "members",
        "edges",
        "member-sets",
        "content",
        "encryption",
        "git",
        "users",
        "sessions",
        "attributes",
        "annotations",
        "processes",
        "chores",
        "round-trips",
    ],
)
def test_runs_a_scenario_sending_one_request_per_step(traffic_dir: Path, name: str) -> None:
    # In both flavors
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))
    expected = len([s for s in scenario["steps"] if _sends(s)]) + len(scenario.get("finally", []))

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    fake = _answering_as_expected(scenario, names)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert len(traffic.exchanges) == expected
    traffic.write(name, "python-sync", names)

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)
    fake2 = _answering_as_expected(scenario, names2)

    async def go() -> list[str]:
        http = httpx2.AsyncClient(transport=httpx2.MockTransport(fake2.handler), event_hooks={"response": [traffic2.arecord]})
        return await live.arun(AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic2, scenario, names2)

    assert asyncio.run(go()) == []
    assert len(traffic2.exchanges) == expected
    traffic2.write(name, "python-async", names2)

    spec = importlib.util.spec_from_file_location("compare", live.REPO / "scripts" / "compare-live-traffic.py")
    assert spec and spec.loader
    compare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compare)
    assert compare.main(["x", str(traffic_dir)]) == 0


def test_builds_a_write_from_what_a_step_kept() -> None:
    # The patch carries what the read held (Rules) with the change; a finally step setting back a
    # read no step kept sends nothing
    scenario = json.loads(
        '{"names":["cube"],"steps":[{"service":"cubes","method":"get","params":["name","query"],"args":["${cube}",{"$query":{}}],"keep":"cube","expect":{"fields":{"Name":"${cube}","Rules":"SKIPCHECK;"}}},{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"cube","json":{"DrillthroughRules":"x"}}],"expect":{"none":true}}],"finally":[{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"never","json":{}}]}]}'
    )
    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    fake = _answering_as_expected(scenario, names)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert len(traffic.exchanges) == 2
    assert traffic.exchanges[1]["request"]["json"] == {"Rules": "SKIPCHECK;", "DrillthroughRules": "x"}


def test_saves_a_field_of_a_result_for_the_steps_after_it() -> None:
    scenario = json.loads((live.SCENARIOS / "annotations.json").read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    fake = _answering_as_expected(scenario, names)
    http = httpx2.Client(transport=httpx2.MockTransport(fake.handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    assert names["note"] == "saved_note" and names["built"] == "saved_built"
    sent = [f"{e['request']['method']} {e['request']['target']}" for e in traffic.exchanges]
    assert "PATCH Annotations('saved_built')" in sent and "DELETE Annotations('saved_note')" in sent

    why = live.save({"save": {"note": "ID"}}, {"result": {"Text": "no ID"}}, names)
    assert why is not None and why.startswith("no ID to save as note")


def _data_tm1(names: dict[str, str]) -> FakeTm1:
    """Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of 3 × 2
    cells (and the slicer), each worth its ordinal until an update writes it, names that exist but
    ``missing``."""
    fake = FakeTm1()
    missing = names["missing"]
    # The cells' values, by ordinal, as the scenario's updates write them
    values: dict[int, Any] = {}

    def cells(begin: int, end: int) -> list[dict[str, Any]]:
        return [{"Ordinal": n, "Value": values.get(n, n)} for n in range(begin, end + 1)]

    def ordinal(update: dict[str, Any]) -> int:
        # By the update's ordinal, or its tuple's elements (a region, then a month)
        if "Ordinal" in update:
            return int(update["Ordinal"])
        region, month = (re.search(r"Elements\('([^']*)'\)$", path)[1] for path in update["Tuple@odata.bind"])
        return ["North", "South"].index(region) * 3 + ["Jan", "Feb", "Mar"].index(month)

    def respond(request: httpx2.Request) -> Any:
        target = request.url.raw_path.decode().removeprefix("/api/v1/")
        method = request.method
        body = json.loads(request.content) if request.content else {}
        if f"('{missing}')" in target or (method == "POST" and target.startswith("ExecuteRelationalDrillthrough")):
            return httpx2.Response(404, json={"error": {"code": "278", "message": "no"}})
        if method == "POST" and re.fullmatch(r"Dimensions|Cubes|Sandboxes|Cubes\('[^']*'\)/(Private)?Views", target):
            return httpx2.Response(201, json={"Name": body["Name"]})
        if method == "POST" and target.split("?")[0] == "ExecuteMDX":
            if "SELEKT" in body["MDX"]:
                return httpx2.Response(400, json={"error": {"code": "501", "message": "syntax"}})
            top = int(m[1]) if (m := re.search(r"\$top%3D(\d+)", target)) else 0
            axes = [{"Ordinal": 0, "Cardinality": 3}, {"Ordinal": 1, "Cardinality": 2}, {"Ordinal": 2, "Cardinality": 1}]
            return {"ID": "cs1", "Axes": axes, "Cells": cells(0, top - 1)}
        if method == "POST" and re.fullmatch(r"(Cellsets\('cs1'\)|Cubes\('[^']*'\))/tm1\.UpdateCells", target):
            for update in body["Updates"]:
                values[ordinal(update)] = update["Value"]
            return httpx2.Response(204)
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


def test_treats_a_null_as_missing_at_any_depth() -> None:
    expected = {"A": {"B": 1, "C": [1.0, {"D": None}]}}
    assert live.same(expected, {"A": {"B": 1.0, "C": [1, {}], "E": None}})
    assert not live.same(expected, {"A": {"B": 1, "C": [1, {"D": 2}]}})
    assert not live.same(expected, {"A": {"C": [1, {}]}})
    assert not live.same(expected, {"A": {"B": 1, "C": [1]}})
    assert not live.same(True, 1)


@pytest.mark.parametrize("name", ["cellsets", "views", "sandboxes"])
def test_runs_the_data_scenarios(traffic_dir: Path, name: str) -> None:
    # In both flavors, and the traffic they record compares equal
    scenario = json.loads((live.SCENARIOS / f"{name}.json").read_text(encoding="utf-8"))

    names = live.names_for(scenario)
    traffic = live.Traffic(BASE)
    http = httpx2.Client(transport=httpx2.MockTransport(_data_tm1(names).handler), event_hooks={"response": [traffic.record]})
    assert live.run(Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=http)), traffic, scenario, names) == []
    traffic.write(name, "python-sync", names)

    names2 = live.names_for(scenario)
    traffic2 = live.Traffic(BASE)

    async def go() -> list[str]:
        http = httpx2.AsyncClient(transport=httpx2.MockTransport(_data_tm1(names2).handler), event_hooks={"response": [traffic2.arecord]})
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
