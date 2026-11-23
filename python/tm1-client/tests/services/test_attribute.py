# SPDX-License-Identifier: Apache-2.0

"""The attribute services (definitions per AttributeSet, translations per AttributeOwner), in
both flavors, against a fake TM1."""

from __future__ import annotations

import json
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import ODataQuery
from skipcheck_tm1.aio.services.attribute import AttributeOwner, AttributeSet
from skipcheck_tm1.aio.services.dimension import SubsetScope
from skipcheck_tm1.model import AttributeDefinition, AttributeDefinitionPatch, AttributesPatch, AttributeType, LocalizedAttributesPatch

from ..conftest import Api, FakeTm1, target

H = "Dimensions('Region')/Hierarchies('Region')"


def body(request: httpx2.Request) -> Any:
    return json.loads(request.content) if request.content else None


class _Scope:
    """Stands for a ViewScope: anything with navigation()."""

    def navigation(self) -> str:
        return "PrivateViews"


SETS = [
    (AttributeSet.dimensions(), "DimensionAttributes"),
    (AttributeSet.hierarchies("Region"), "Dimensions('Region')/HierarchyAttributes"),
    (AttributeSet.elements("Region", "Region"), f"{H}/ElementAttributes"),
    (AttributeSet.subsets("Region", "Region"), f"{H}/SubsetAttributes"),
    (AttributeSet.cubes(), "CubeAttributes"),
    (AttributeSet.views("Sales"), "Cubes('Sales')/ViewAttributes"),
    (AttributeSet.processes(), "ProcessAttributes"),
    (AttributeSet.chores(), "ChoreAttributes"),
]

OWNERS = [
    (AttributeOwner.dimension("Region"), "Dimensions('Region')"),
    (AttributeOwner.hierarchy("Region", "Region"), H),
    (AttributeOwner.element("Region", "Region", "North"), f"{H}/Elements('North')"),
    (AttributeOwner.member("Region", "Region", "North"), f"{H}/Members('North')"),
    (AttributeOwner.subset("Region", "Region", SubsetScope.SESSION, "s"), f"{H}/SessionSubsets('s')"),
    (AttributeOwner.cube("Sales"), "Cubes('Sales')"),
    (AttributeOwner.view("Sales", _Scope(), "Mine"), "Cubes('Sales')/PrivateViews('Mine')"),
    (AttributeOwner.view("Sales", "Views", "Default"), "Cubes('Sales')/Views('Default')"),
    (AttributeOwner.process("Load"), "Processes('Load')"),
    (AttributeOwner.chore("Nightly"), "Chores('Nightly')"),
]


@pytest.mark.parametrize("attribute_set,path", SETS, ids=[p for _, p in SETS])
def test_attribute_definitions_of_every_set(api: Api, fake: FakeTm1, attribute_set: AttributeSet, path: str) -> None:
    assert str(attribute_set) == path
    fake.respond = lambda r: {"value": [{"Name": "Manager", "Type": "String"}]}
    found = api.run(api.client.attributes.list(attribute_set, ODataQuery.none().select(AttributeDefinition.NAME)))
    assert found[0].type is AttributeType.STRING
    assert target(fake.last) == f"GET {path}?$select=Name"
    fake.respond = lambda r: {"Name": "Manager"}
    api.run(api.client.attributes.get(attribute_set, "Manager"))
    assert target(fake.last) == f"GET {path}('Manager')"
    api.run(api.client.attributes.create(attribute_set, AttributeDefinitionPatch.builder().name("Manager").type(AttributeType.STRING).build()))
    assert (target(fake.last), body(fake.last)) == (f"POST {path}", {"Name": "Manager", "Type": "String"})
    fake.respond = lambda r: None
    api.run(api.client.attributes.delete(attribute_set, "Manager"))
    assert target(fake.last) == f"DELETE {path}('Manager')"


@pytest.mark.parametrize("owner,path", OWNERS, ids=[p for _, p in OWNERS])
def test_translations_of_every_owner(api: Api, fake: FakeTm1, owner: AttributeOwner, path: str) -> None:
    assert str(owner) == path
    fake.respond = lambda r: {"value": [{"LocaleID": "fr"}]}
    assert api.run(api.client.localized_attributes.list(owner))[0].locale_id == "fr"
    assert target(fake.last) == f"GET {path}/LocalizedAttributes"
    fake.respond = lambda r: {"LocaleID": "fr", "Attributes": {"Caption": "Nord", "Long Name": "Région Nord"}}
    french = api.run(api.client.localized_attributes.get(owner, "fr"))
    assert target(fake.last) == f"GET {path}/LocalizedAttributes('fr')"
    # Attributes other than Caption come back as undeclared (open type) properties
    assert french.attributes.caption == "Nord"
    assert french.attributes.model_extra["Long Name"] == "Région Nord"
    patch = LocalizedAttributesPatch.builder().locale_id("fr").attributes(AttributesPatch.builder().property("Long Name", "Région Nord").build()).build()
    api.run(api.client.localized_attributes.create(owner, patch))
    assert (target(fake.last), body(fake.last)) == (
        f"POST {path}/LocalizedAttributes",
        {"LocaleID": "fr", "Attributes": {"Long Name": "Région Nord"}},
    )


def test_owners_and_sets_are_values() -> None:
    assert AttributeOwner.cube("a") == AttributeOwner.cube("a")
    assert AttributeSet.cubes() == AttributeSet.cubes()
    assert AttributeOwner.cube("a") != AttributeOwner.process("a")
    with pytest.raises(TypeError):
        AttributeOwner.cube(None)  # type: ignore[arg-type]
