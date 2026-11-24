# SPDX-License-Identifier: Apache-2.0

"""What every generated create and patch builder offers, which every client's builders must match
(spec/conformance/builders.json): a port of the Java client's BuilderManifestTest. Each builder is
driven to find what it offers: a setter on a builder of its own gives the name it writes, and a
property is required when a builder with every other setter called refuses to build."""

from __future__ import annotations

import inspect
import json
from pathlib import Path
from typing import Any

from skipcheck_tm1 import ODataPath
from skipcheck_tm1._generated import patches

ROOT = Path(__file__).resolve().parents[3]
SPEC = json.loads((ROOT / "spec" / "tm1-api.json").read_text(encoding="utf-8"))
MANIFEST = json.loads((ROOT / "spec" / "conformance" / "builders.json").read_text(encoding="utf-8"))
BIND = "@odata.bind"


def bare(name: str) -> str:
    return name.removesuffix(BIND)


def setters(builder: Any) -> list[str]:
    return sorted(
        name for name, member in inspect.getmembers(type(builder), inspect.isfunction) if not name.startswith("_") and name not in ("build", "property")
    )


def call(builder: Any, setter: str) -> None:
    getattr(builder, setter)(ODataPath.of("x") if setter.startswith("bind_") else None)


def describe(patch: Any) -> dict[str, Any]:
    base = dict(patch.builder()._values)
    wrote: dict[str, str] = {}
    for setter in setters(patch.builder()):
        b = patch.builder()
        call(b, setter)
        new = [k for k in b._values if k not in base]
        assert len(new) == 1, f"{patch.__name__}.{setter} writes one property: {new}"
        wrote[setter] = new[0]
    required = []
    for group in sorted({bare(k) for k in wrote.values()}):
        b = patch.builder()
        for setter, key in wrote.items():
            if bare(key) != group:
                call(b, setter)
        try:
            b.build()
        except ValueError:
            required.append(group)
    return {
        "odataType": base.get("@odata.type"),
        "open": callable(getattr(patch.builder(), "property", None)),
        "required": required,
        "setters": sorted(wrote.values()),
    }


def test_every_builder_offers_what_the_manifest_lists() -> None:
    names = sorted(t["name"] + suffix for t in SPEC["types"] for suffix in ("Patch", "Create") if hasattr(patches, t["name"] + suffix))
    expected = MANIFEST["builders"]
    assert names == sorted(expected), "the builders this client generates"
    wrong = [f"{n}: {describe(getattr(patches, n))} != {expected[n]}" for n in names if describe(getattr(patches, n)) != expected[n]]
    assert wrong == []
