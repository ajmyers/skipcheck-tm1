# SPDX-License-Identifier: Apache-2.0

"""The endpoint coverage gate, as the Java client's CoverageGateTest: every
endpoint of the spec (spec/tm1-api.json) is implemented, excluded with a reason, or pending.

Every client in the repository runs this gate against the same inventory, so "full coverage"
means the same thing in each. Implemented is what ``@endpoint`` (and the generated API,
which uses it) registers, counted for the sync and the asyncio flavor separately. Summaries
go to build/tm1-coverage-<flavor>.txt.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from skipcheck_tm1.resources import implemented_endpoints

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = ROOT / "coverage" / "excluded.txt"
PENDING = ROOT / "coverage" / "pending.txt"


def lines(path: Path) -> list[str]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def excluded() -> dict[str, str]:
    out: dict[str, str] = {}
    for line in lines(EXCLUDED):
        endpoint, bar, reason = line.partition("|")
        assert bar and reason.strip(), f"{line!r} in {EXCLUDED.name} needs a reason: <endpoint> | <reason>"
        assert endpoint.strip() not in out, f"duplicate exclusion {line!r}"
        out[endpoint.strip()] = reason.strip()
    return out


@pytest.mark.parametrize("flavor", ["sync", "async"])
def test_every_endpoint_is_accounted_for(spec: dict[str, Any], flavor: str) -> None:
    """Each flavor separately: the asyncio API is held to the same inventory as the sync one."""
    endpoints = {e["id"] for e in spec["endpoints"]}
    implemented = set(implemented_endpoints(flavor))
    skip = excluded()
    pending = lines(PENDING)

    untriaged = endpoints - implemented - set(skip) - set(pending)
    assert not untriaged, f"endpoints not implemented, excluded or pending: add each to {PENDING.name} or {EXCLUDED.name}: {sorted(untriaged)}"

    unknown = (set(pending) | set(skip) | implemented) - endpoints
    assert not unknown, f"endpoints the spec doesn't define (typos, or removed endpoints): {sorted(unknown)}"

    still_pending = implemented & set(pending)
    assert not still_pending, f"implemented endpoints still in {PENDING.name}: take them off: {sorted(still_pending)}"

    assert not (set(pending) & set(skip)), "endpoints both pending and excluded"
    assert len(pending) == len(set(pending)), f"duplicate lines in {PENDING.name}"

    report = ROOT / "build" / f"tm1-coverage-{flavor}.txt"
    report.parent.mkdir(exist_ok=True)
    report.write_text(
        f"TM1 REST API coverage (Python, {flavor}), schema {spec['source']['schemaVersion']}\n\n"
        f"Endpoints:   {len(endpoints)}\nImplemented: {len(implemented & endpoints)}\n"
        f"Excluded:    {len(skip)}\nPending:     {len(pending)}\n",
        encoding="utf-8",
    )


def test_counts_every_kind_of_endpoint(spec: dict[str, Any]) -> None:
    endpoints = {e["id"] for e in spec["endpoints"]}
    assert {
        "EntitySet Cubes",
        "Singleton Server",
        "ActionImport ExecuteMDX",
        "FunctionImport TailMessageLog",
        "Action Cube/Reserve",
        "Function Cube/DimensionsStorageOrder",
        "Navigation Cube/Views",
        "Navigation ViewTitle/Subset",
    } <= endpoints
    # The same count as the Java client's CoverageGateTest
    assert len(endpoints) == 242
