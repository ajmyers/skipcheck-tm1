# SPDX-License-Identifier: Apache-2.0

"""HierarchyService against a real TM1: a port of the Java client's HierarchyServiceTm1Test."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1Client,
    Tm1PreconditionFailedError,
)
from skipcheck_tm1.model import (
    Hierarchy,
    HierarchyCreate,
    HierarchyPatch,
    Member,
)

from .conftest import CONFIGURED, REASON
from .support import (
    consolidated,
    dimension_of,
    edge,
    made,
    names,
    numeric,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def region(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A dimension: Total = North + South, and East, a second root."""
    with made(
        tm1,
        name,
        dimension_of(
            name,
            [consolidated("Total"), numeric("North"), numeric("South"), numeric("East")],
            [edge("Total", "North"), edge("Total", "South")],
        ),
    ) as d:
        yield d


def structure(name: str) -> HierarchyCreate:
    return (
        HierarchyCreate.builder()
        .name(name)
        .elements([consolidated("Total"), numeric("North"), numeric("South"), numeric("East")])
        .edges([edge("Total", "North"), edge("Total", "South")])
        .build()
    )


def roots(tm1: Tm1Client, d: str) -> list[str]:
    return names(tm1.hierarchies.root_members(d, d, ODataQuery.none().select(Member.NAME)))


def test_creates_lists_reads_and_deletes_a_hierarchy(tm1: Tm1Client, region: str) -> None:
    hierarchies, d = tm1.hierarchies, region
    assert hierarchies.create(d, structure("Alt")).name == "Alt"
    listed = names(hierarchies.list(d, ODataQuery.none().select(Hierarchy.NAME)))
    assert d in listed and "Alt" in listed
    assert len(hierarchies.get(d, "Alt", ODataQuery.none().expand(Hierarchy.ELEMENTS)).elements) == 4
    assert hierarchies.default_hierarchy(d).name == d
    assert hierarchies.dimension(d, "Alt").name == d
    hierarchies.delete(d, "Alt")
    assert "Alt" not in names(hierarchies.list(d))


def test_refuses_to_replace_elements_through_a_plain_update(tm1: Tm1Client, region: str) -> None:
    d = region
    with pytest.raises(ValueError, match="replace_structure"):
        tm1.hierarchies.update(d, d, HierarchyPatch.builder().elements([]).build())
    north = ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key("North")
    with pytest.raises(ValueError):
        tm1.hierarchies.update(d, d, HierarchyPatch.builder().bind_elements(north).build())
    with pytest.raises(ValueError):
        tm1.hierarchies.update(d, d, HierarchyPatch.builder().edges([edge("Total", "East")]).build(), 'W/"x"')


def test_replaces_the_structure_deleting_what_it_doesnt_list(tm1: Tm1Client, region: str) -> None:
    d = region
    tm1.hierarchies.replace_structure(d, d, [consolidated("Total"), numeric("North")], [edge("Total", "North")])
    elements = tm1.hierarchies.get(d, d, ODataQuery.none().expand(Hierarchy.ELEMENTS)).elements
    assert sorted(names(elements)) == ["North", "Total"]


def test_updates_only_when_the_etag_is_current_and_ignores_visible(tm1: Tm1Client, region: str) -> None:
    d = region
    etag = tm1.hierarchies.get(d, d).etag
    tm1.hierarchies.update(d, d, HierarchyPatch.builder().visible(False).build(), etag)
    assert tm1.hierarchies.get(d, d).visible is True, "TM1 accepts Visible in a PATCH and ignores it"
    tm1.hierarchies.replace_structure(d, d, [numeric("North")], [])
    with pytest.raises(Tm1PreconditionFailedError):
        tm1.hierarchies.update(d, d, HierarchyPatch.builder().build(), etag)


def test_reads_the_all_default_and_root_members(tm1: Tm1Client, region: str) -> None:
    d = region
    assert tm1.hierarchies.all_member(d, d) is None, "no all member with two roots: TM1 sends 204"
    assert tm1.hierarchies.default_member(d, d).name
    assert roots(tm1, d) == ["Total", "East"]
    tm1.hierarchies.replace_structure(d, d, [consolidated("Total"), numeric("North")], [edge("Total", "North")])
    assert tm1.hierarchies.all_member(d, d).name == "Total", "with one root, the all member is that root"


def test_saves_a_copy(tm1: Tm1Client, region: str) -> None:
    d = region
    assert tm1.hierarchies.save_as(d, d, "Copy").name == "Copy"
    assert tm1.hierarchies.save_as(d, d, "Copy", True, False).name == "Copy"
    assert len(tm1.hierarchies.get(d, "Copy", ODataQuery.none().expand(Hierarchy.ELEMENTS)).elements) == 4


def test_places_a_root_before_another_or_last_without_before(tm1: Tm1Client, region: str) -> None:
    d = region
    tm1.hierarchies.set_element(d, d, "East", "Total")
    assert roots(tm1, d) == ["East", "Total"]
    tm1.hierarchies.set_element(d, d, "East")
    assert roots(tm1, d) == ["Total", "East"], "no Before puts it last, though $metadata says first"
