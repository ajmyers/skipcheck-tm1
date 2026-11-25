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
