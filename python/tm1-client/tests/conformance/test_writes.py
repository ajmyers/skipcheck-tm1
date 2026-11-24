# SPDX-License-Identifier: Apache-2.0
"""The writes made from what was read that every client must produce identically
(spec/conformance/writes.json), as the Java WritesConformanceTest."""

from __future__ import annotations

import json
from typing import Any

from skipcheck_tm1 import model as m

from ..conftest import SPEC

CASES: list[dict[str, Any]] = json.loads((SPEC.parent / "conformance" / "writes.json").read_text(encoding="utf-8"))["cases"]


def test_every_case_writes_what_it_expects() -> None:
    wrong: list[str] = []
    for case in CASES:
        read = getattr(m, case["type"]).model_validate(case["read"])
        write = getattr(m, case["type"] + ("Create" if case["write"] == "create" else "Patch"))
        try:
            got = write.builder(read).build().json()
        except Exception as e:  # noqa: BLE001 - every client's own exception: the message is what's compared
            if "error" not in case or case["error"] not in str(e):
                wrong.append(f"{case['id']}: failed with {e}")
            continue
        if "error" in case:
            wrong.append(f"{case['id']}: built {got}, but should fail with {case['error']}")
        elif json.loads(json.dumps(got)) != case["expect"]:
            wrong.append(f"{case['id']}: wrote {got}, expected {case['expect']}")
    assert CASES
    assert wrong == []
