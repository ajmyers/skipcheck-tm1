"""Replays the Java client's recorded service calls (``spec/conformance/calls``) in both flavors:
the same call, with the same arguments, must send exactly the requests the Java client sent and,
given the answers it got, return the same result or fail the same way."""

from __future__ import annotations

import asyncio
import inspect
import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from tm1_client import Tm1Client, Tm1Session, auth
from tm1_client.aio import AsyncTm1Client, AsyncTm1Session

from . import conformance_codec as codec
from .conformance_codec import Unknown
from .conformance_codec import snake
from .conftest import BASE, CONFORMANCE, FakeTm1

CALLS = CONFORMANCE / "calls"
UNSUPPORTED = Path(__file__).resolve().parents[1] / "conformance" / "unsupported.txt"


def _unsupported() -> dict[str, str]:
    """Cases this client can't express, each with its reason: ``id  reason``."""
    out: dict[str, str] = {}
    if UNSUPPORTED.exists():
        for line in UNSUPPORTED.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                case, _, reason = line.partition(" ")
                out[case] = reason.strip()
    return out


def _cases() -> list[Any]:
    out = []
    for file in sorted(CALLS.glob("*.json")):
        fixture = json.loads(file.read_text(encoding="utf-8"))
        assert fixture["specVersion"] == codec.SPEC_VERSION, f"{file.name}: unknown specVersion {fixture['specVersion']}"
        for case in fixture["cases"]:
            for flavor in ("sync", "async"):
                out.append(pytest.param(fixture["session"], case, flavor, id=f"{case['id']}[{flavor}]"))
    return out


CASES = _cases()


def test_there_are_fixtures() -> None:
    assert CASES, f"no fixtures in {CALLS}"


def test_unsupported_lists_only_real_cases() -> None:
    ids = {p.values[1]["id"] for p in CASES}
    assert not set(_unsupported()) - ids, "unsupported.txt lists cases no fixture has"


def _flat(exchanges: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The exchanges, an unordered group's in its place."""
    return [x for entry in exchanges for x in entry.get("unordered", [entry])]


class Replay:
    """The fake TM1 answering each request with the recorded answer, in order; within an
    unordered group, with the answer of the group's equal request."""

    def __init__(self, session: dict[str, Any], case: dict[str, Any]):
        self.fake = FakeTm1()
        self.fake.product_version = session["productVersion"]
        self.entries = [list(entry["unordered"]) if "unordered" in entry else [entry] for entry in case["exchanges"]]
        self.expected: list[dict[str, Any]] = []
        self.fake.respond = self._answer

    def _next(self, request: httpx.Request) -> dict[str, Any] | None:
        while self.entries and not self.entries[0]:
            self.entries.pop(0)
        if not self.entries:
            return None
        group = self.entries[0]
        sent = codec.request_of(request.method, _target(request), _headers(request), request.content)
        match = next((x for x in group if codec.same_request(x["request"], sent)), group[0])
        group.remove(match)
        return match

    def _answer(self, request: httpx.Request) -> httpx.Response:
        exchange = self._next(request)
        if exchange is None:
            return httpx.Response(599, text=f"unscripted request {request.method} {request.url}")
        self.expected.append(exchange["request"])
        r = exchange["response"]
        headers = dict(r.get("headers", {}))
        if "json" in r:
            return httpx.Response(r["status"], content=json.dumps(r["json"]).encode(), headers=headers)
        if "text" in r:
            return httpx.Response(r["status"], content=r["text"].encode("utf-8"), headers=headers)
        if "bytes" in r:
            import base64

            return httpx.Response(r["status"], content=base64.b64decode(r["bytes"]), headers=headers)
        return httpx.Response(r["status"], headers=headers)

    def sent(self) -> list[dict[str, Any]]:
        return [codec.request_of(r.method, _target(r), _headers(r), r.content) for r in self.fake.requests]


def _target(request: httpx.Request) -> str:
    return request.url.raw_path.decode().removeprefix("/api/v1/")


def _headers(request: httpx.Request) -> dict[str, str]:
    return {k.lower(): v for k, v in request.headers.items()}


def _call(service: Any, case: dict[str, Any]) -> Any:
    method = getattr(service, snake(case["method"]), None)
    if method is None:
        raise Unknown(f"{type(service).__name__} has no {snake(case['method'])}")
    kwargs = {snake(name): codec.decode(arg) for name, arg in zip(case["params"], case["args"])}
    return method(**kwargs)


async def _drain(value: Any) -> Any:
    if inspect.isawaitable(value):
        value = await value
    if hasattr(value, "__aiter__"):
        value = [v async for v in value]
    return value


def _sync_drain(value: Any) -> Any:
    if inspect.isgenerator(value) or (hasattr(value, "__iter__") and hasattr(value, "__next__")):
        value = list(value)
    return value


@pytest.mark.parametrize(("session", "case", "flavor"), CASES)
def test_replays_the_java_call(session: dict[str, Any], case: dict[str, Any], flavor: str) -> None:
    reason = _unsupported().get(case["id"])
    if reason:
        pytest.skip(f"unsupported here: {reason}")
    replay = Replay(session, case)
    outcome: dict[str, Any]
    if flavor == "sync":
        client = Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=httpx.Client(transport=httpx.MockTransport(replay.fake.handler))))
        service = getattr(client, snake(case["service"]))
        try:
            outcome = {"result": codec.result(_sync_drain(_call(service, case)))}
        except Unknown:
            raise
        except Exception as exc:  # noqa: BLE001 - compared below
            outcome = {"error": codec.error(exc)}
    else:
        loop = asyncio.new_event_loop()
        http = httpx.AsyncClient(transport=httpx.MockTransport(replay.fake.handler))
        client = AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http))
        service = getattr(client, snake(case["service"]))
        try:
            outcome = {"result": codec.result(loop.run_until_complete(_drain(_call(service, case))))}
        except Unknown:
            raise
        except Exception as exc:  # noqa: BLE001 - compared below
            outcome = {"error": codec.error(exc)}
        finally:
            loop.run_until_complete(http.aclose())
            loop.close()

    # Each request against the recorded one it was answered with
    expected = replay.expected
    sent = replay.sent()
    for i, (want, got) in enumerate(zip(expected, sent)):
        assert codec.same_request(want, got), f"request {i + 1} differs from Java's:\n  Java:   {want}\n  Python: {got}"
    recorded = [x["request"] for x in _flat(case["exchanges"])]
    assert len(sent) == len(recorded), f"sent {len(sent)} requests, Java sent {len(recorded)}: {sent[len(recorded):] or recorded[len(sent):]}"

    if "error" in case:
        assert "error" in outcome, f"Java failed with {case['error']}, Python gave {outcome.get('result')!r}"
        assert codec.same_error(case["error"], outcome["error"]), f"Java failed with {case['error']}, Python with {outcome['error']}"
    else:
        assert "result" in outcome, f"Java gave {case['result']!r}, Python failed with {outcome['error']}"
        assert codec.normal(outcome["result"], drop_nulls=True) == codec.normal(case["result"], drop_nulls=True), (
            f"result differs:\n  Java:   {case['result']}\n  Python: {outcome['result']}"
        )
