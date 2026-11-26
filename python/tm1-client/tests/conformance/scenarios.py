# SPDX-License-Identifier: Apache-2.0

"""Runs a live conformance scenario (spec/conformance/live/<name>.json) with one of this client's
flavors, checks each step's expectation, and writes what it sent to
build/conformance-live/<language>/<name>.jsonl for scripts/compare-live-traffic.py."""

from __future__ import annotations

import inspect
import json
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

import httpx2

from skipcheck_tm1 import Tm1Version

from . import codec
from .codec import snake

REPO = Path(__file__).resolve().parents[4]
SCENARIOS = REPO / "spec" / "conformance" / "live"
TRAFFIC = REPO / "build" / "conformance-live"

# The session's own requests, which differ by when each client logs in and caches capabilities
_SESSION = ("ActiveSession", "$metadata", "Server?$select=ProductVersion")


def scenarios() -> list[Path]:
    return sorted(SCENARIOS.glob("*.json"))


class Traffic:
    """The requests a client sends, recorded by httpx2 event hooks, labelled with the step."""

    def __init__(self, root: str):
        self.root = root.rstrip("/") + "/"
        self.step = ""
        self.exchanges: list[dict[str, Any]] = []

    def record(self, response: httpx2.Response) -> None:
        request = response.request
        url = str(request.url.raw_path.decode())
        prefix = httpx2.URL(self.root).raw_path.decode()
        target = url[len(prefix) :] if url.startswith(prefix) else url
        if target.startswith(_SESSION):
            return
        headers = {k.lower(): v for k, v in request.headers.items()}
        self.exchanges.append(
            {"step": self.step, "request": codec.request_of(request.method, target, headers, request.content), "status": response.status_code}
        )

    async def arecord(self, response: httpx2.Response) -> None:
        self.record(response)

    def write(self, scenario: str, language: str, names: dict[str, str]) -> Path:
        out = TRAFFIC / language / f"{scenario}.jsonl"
        out.parent.mkdir(parents=True, exist_ok=True)
        lines = [{"scenario": scenario, "language": language, "names": names}, *self.exchanges]
        out.write_text("\n".join(json.dumps(x) for x in lines) + "\n", encoding="utf-8")
        return out


def names_for(scenario: dict[str, Any]) -> dict[str, str]:
    """A fresh name for each of the scenario's placeholders: letters, digits and _ only, so no
    client encodes them differently."""
    run = uuid.uuid4().hex[:8]
    return {n: f"tm1conf_{run}_{n}" for n in scenario["names"]}


def substitute(value: Any, names: dict[str, str]) -> Any:
    if isinstance(value, str):
        for placeholder, actual in names.items():
            value = value.replace("${" + placeholder + "}", actual)
        return value
    if isinstance(value, list):
        return [substitute(v, names) for v in value]
    if isinstance(value, dict):
        return {substitute(k, names): substitute(v, names) for k, v in value.items()}
    return value


def same(expected: Any, got: Any) -> bool:
    """Equal JSON at any depth: numbers by their value (11 as an int or a float), and a null as a
    property left out, as clients differ in whether a model's JSON keeps a null."""
    if expected is None or got is None:
        return expected is None and got is None
    if isinstance(expected, bool) or isinstance(got, bool):
        return expected is got
    if isinstance(expected, (int, float)) and isinstance(got, (int, float)):
        return expected == got
    if isinstance(expected, dict) and isinstance(got, dict):
        return all(same(expected.get(k), got.get(k)) for k in expected.keys() | got.keys())
    if isinstance(expected, list) and isinstance(got, list):
        return len(expected) == len(got) and all(same(e, g) for e, g in zip(expected, got))
    return expected == got


def check(expect: dict[str, Any], outcome: dict[str, Any]) -> str | None:
    """Why the outcome doesn't meet the expectation, or None if it does."""
    if "error" in expect:
        got = outcome.get("error")
        if got is None or any(got.get(k) != v for k, v in expect["error"].items()):
            return f"expected the error {expect['error']}, got {outcome}"
        return None
    if "error" in outcome:
        return f"failed with {outcome['error']}"
    result = outcome["result"]
    if expect.get("none") and result is not None:
        return f"expected nothing, got {result!r}"
    if "count" in expect and (not isinstance(result, list) or len(result) != expect["count"]):
        return f"expected {expect['count']} items, got {result!r}"
    if "fields" in expect:
        json_ = result.get("json") if isinstance(result, dict) and "$model" in result else result
        if isinstance(json_, dict) and "$model" in result:
            # A model's JSON leaves out its type, which its $model gives
            json_ = {"@odata.type": f"#ibm.tm1.api.v1.{result['$model']}", **json_}
        if not isinstance(json_, dict) or any(not same(v, json_.get(k)) for k, v in expect["fields"].items()):
            return f"expected the fields {expect['fields']}, got {result!r}"
    if "items" in expect:
        items = [i.get("json") if isinstance(i, dict) and "$model" in i else i for i in (result or [])]
        for field, values in expect["items"].items():
            if not same(values, [i.get(field) if isinstance(i, dict) else None for i in items]):
                return f"expected the items' {field} {values}, got {items!r}"
    return None


def save(step: dict[str, Any], outcome: dict[str, Any], names: dict[str, str]) -> str | None:
    """Keeps what a step's ``"save"`` names from its result, ``{"note": "ID"}``: the result's ``ID`` as the
    name ``note``, for the steps after it, such as an ID TM1 made up. Why it can't, or None."""
    for name, field in step.get("save", {}).items():
        result = outcome.get("result")
        json_ = result.get("json") if isinstance(result, dict) and "$model" in result else result
        value = json_.get(field) if isinstance(json_, dict) else None
        if value is None or isinstance(value, (dict, list)):
            return f"no {field} to save as {name} in {result!r}"
        names[name] = ("true" if value else "false") if isinstance(value, bool) else str(value)
    return None


def _outcome(call: Callable[[], Any], step: dict[str, Any] | None = None, kept: dict[str, Any] | None = None) -> Any:
    try:
        value = call()
        if inspect.isgenerator(value):
            value = list(value)
        _keep(step, kept, value)
        return {"result": codec.result(value)}
    except codec.Unknown:
        raise
    except Exception as exc:  # noqa: BLE001 - checked against the expectation
        return {"error": codec.error(exc)}


async def _aoutcome(call: Callable[[], Awaitable[Any]], step: dict[str, Any] | None = None, kept: dict[str, Any] | None = None) -> Any:
    try:
        value = call()
        if inspect.isawaitable(value):
            value = await value
        if hasattr(value, "__aiter__"):
            value = [v async for v in value]
        _keep(step, kept, value)
        return {"result": codec.result(value)}
    except codec.Unknown:
        raise
    except Exception as exc:  # noqa: BLE001 - checked against the expectation
        return {"error": codec.error(exc)}


def _unkept(step: dict[str, Any], kept: dict[str, Any]) -> bool:
    """Whether a step builds a ``$fromRead`` from a read no step kept: a ``finally`` step setting back
    what a step that didn't run would have read, which is skipped."""
    return any(isinstance(a, dict) and "$fromRead" in a and a.get("read") not in kept for a in step["args"])


def _keep(step: dict[str, Any] | None, kept: dict[str, Any] | None, value: Any) -> None:
    """Keeps a step's result, as read, under its ``"keep"`` name, for a ``$fromRead`` after it."""
    if step is not None and kept is not None and "keep" in step and value is not None:
        kept[step["keep"]] = value


def _arg(value: Any, kept: dict[str, Any]) -> Any:
    """A step's argument: a ``$fromRead`` one, ``{"$fromRead": "CubePatch", "read": "cube", "json": {...}}``,
    is ``CubePatch.builder(read)`` with the changes ``json`` names, through the setters a ``$patch``
    uses, from what a step before kept; any other is decoded."""
    if isinstance(value, dict) and "$fromRead" in value:
        if value.get("read") not in kept:
            raise ValueError(f"No read kept as {value.get('read')!r} for {value!r}")
        cls = getattr(codec.p, value["$fromRead"], None)
        if cls is None:
            raise codec.Unknown(f"no patch {value['$fromRead']}")
        return codec.build(cls, value.get("json", {}), kept[value["read"]])
    return codec.decode(value)


def _invoke(client: Any, step: dict[str, Any], names: dict[str, str], kept: dict[str, Any] | None = None) -> Callable[[], Any]:
    service = getattr(client, snake(step["service"]))
    method = getattr(service, snake(step["method"]))
    kwargs = {snake(p): _arg(substitute(a, names), kept or {}) for p, a in zip(step["params"], step["args"])}
    return lambda: method(**kwargs)


def applies(node: dict[str, Any], endpoints: Any, version: Tm1Version) -> bool:
    """Whether a scenario or a step applies to a server, as Java's ``ConformanceScenarios.applies``:
    every endpoint its ``requires`` names is in the server's ``$metadata``, and the server's product
    version is at least its ``tm1From`` and below its ``tm1Before``."""
    if any(endpoint not in endpoints for endpoint in node.get("requires", [])):
        return False
    if "tm1From" in node and version < Tm1Version.parse(node["tm1From"]):
        return False
    return "tm1Before" not in node or version < Tm1Version.parse(node["tm1Before"])


def _always(step: dict[str, Any]) -> bool:
    return True


def run(client: Any, traffic: Traffic, scenario: dict[str, Any], names: dict[str, str], applies: Callable[[dict[str, Any]], bool] = _always) -> list[str]:
    """Runs the scenario with a sync client; the steps whose expectation failed. Its ``finally`` steps
    always run. What a step saves joins ``names``, so the traffic written after it has TM1's made-up
    values as their placeholders too. A step ``applies`` refuses sends nothing and isn't checked."""
    failures = []
    kept: dict[str, Any] = {}
    try:
        for i, step in enumerate(scenario["steps"]):
            if not applies(step):
                continue
            traffic.step = f"steps[{i}]"
            outcome = _outcome(_invoke(client, step, names, kept), step, kept)
            why = check(substitute(step["expect"], names), outcome) or save(step, outcome, names)
            if why:
                failures.append(f"step {i} {step['service']}.{step['method']}: {why}")
                break
    finally:
        for i, step in enumerate(scenario.get("finally", [])):
            traffic.step = f"finally[{i}]"
            if applies(step) and not _unkept(step, kept):
                _outcome(_invoke(client, step, names, kept), step, kept)
    return failures


async def arun(
    client: Any, traffic: Traffic, scenario: dict[str, Any], names: dict[str, str], applies: Callable[[dict[str, Any]], bool] = _always
) -> list[str]:
    """Runs the scenario with an asyncio client; the steps whose expectation failed, as :func:`run`."""
    failures = []
    kept: dict[str, Any] = {}
    try:
        for i, step in enumerate(scenario["steps"]):
            if not applies(step):
                continue
            traffic.step = f"steps[{i}]"
            outcome = await _aoutcome(_invoke(client, step, names, kept), step, kept)
            why = check(substitute(step["expect"], names), outcome) or save(step, outcome, names)
            if why:
                failures.append(f"step {i} {step['service']}.{step['method']}: {why}")
                break
    finally:
        for i, step in enumerate(scenario.get("finally", [])):
            traffic.step = f"finally[{i}]"
            if applies(step) and not _unkept(step, kept):
                await _aoutcome(_invoke(client, step, names, kept), step, kept)
    return failures
