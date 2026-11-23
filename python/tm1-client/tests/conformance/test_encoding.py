# SPDX-License-Identifier: Apache-2.0

"""The OData encoding every client must agree on (spec/conformance/odata-encoding.json), which
the Java client's ODataConformanceTest reads too."""

from __future__ import annotations

import json
from typing import Any

import pytest

from skipcheck_tm1.odata import ODataPath, literal

from ..conftest import CONFORMANCE

CASES = json.loads((CONFORMANCE / "odata-encoding.json").read_text(encoding="utf-8"))


def build(steps: list[list[Any]]) -> ODataPath:
    path: ODataPath | None = None
    for op, *args in steps:
        if op == "of":
            path = ODataPath.of(args[0])
        elif op == "functionImport":
            path = ODataPath.function_import(args[0], args[1])
        else:
            assert path is not None, "a path starts with of or functionImport"
            if op == "compoundKey":
                path = path.key(args[0])
            elif op in ("key", "nav", "cast", "action"):
                path = getattr(path, op)(args[0])
            elif op == "function":
                path = path.function(args[0], args[1])
            elif op in ("value", "count", "ref"):
                path = getattr(path, op)()
            else:
                raise ValueError(op)
    assert path is not None
    return path


@pytest.mark.parametrize("case", CASES["paths"], ids=lambda c: c["expect"])
def test_path(case: dict[str, Any]) -> None:
    assert str(build(case["steps"])) == case["expect"]


@pytest.mark.parametrize("case", CASES["literals"], ids=lambda c: c["expect"])
def test_literal(case: dict[str, Any]) -> None:
    value = float(case["value"]) if case.get("as") == "double" else case["value"]
    assert literal(value) == case["expect"]


def test_refuses_names_that_arent_identifiers() -> None:
    with pytest.raises(ValueError):
        ODataPath.of("Cubes('x')")
    with pytest.raises(ValueError):
        ODataPath.of("Cubes").key("a").key("b")


def test_follows_a_server_link_encoding_each_segment() -> None:
    assert str(ODataPath.link("MessageLogEntries/!delta('0[4096]38@11!19')")) == "MessageLogEntries/!delta('0%5B4096%5D38%4011!19')"
    with pytest.raises(ValueError):
        ODataPath.link("Cubes?$top=1")
