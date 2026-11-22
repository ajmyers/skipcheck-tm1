"""The live conformance scenarios (spec/conformance/live) against a real TM1, in both flavors: each
step must meet its expectation, and what the client sent is written to build/conformance-live
for scripts/compare-live-traffic.py to compare with the other clients'."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path

import httpx
import pytest

from tm1_client import Tm1Client
from tm1_client.aio import AsyncTm1Client

from .. import conformance_live as live
from .conftest import _builder, target


@pytest.mark.parametrize("path", live.scenarios(), ids=lambda p: p.stem)
def test_sync(path: Path) -> None:
    scenario = json.loads(path.read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    url, _, _ = target()
    traffic = live.Traffic(url)
    verify = os.environ.get("TM1_VERIFY", "true").lower() != "false"
    http = httpx.Client(verify=verify, event_hooks={"response": [traffic.record]})
    with _builder(Tm1Client.builder()).http_client(http).build() as tm1:
        failures = live.run(tm1, traffic, scenario, names)
    traffic.write(scenario["name"], "python-sync", names)
    assert not failures, "\n".join(failures)


@pytest.mark.parametrize("path", live.scenarios(), ids=lambda p: p.stem)
def test_async(path: Path) -> None:
    scenario = json.loads(path.read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    url, _, _ = target()
    traffic = live.Traffic(url)
    verify = os.environ.get("TM1_VERIFY", "true").lower() != "false"

    async def go() -> list[str]:
        http = httpx.AsyncClient(verify=verify, event_hooks={"response": [traffic.arecord]})
        async with _builder(AsyncTm1Client.builder()).http_client(http).build() as tm1:
            return await live.arun(tm1, traffic, scenario, names)

    failures = asyncio.run(go())
    traffic.write(scenario["name"], "python-async", names)
    assert not failures, "\n".join(failures)
