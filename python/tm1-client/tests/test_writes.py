# SPDX-License-Identifier: Apache-2.0
"""The create types and builder(read): a write made from a read entity holds only what TM1 applies, by
the probe (tm1-probe-<schema>.json's writes). As the Java WritesTest."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import model as m
from skipcheck_tm1.odata import ODataPath


def test_creates_from_a_read_entity_what_a_post_applies() -> None:
    read = m.Element.model_validate({"Name": "North", "Type": "Consolidated", "Index": 4, "Level": 1, "Attributes": {"Caption": "North"}})
    create = m.ElementCreate.builder(read).build().json()
    # TM1 ignores Index, Level and Attributes in a create: they aren't offered, nor copied
    assert set(create) == {"Name", "Type"}
    assert create["Type"] == "Consolidated"
    # A builder, to change what's copied
    assert m.ElementCreate.builder(read).name("South").build().json()["Name"] == "South"


def test_patches_from_a_read_entity_leaving_out_what_a_patch_rejects_or_appends() -> None:
    read = m.Chore.model_validate(
        {
            "Name": "Nightly",
            "Active": True,
            "Frequency": "P1DT00H00M00S",
            "Attributes": {"Caption": "Nightly"},
            "Tasks": [{"Step": 0, "Parameters": [{"Name": "pValue", "Value": "1"}]}],
        }
    )
    # A PATCH refuses Attributes, and appends Tasks: neither is copied
    assert set(m.ChorePatch.builder(read).build().json()) == {"Name", "Active", "Frequency"}
    # A create ignores Attributes too (they're written through the attribute cubes). Tasks is a
    # navigation: none is copied, as a task's Process would have to be bound again
    create = m.ChoreCreate.builder(read).build().json()
    assert "Frequency" in create and "Attributes" not in create and "Tasks" not in create


def test_refuses_a_create_without_what_tm1_needs() -> None:
    with pytest.raises(ValueError, match="Dimensions"):
        m.CubeCreate.builder().name("Sales").build()
    dimensions = ODataPath.of("Dimensions")
    create = m.CubeCreate.builder().name("Sales").bind_dimensions(dimensions.key("Region"), dimensions.key("Month")).build()
    assert len(create.json()["Dimensions@odata.bind"]) == 2
