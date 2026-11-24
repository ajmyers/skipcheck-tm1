# SPDX-License-Identifier: Apache-2.0
"""The fields of the model that depart from TM1's $metadata: properties it doesn't declare (found
by probing TM1, tm1-probe-<schema>.json), and the overlay's departures and lenient dates. As the
Java DeparturesTest."""

from __future__ import annotations

import json

from skipcheck_tm1 import model as m
from skipcheck_tm1.version import Tm1Departure

from .conftest import SPEC


def test_reads_and_writes_back_the_properties_tm1_does_not_declare() -> None:
    process = m.Process.model_validate(
        {
            "Name": "Load",
            "UIData": "CubeAction=1511\f",
            "VariablesUIData": ["VarType=32\f"],
            "Code": "#region Prolog\nx = 1;\n#endregion",
        }
    )
    assert process.ui_data == "CubeAction=1511\f"
    assert process.variables_ui_data == ["VarType=32\f"]
    assert process.code is not None and process.code.startswith("#region Prolog")
    assert m.Subset.model_validate({"Name": "Top", "Alias": "Caption"}).alias == "Caption"

    patch = m.ProcessPatch.builder().ui_data(process.ui_data).variables_ui_data(process.variables_ui_data).build()
    assert patch.json() == {"UIData": "CubeAction=1511\f", "VariablesUIData": ["VarType=32\f"]}
    assert m.SubsetPatch.builder().alias("Caption").build().json() == {"Alias": "Caption"}


def test_tags_every_departure_the_spec_lists() -> None:
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    listed: dict[str, str] = {}
    for t in spec["types"]:
        for p in t["properties"]:
            kind = "UNDECLARED" if p.get("undeclared") else "TYPE" if p.get("departure") else "LENIENT_DATE" if p.get("lenientDate") else None
            if kind:
                listed[f"{t['name']}/{p['name']}"] = kind
    assert listed["Process/UIData"] == "UNDECLARED"
    assert listed["Subset/Alias"] == "UNDECLARED"
    assert listed["CAMSettings/ClientPingCAMPassport"] == "TYPE"
    assert listed["MessageLogEntry/TimeStamp"] == "LENIENT_DATE"

    tagged: dict[str, str] = {}
    for t in spec["types"]:
        own = {p["name"] for p in t["properties"]}
        cls = getattr(m, t["name"])
        for field in cls.model_fields.values():
            for meta in field.metadata:
                if isinstance(meta, Tm1Departure) and field.alias in own:
                    tagged[f"{t['name']}/{field.alias}"] = meta.kind.name
    assert tagged == listed
