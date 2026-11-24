# SPDX-License-Identifier: Apache-2.0

"""AttributeService and LocalizedAttributeService against a real TM1, for dimensions, hierarchies,
elements and subsets: a port of the Java client's AttributeServiceTm1Test."""

from __future__ import annotations

import uuid
from collections.abc import Iterator

import pytest

from skipcheck_tm1 import Tm1BadRequestError, Tm1Client, Tm1Request
from skipcheck_tm1._sync.services.attribute import AttributeOwner, AttributeSet
from skipcheck_tm1._sync.services.dimension import SubsetScope
from skipcheck_tm1.model import (
    AttributesPatch,
    AttributeType,
    DimensionCreate,
    ElementCreate,
    ElementType,
    HierarchyCreate,
    LocalizedAttributesCreate,
    SubsetCreate,
)

from .conftest import CONFIGURED, REASON
from .support import (
    french,
    french_value,
    label,
    string_attribute,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def d(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A dimension with North and South, and a public subset S."""
    tm1.dimensions.create(
        DimensionCreate.builder()
        .name(name)
        .hierarchies(
            [
                HierarchyCreate.builder()
                .name(name)
                .elements(
                    [
                        ElementCreate.builder().name("North").type(ElementType.NUMERIC).build(),
                        ElementCreate.builder().name("South").type(ElementType.NUMERIC).build(),
                    ]
                )
                .build()
            ]
        )
        .build()
    )
    try:
        tm1.subsets.create(name, name, SubsetScope.PUBLIC, SubsetCreate.builder().name("S").expression(f"{{[{name}].[{name}].[North]}}").build())
        yield name
    finally:
        tm1.dimensions.delete(name)


@pytest.mark.parametrize("kind", ["dimensions", "hierarchies", "elements", "subsets"])
def test_creates_lists_reads_and_deletes_attribute_definitions(tm1: Tm1Client, d: str, kind: str) -> None:
    attribute_set = {
        "dimensions": AttributeSet.dimensions(),
        "hierarchies": AttributeSet.hierarchies(d),
        "elements": AttributeSet.elements(d, d),
        "subsets": AttributeSet.subsets(d, d),
    }[kind]
    attribute = "Code " + uuid.uuid4().hex[:4]
    created = tm1.attributes.create(attribute_set, string_attribute(attribute))
    assert created.type is AttributeType.STRING
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        assert tm1.attributes.get(attribute_set, attribute).name == attribute
    finally:
        tm1.attributes.delete(attribute_set, attribute)
    assert attribute not in [a.name for a in tm1.attributes.list(attribute_set)]


@pytest.mark.parametrize("kind", ["dimension", "hierarchy", "element", "member", "subset"])
def test_translates_attribute_values(tm1: Tm1Client, d: str, kind: str) -> None:
    attribute_set = {
        "dimension": AttributeSet.dimensions(),
        "hierarchy": AttributeSet.hierarchies(d),
        "element": AttributeSet.elements(d, d),
        "member": AttributeSet.elements(d, d),
        "subset": AttributeSet.subsets(d, d),
    }[kind]
    owner = {
        "dimension": AttributeOwner.dimension(d),
        "hierarchy": AttributeOwner.hierarchy(d, d),
        "element": AttributeOwner.element(d, d, "North"),
        "member": AttributeOwner.element(d, d, "North"),
        "subset": AttributeOwner.subset(d, d, SubsetScope.PUBLIC, "S"),
    }[kind]
    # A member's translations are its element's, and read only
    reader = AttributeOwner.member(d, d, "North") if kind == "member" else owner
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    localized = tm1.localized_attributes
    try:
        localized.create(owner, french(attribute, "Nord"))
        assert "fr" in [t.locale_id for t in localized.list(reader)]
        assert french_value(tm1, reader, attribute) == "Nord"
        changed = french(attribute, "Le Nord")
        fr = owner.path().nav("LocalizedAttributes").key("fr")
        with pytest.raises(Tm1BadRequestError):  # a locale can't be created twice
            localized.create(owner, changed)
        with pytest.raises(Tm1BadRequestError):  # nor patched
            tm1.session.execute(Tm1Request.patch(fr, changed))
        with pytest.raises(Tm1BadRequestError):  # nor deleted
            tm1.session.execute(Tm1Request.delete(fr))
        assert french_value(tm1, reader, attribute) == "Nord"
    finally:
        tm1.attributes.delete(attribute_set, attribute)


def test_needs_the_attribute_defined_and_refuses_writes_through_a_member(tm1: Tm1Client, d: str) -> None:
    caption = LocalizedAttributesCreate.builder().locale_id("fr").attributes(AttributesPatch.builder().caption("Nord").build()).build()
    with pytest.raises(Tm1BadRequestError):  # Caption isn't an attribute until it's defined
        tm1.localized_attributes.create(AttributeOwner.element(d, d, "North"), caption)
    with pytest.raises(Tm1BadRequestError):  # POST not supported on a member's
        tm1.localized_attributes.create(AttributeOwner.member(d, d, "North"), caption)
