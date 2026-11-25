# SPDX-License-Identifier: Apache-2.0

"""A scenario's ``$patch`` goes through the generated setters (#354): what a caller can't build, a
scenario can't send. As the Java client's PatchBuildersTest."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import ODataPath
from skipcheck_tm1._generated import patches as p

from .codec import build


def test_builds_a_write_with_its_setters_and_binds_each_path_once() -> None:
    built = build(p.CubeCreate, {"Name": "c", "Rules": None, "Dimensions@odata.bind": ["Dimensions('%7DClients')"]})
    made = p.CubeCreate.builder().name("c").rules(None).bind_dimensions(ODataPath.of("Dimensions").key("}Clients")).build()
    assert built == made
    assert built.json()["Dimensions@odata.bind"] == ["Dimensions('%7DClients')"]


def test_refuses_a_key_no_setter_writes() -> None:
    with pytest.raises(ValueError, match="CubeCreate has no setter for Nope"):
        build(p.CubeCreate, {"Name": "c", "Nope": 1})


def test_builds_a_nested_value_with_its_own_builder_and_a_roots_by_subtype() -> None:
    built = build(p.ServerSettingsPatch, {"EncryptionAtRest": {"@odata.type": "#ibm.tm1.api.v1.VaultSettings", "KeyRef": "k"}})
    value = built.json()["EncryptionAtRest"]
    # Only VaultSettingsPatch's builder sends the @odata.type: the root's leaves it out
    value = value.json() if hasattr(value, "json") else value
    assert value == {"@odata.type": "#ibm.tm1.api.v1.VaultSettings", "KeyRef": "k"}


def test_sets_an_open_types_undeclared_property_by_name() -> None:
    built = build(p.EncryptionAtRestProviderSettingsPatch, {"KeyRef": "k", "Extra": "x"})
    assert built.json()["Extra"] == "x"


def test_takes_a_bindings_path_as_written_encoded() -> None:
    # A key's %2F is a slash in the key, not between segments
    built = build(p.CubeCreate, {"Name": "c", "Dimensions@odata.bind": ["Dimensions('a%2Fb')"]})
    assert built.json()["Dimensions@odata.bind"] == ["Dimensions('a%2Fb')"]
    with pytest.raises(ValueError, match="binds a list of paths"):
        build(p.CubeCreate, {"Name": "c", "Dimensions@odata.bind": "Dimensions('d')"})


def test_refuses_a_declared_key_no_setter_writes_on_an_open_type() -> None:
    # NativeView is open, but declares Name, which its patch doesn't offer
    with pytest.raises(ValueError, match="NativeViewPatch has no setter for Name"):
        build(p.NativeViewPatch, {"@odata.type": "#ibm.tm1.api.v1.NativeView", "Name": "renamed"})


def test_refuses_a_value_that_doesnt_read_back_as_written() -> None:
    with pytest.raises(ValueError, match="doesn't read back as written"):
        build(p.ChoreCreate, {"Name": "c", "ExecutionMode": "Nope"})
    with pytest.raises(ValueError, match="doesn't send @odata.type"):
        build(p.CubeCreate, {"@odata.type": "#ibm.tm1.api.v1.Cube", "Name": "c"})


def test_keeps_a_date_times_offset() -> None:
    built = build(p.ChoreCreate, {"Name": "c", "StartTime": "2026-03-04T05:06:07.5+02:00"})
    assert built.json()["StartTime"] == "2026-03-04T05:06:07.5+02:00"
