# SPDX-License-Identifier: Apache-2.0

"""The live conformance scenarios (spec/conformance/live) against a real TM1: a port of the Java
client's ConformanceScenarioTm1Test, run in both flavors. Each step must meet its expectation, and
what the client sent is written to build/conformance-live for scripts/compare-live-traffic.py to
compare with the other clients'."""

from __future__ import annotations

import asyncio
import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.aio import AsyncTm1Client

from ..conformance import scenarios as live
from .conftest import INSTALL, _builder, target


def _applies(capabilities: Any) -> Callable[[dict[str, Any]], bool]:
    """What the server has and is: a scenario or a step for one it isn't is skipped."""
    version = capabilities.product_version
    return lambda node: live.applies(node, capabilities.endpoints, version, INSTALL)


def _skip_unless(scenario: dict[str, Any], applies: Callable[[dict[str, Any]], bool], capabilities: Any) -> None:
    if not applies(scenario):
        pytest.skip(
            f"the scenario needs {scenario.get('requires')} {scenario.get('tm1From')} {scenario.get('tm1Before')} "
            f"{scenario.get('install')}; the server is {capabilities.product_version}, schema {capabilities.schema_version}, "
            f"install {INSTALL}"
        )


def _sync(traffic: live.Traffic, scenario: dict[str, Any], names: dict[str, str], verify: bool) -> list[str]:
    http = httpx2.Client(verify=verify, event_hooks={"response": [traffic.record]})
    with _builder(Tm1Client.builder()).http_client(http).build() as tm1:
        capabilities = tm1.session.capabilities()
        applies = _applies(capabilities)
        _skip_unless(scenario, applies, capabilities)
        return live.run(tm1, traffic, scenario, names, applies)


def _async(traffic: live.Traffic, scenario: dict[str, Any], names: dict[str, str], verify: bool) -> list[str]:
    async def go() -> list[str]:
        http = httpx2.AsyncClient(verify=verify, event_hooks={"response": [traffic.arecord]})
        async with _builder(AsyncTm1Client.builder()).http_client(http).build() as tm1:
            capabilities = await tm1.session.capabilities()
            applies = _applies(capabilities)
            _skip_unless(scenario, applies, capabilities)
            return await live.arun(tm1, traffic, scenario, names, applies)

    return asyncio.run(go())


@pytest.mark.parametrize("flavor", ["sync", "async"])
@pytest.mark.parametrize("path", live.scenarios(), ids=lambda p: p.stem)
def test_runs_the_scenario(path: Path, flavor: str) -> None:
    scenario = json.loads(path.read_text(encoding="utf-8"))
    names = live.names_for(scenario)
    url, _, _ = target()
    traffic = live.Traffic(url)
    verify = os.environ.get("TM1_VERIFY", "true").lower() != "false"
    failures = (_sync if flavor == "sync" else _async)(traffic, scenario, names, verify)
    traffic.write(scenario["name"], "python-" + flavor, names)
    assert not failures, "\n".join(failures)
