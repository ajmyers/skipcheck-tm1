"""The generated model: reading what TM1 sends, and writing only what was set.

The payloads are the Java client's (tm1-client/src/test/resources/payloads), captured from a
real TM1: both clients must read them the same and write them back unchanged.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any

import pytest
from pydantic import TypeAdapter

from tm1_client import Bind
from tm1_client._generated import model as m

from .conftest import PAYLOADS

# The same payloads and types as the Java client's PayloadRoundTripTest
ROUND_TRIPS = {
    "server": m.Server,
    "configuration": m.Configuration,
    "active-session": m.Session,
    "users": m.User,
    "groups": m.Group,
    "dimension": m.Dimension,
    "members": m.Member,
    "cube": m.Cube,
    "views": m.View,
    "process": m.Process,
    "chore": m.Chore,
    "threads": m.Thread,
    "cellset": m.Cellset,
}

# Properties TM1 sends that $metadata doesn't declare, by payload (none, as for Java)
UNKNOWN: dict[str, set[str]] = {}

_DURATION = TypeAdapter(timedelta)
_DATETIME = TypeAdapter(datetime)


def same(a: Any, b: Any) -> bool:
    if a == b:
        return True
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool):
        return float(a) == float(b)
    if isinstance(a, str) and isinstance(b, str):
        try:
            return _DATETIME.validate_python(a) == _DATETIME.validate_python(b)
        except ValueError:
            pass
        try:
            return _DURATION.validate_python(a) == _DURATION.validate_python(b)
        except ValueError:
            return False
    return False


def compare(original: Any, written: Any, path: str, differences: list[str], unknown: set[str]) -> None:
    if isinstance(original, dict):
        if not isinstance(written, dict):
            differences.append(f"{path}: {original} became {written}")
            return
        for name, value in original.items():
            at = f"{path}/{name}"
            if name.startswith("@odata.") and name not in ("@odata.etag", "@odata.type"):
                continue
            if value is None:
                if written.get(name) is not None:
                    differences.append(f"{at}: null became {written[name]}")
            elif name not in written:
                unknown.add(at)
            else:
                compare(value, written[name], at, differences, unknown)
        for name in written:
            if name not in original and name != "@odata.type":
                differences.append(f"{path}/{name}: written, but TM1 didn't send it")
    elif isinstance(original, list):
        if not isinstance(written, list) or len(written) != len(original):
            differences.append(f"{path}: {original} became {written}")
            return
        for i, (a, b) in enumerate(zip(original, written)):
            compare(a, b, f"{path}[{i}]", differences, unknown)
    elif not same(original, written):
        differences.append(f"{path}: {original!r} became {written!r}")


@pytest.mark.parametrize("payload", sorted(ROUND_TRIPS))
def test_reads_and_writes_back_what_tm1_sent(payload: str) -> None:
    cls = ROUND_TRIPS[payload]
    original = json.loads((PAYLOADS / "11.8.35" / f"{payload}.json").read_text(encoding="utf-8"))
    items = original["value"] if isinstance(original.get("value"), list) else [original]
    assert items
    differences: list[str] = []
    unknown: set[str] = set()
    for i, item in enumerate(items):
        value = cls.model_validate(item)
        assert isinstance(value, cls)
        # ETags are read, never written in a body (they go in If-Match)
        compare(_without_etags(item), value.to_odata(), f"[{i}]", differences, unknown)
    assert differences == []
    assert unknown == UNKNOWN.get(payload, set())


def _without_etags(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _without_etags(v) for k, v in value.items() if k != "@odata.etag"}
    if isinstance(value, list):
        return [_without_etags(v) for v in value]
    return value


def test_chooses_a_subtype_by_odata_type() -> None:
    views = json.loads((PAYLOADS / "11.8.35" / "views.json").read_text(encoding="utf-8"))["value"]
    read = [m.View.model_validate(v) for v in views]
    kinds = {type(v) for v in read}
    assert kinds <= {m.NativeView, m.MDXView}
    assert m.NativeView in kinds or m.MDXView in kinds
    # Alias-qualified and unknown types too
    assert type(m.View.model_validate({"@odata.type": "#tm1.MDXView", "Name": "a"})) is m.MDXView
    unknown = m.View.model_validate({"@odata.type": "#tm1.FutureView", "Name": "a"})
    assert type(unknown) is m.View and unknown.name == "a"


def test_writes_only_what_was_set_explicit_nulls_included() -> None:
    assert m.Cube(rules="SKIPCHECK;").to_odata() == {"Rules": "SKIPCHECK;"}
    assert m.Cube(drillthrough_rules=None).to_odata() == {"DrillthroughRules": None}
    assert m.Cube().to_odata() == {}
    # TM1's names work as well as the Python ones
    assert m.Cube(Rules="x").to_odata() == {"Rules": "x"}


def test_binds_existing_entities() -> None:
    cube = m.Cube(name="c", dimensions=[Bind("Dimensions('Region')"), Bind("Dimensions('Year')")])
    assert cube.to_odata() == {"Name": "c", "Dimensions@odata.bind": ["Dimensions('Region')", "Dimensions('Year')"]}


def test_sends_a_subtypes_odata_type_first() -> None:
    view = m.NativeView(name="v", suppress_empty_rows=True)
    written = view.to_odata()
    assert list(written)[0] == "@odata.type"
    assert written == {"@odata.type": "#ibm.tm1.api.v1.NativeView", "Name": "v", "SuppressEmptyRows": True}


def test_open_types_keep_undeclared_properties() -> None:
    attributes = m.Attributes.model_validate({"Caption": "Sales", "Format": "#,##0"})
    assert (attributes.model_extra or {}).get("Format") == "#,##0"
    assert m.Attributes(**{"Format": "0.0"}).to_odata() == {"Format": "0.0"}


def test_an_unknown_enum_value_reads_as_the_fallback_and_is_never_sent() -> None:
    privilege = m.AccessPrivilege("SomethingNew")
    assert privilege is m.AccessPrivilege.UNKNOWN and not privilege.known
    assert m.AccessPrivilege.READ.odata_literal() == "tm1.AccessPrivilege'Read'"
    with pytest.raises(ValueError):
        m.AccessPrivilege.UNKNOWN.odata_literal()


def test_reads_departures_and_lenient_dates_from_the_overlay() -> None:
    # CAMSettings/ClientPingCAMPassport: a number in one place, an ISO duration in another
    assert m.CAMSettings.model_validate({"ClientPingCAMPassport": "PT15M"}).client_ping_cam_passport == "PT15M"
    assert m.CAMSettings.model_validate({"ClientPingCAMPassport": 900}).client_ping_cam_passport == 900
    # MessageLogEntry/TimeStamp: 0000-00-00T00:00Z reads as None
    entry = m.MessageLogEntry.model_validate({"TimeStamp": "0000-00-00T00:00Z", "Message": "x"})
    assert entry.time_stamp is None


def test_durations_read_tm1s_way_and_write_iso() -> None:
    chore = m.Chore.model_validate({"Frequency": "P1DT00H00M00S"})
    assert chore.frequency == timedelta(days=1)
    assert m.Chore(frequency=timedelta(hours=24)).to_odata() == {"Frequency": "P1D"}
    assert m.Chore(frequency=timedelta(minutes=90)).to_odata() == {"Frequency": "PT1H30M"}
    assert m.Chore(frequency=timedelta(0)).to_odata() == {"Frequency": "PT0S"}


def test_field_constants_name_tm1s_properties() -> None:
    assert m.Cube.Fields.NAME == "Name"
    assert m.Cube.Fields.DRILLTHROUGH_RULES == "DrillthroughRules"
    assert m.Cube.Fields.VIEWS == "Views"
