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
        if not isinstance(json_, dict) or any(json_.get(k) != v for k, v in expect["fields"].items()):
            return f"expected the fields {expect['fields']}, got {result!r}"
    if "items" in expect:
        items = [i.get("json") if isinstance(i, dict) and "$model" in i else i for i in (result or [])]
        for field, values in expect["items"].items():
            if [i.get(field) if isinstance(i, dict) else None for i in items] != values:
                return f"expected the items' {field} {values}, got {items!r}"
    return None


def _outcome(call: Callable[[], Any]) -> Any:
    try:
        value = call()
        if inspect.isgenerator(value):
            value = list(value)
        return {"result": codec.result(value)}
    except codec.Unknown:
        raise
    except Exception as exc:  # noqa: BLE001 - checked against the expectation
        return {"error": codec.error(exc)}


async def _aoutcome(call: Callable[[], Awaitable[Any]]) -> Any:
    try:
        value = call()
        if inspect.isawaitable(value):
            value = await value
        if hasattr(value, "__aiter__"):
            value = [v async for v in value]
        return {"result": codec.result(value)}
    except codec.Unknown:
        raise
    except Exception as exc:  # noqa: BLE001 - checked against the expectation
        return {"error": codec.error(exc)}


def _invoke(client: Any, step: dict[str, Any], names: dict[str, str]) -> Callable[[], Any]:
    service = getattr(client, snake(step["service"]))
    method = getattr(service, snake(step["method"]))
    kwargs = {snake(p): codec.decode(substitute(a, names)) for p, a in zip(step["params"], step["args"])}
    return lambda: method(**kwargs)


def run(client: Any, traffic: Traffic, scenario: dict[str, Any], names: dict[str, str]) -> list[str]:
    """Runs the scenario with a sync client; the steps whose expectation failed."""
    failures = []
    try:
        for i, step in enumerate(scenario["steps"]):
            traffic.step = f"steps[{i}]"
            why = check(substitute(step["expect"], names), _outcome(_invoke(client, step, names)))
            if why:
                failures.append(f"step {i} {step['service']}.{step['method']}: {why}")
                break
    finally:
        for i, step in enumerate(scenario.get("finally", [])):
            traffic.step = f"finally[{i}]"
            _outcome(_invoke(client, step, names))
    return failures


async def arun(client: Any, traffic: Traffic, scenario: dict[str, Any], names: dict[str, str]) -> list[str]:
    """Runs the scenario with an asyncio client; the steps whose expectation failed."""
    failures = []
    try:
        for i, step in enumerate(scenario["steps"]):
            traffic.step = f"steps[{i}]"
            why = check(substitute(step["expect"], names), await _aoutcome(_invoke(client, step, names)))
            if why:
                failures.append(f"step {i} {step['service']}.{step['method']}: {why}")
                break
    finally:
        for i, step in enumerate(scenario.get("finally", [])):
            traffic.step = f"finally[{i}]"
            await _aoutcome(_invoke(client, step, names))
    return failures
