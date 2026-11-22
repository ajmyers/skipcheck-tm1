"""The dimension services against a real TM1: ports of the Java client's
DimensionServiceTm1Test, HierarchyServiceTm1Test, ElementServiceTm1Test,
LevelAndMemberServiceTm1Test and SubsetServiceTm1Test. Skipped unless TM1_URL is set."""

from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

import pytest

from tm1_client import (
    Filter,
    ODataPath,
    ODataQuery,
    Tm1BadRequestError,
    Tm1Client,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
    Tm1Request,
)
from tm1_client._sync.services.dimension import SubsetScope
from tm1_client.model import (
    Dimension,
    DimensionPatch,
    EdgePatch,
    Element,
    ElementPatch,
    ElementType,
    Hierarchy,
    HierarchyPatch,
    Member,
    SubsetPatch,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def numeric(name: str) -> ElementPatch:
    return ElementPatch.builder().name(name).type(ElementType.NUMERIC).build()


def consolidated(name: str) -> ElementPatch:
    return ElementPatch.builder().name(name).type(ElementType.CONSOLIDATED).build()


def string(name: str) -> ElementPatch:
    return ElementPatch.builder().name(name).type(ElementType.STRING).build()


def edge(parent: str, component: str) -> EdgePatch:
    return EdgePatch.builder().parent_name(parent).component_name(component).weight(1.0).build()


def dimension_of(name: str, elements: list[ElementPatch], edges: list[EdgePatch] | None = None) -> DimensionPatch:
    hierarchy = HierarchyPatch.builder().name(name).elements(elements)
    if edges:
        hierarchy = hierarchy.edges(edges)
    return DimensionPatch.builder().name(name).hierarchies([hierarchy.build()]).build()


def names(items: list) -> list[str]:  # type: ignore[type-arg]
    return [i.name for i in items]


@contextmanager
def made(tm1: Tm1Client, name: str, patch: DimensionPatch) -> Iterator[str]:
    tm1.dimensions.create(patch)
    try:
        yield name
    finally:
        tm1.dimensions.delete(name)


# DimensionServiceTm1Test


def test_creates_reads_lists_and_deletes_a_dimension(tm1: Tm1Client, name: str) -> None:
    dimensions = tm1.dimensions
    assert dimensions.create(dimension_of(name, [numeric("North"), numeric("South")])).name == name
    read = dimensions.get(name, ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.expand(Hierarchy.ELEMENTS)))
    assert read.etag
    assert len(read.hierarchies) == 1
    assert sorted(names(read.hierarchies[0].elements)) == ["North", "South"]
    listed = dimensions.list(ODataQuery.none().select(Dimension.NAME).filter(Filter.starts_with(Dimension.NAME, name)))
    assert names(listed) == [name]
    dimensions.delete(name)
    with pytest.raises(Tm1NotFoundError):
        dimensions.get(name)


def test_updates_a_dimension_only_when_the_etag_is_current(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        etag = tm1.dimensions.get(d).etag
        patch = DimensionPatch.builder().name(d).build()
        tm1.dimensions.update(d, patch, etag)
        tm1.dimensions.add_all_leaves_hierarchy(d)
        assert tm1.dimensions.get(d).etag != etag, "a new hierarchy changes it"
        with pytest.raises(Tm1PreconditionFailedError):
            tm1.dimensions.update(d, patch, etag)


def test_ignores_hierarchies_and_the_all_leaves_name_in_a_patch(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        tm1.dimensions.update(d, DimensionPatch.builder()
            .all_leaves_hierarchy_name("AllLeaves")
            .hierarchies([HierarchyPatch.builder().name(d).elements([numeric("North"), numeric("South"), numeric("East")]).build()])
            .build())
        read = tm1.dimensions.get(d, ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.expand(Hierarchy.ELEMENTS)))
        assert not read.all_leaves_hierarchy_name, "TM1 accepts the PATCH and ignores it"
        assert len(read.hierarchies) == 1 and len(read.hierarchies[0].elements) == 2


def test_saves_a_copy_and_adds_an_all_leaves_hierarchy(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        assert tm1.dimensions.save_as(d, d + "-copy").name == d + "-copy"
        assert tm1.dimensions.save_as(d, d + "-copy", True, False).name == d + "-copy"
        tm1.dimensions.delete(d + "-copy")
        assert tm1.dimensions.add_all_leaves_hierarchy(d).name == "Leaves"
        hierarchies = names(tm1.dimensions.get(d, ODataQuery.none().expand(Dimension.HIERARCHIES)).hierarchies)
        assert d in hierarchies and "Leaves" in hierarchies


def test_locks_and_reserves_a_dimension(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North")])) as d:
        tm1.dimensions.lock(d)
        tm1.dimensions.unlock(d)
        tm1.dimensions.reserve(d)
        tm1.dimensions.release(d)


# HierarchyServiceTm1Test


@pytest.fixture
def region(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A dimension: Total = North + South, and East, a second root."""
    with made(tm1, name, dimension_of(
        name,
        [consolidated("Total"), numeric("North"), numeric("South"), numeric("East")],
        [edge("Total", "North"), edge("Total", "South")],
    )) as d:
        yield d


def structure(name: str) -> HierarchyPatch:
    return (
        HierarchyPatch.builder()
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


def test_updates_a_hierarchy_only_when_the_etag_is_current_and_ignores_visible(tm1: Tm1Client, region: str) -> None:
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


def test_saves_a_copy_of_a_hierarchy(tm1: Tm1Client, region: str) -> None:
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


# ElementServiceTm1Test


@pytest.fixture
def elements_dim(tm1: Tm1Client, name: str) -> Iterator[str]:
    with made(tm1, name, dimension_of(
        name,
        [consolidated("Total"), numeric("North"), numeric("South"), numeric("East"), string("Label")],
        [edge("Total", "North"), edge("Total", "South")],
    )) as d:
        yield d


def components(tm1: Tm1Client, d: str, parent: str) -> list[str]:
    return names(tm1.elements.components(d, d, parent))


def test_creates_reads_updates_and_deletes_an_element(tm1: Tm1Client, elements_dim: str) -> None:
    d, elements = elements_dim, tm1.elements
    assert elements.create(d, d, numeric("West")).name == "West"
    assert sorted(names(elements.list(d, d, ODataQuery.none().select(Element.NAME)))) == sorted(
        ["Total", "North", "South", "East", "Label", "West"]
    )
    etag = elements.get(d, d, "West").etag
    elements.update(d, d, "West", ElementPatch.builder().type(ElementType.STRING).build(), etag)
    assert elements.get(d, d, "West").type is ElementType.STRING
    with pytest.raises(Tm1PreconditionFailedError):
        elements.update(d, d, "West", ElementPatch.builder().type(ElementType.NUMERIC).build(), etag)
    elements.delete(d, d, "West")
    with pytest.raises(Tm1NotFoundError):
        elements.get(d, d, "West")


def test_refuses_name_and_attributes_in_an_update_as_tm1_ignores_or_rejects_them(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    with pytest.raises(ValueError, match="renamed"):
        tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build())
    with pytest.raises(ValueError):
        tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build(), 'W/"x"')
    east = ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key("East")
    tm1.session.execute(Tm1Request.patch(east, {"Name": "Est"}))
    assert tm1.elements.get(d, d, "East").name == "East", "TM1 ignores the new name"
    with pytest.raises(Tm1BadRequestError):
        tm1.session.execute(Tm1Request.patch(east, {"Attributes": {"Caption": "E"}}))


def test_navigates_parents_components_edges_and_the_hierarchy(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    assert names(tm1.elements.parents(d, d, "North")) == ["Total"]
    assert components(tm1, d, "Total") == ["North", "South"]
    assert [(e.parent_name, e.component_name) for e in tm1.elements.edges(d, d, "Total")] == [("Total", "North"), ("Total", "South")]
    assert tm1.elements.hierarchy(d, d, "North").name == d


def test_sets_components_last_or_before_another_with_a_weight(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    tm1.elements.set_component(d, d, "Total", "East")
    assert components(tm1, d, "Total") == ["North", "South", "East"], "no Before puts it last"
    tm1.elements.create(d, d, numeric("West"))
    tm1.elements.set_component(d, d, "Total", "West", before="North", weight=2.0)
    assert components(tm1, d, "Total") == ["West", "North", "South", "East"]
    assert tm1.edges.get(d, d, "Total", "West").weight == 2.0
    tm1.elements.create(d, d, numeric("Far"))
    tm1.elements.set_component(d, d, "Total", "Far", weight=-1.0)
    assert tm1.edges.get(d, d, "Total", "Far").weight == -1.0


def test_locks_and_reserves_an_element(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    tm1.elements.lock(d, d, "North")
    tm1.elements.unlock(d, d, "North")
    tm1.elements.reserve(d, d, "North")
    tm1.elements.release(d, d, "North")


def test_creates_reads_reweights_and_deletes_edges(tm1: Tm1Client, elements_dim: str) -> None:
    d, edges = elements_dim, tm1.edges
    assert len(edges.list(d, d)) == 2
    assert edges.get(d, d, "Total", "North").weight == 1.0
    edges.update(d, d, "Total", "North", EdgePatch.builder().weight(3.0).build())
    assert edges.get(d, d, "Total", "North").weight == 3.0
    assert edges.parent(d, d, "Total", "North").name == "Total"
    assert edges.component(d, d, "Total", "North").name == "North"
    assert edges.hierarchy(d, d, "Total", "North").name == d
    assert edges.create(d, d, edge("Total", "East")).component_name == "East"
    edges.delete(d, d, "Total", "North")
    assert components(tm1, d, "Total") == ["South", "East"]
    assert tm1.elements.parents(d, d, "North") == [], "still an element, now a root"


# LevelAndMemberServiceTm1Test


@pytest.fixture
def world(tm1: Tm1Client, name: str) -> Iterator[str]:
    with made(tm1, name, dimension_of(
        name,
        [consolidated("World"), consolidated("Europe"), consolidated("Asia"), numeric("France"), numeric("Spain"), numeric("Japan")],
        [edge("World", "Europe"), edge("World", "Asia"), edge("Europe", "France"), edge("Europe", "Spain"), edge("Asia", "Japan")],
    )) as d:
        yield d


def test_lists_levels_and_their_members(tm1: Tm1Client, world: str) -> None:
    d = world
    levels = tm1.levels.list(d, d)
    assert [level.number for level in levels] == [0, 1, 2]
    assert [level.cardinality for level in levels] == [1, 2, 3]
    assert tm1.levels.get(d, d, 2).cardinality == 3
    assert sorted(names(tm1.levels.members(d, d, 2, ODataQuery.none().select(Member.NAME)))) == ["France", "Japan", "Spain"]
    assert tm1.levels.hierarchy(d, d, 0).name == d


def test_navigates_members(tm1: Tm1Client, world: str) -> None:
    d, members = world, tm1.members
    listed = members.list(d, d)
    assert len(listed) == 6 and all(type(m) is Member for m in listed)
    assert members.get(d, d, "Europe").unique_name == f"[{d}].[{d}].[Europe]"
    assert members.parent(d, d, "France").name == "Europe"
    assert members.parent(d, d, "World") is None, "a root has none"
    assert names(members.children(d, d, "Europe")) == ["France", "Spain"]
    assert members.element(d, d, "Japan").type is ElementType.NUMERIC
    assert members.level(d, d, "Japan").number == 2
    assert members.hierarchy(d, d, "Japan").name == d


# SubsetServiceTm1Test


@pytest.fixture
def abcd(tm1: Tm1Client, name: str) -> Iterator[str]:
    with made(tm1, name, dimension_of(name, [numeric("A"), numeric("B"), numeric("C"), numeric("D")])) as d:
        yield d


def el(d: str, name: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(name)


def subset_elements(tm1: Tm1Client, d: str, scope: SubsetScope, subset: str) -> list[str]:
    return names(tm1.subsets.elements(d, d, scope, subset, ODataQuery.none().select(Element.NAME)))


def test_creates_static_and_dynamic_subsets_reads_updates_and_deletes_them(tm1: Tm1Client, abcd: str) -> None:
    d, subsets, public = abcd, tm1.subsets, SubsetScope.PUBLIC
    subsets.create(d, d, public, SubsetPatch.builder().name("Static").bind_elements(el(d, "A"), el(d, "B")).build())
    subsets.create(d, d, public, SubsetPatch.builder().name("Dynamic").expression(f"{{TM1SUBSETALL([{d}].[{d}])}}").build())
    assert sorted(names(subsets.list(d, d, public))) == ["Dynamic", "Static"]
    assert subset_elements(tm1, d, public, "Static") == ["A", "B"]
    assert sorted(subset_elements(tm1, d, public, "Dynamic")) == ["A", "B", "C", "D"]
    etag = subsets.get(d, d, public, "Dynamic").etag
    subsets.update(d, d, public, "Dynamic", SubsetPatch.builder().expression(f"{{[{d}].[{d}].[C]}}").build(), etag)
    assert subset_elements(tm1, d, public, "Dynamic") == ["C"]
    with pytest.raises(Tm1PreconditionFailedError):
        subsets.update(d, d, public, "Dynamic", SubsetPatch.builder().expression("{}").build(), etag)
    assert subsets.hierarchy(d, d, public, "Static").name == d
    subsets.delete(d, d, public, "Static")
    assert names(subsets.list(d, d, public)) == ["Dynamic"]


def test_appends_and_clears_elements_by_name_and_refuses_them_in_an_update(tm1: Tm1Client, abcd: str) -> None:
    d, public = abcd, SubsetScope.PUBLIC
    tm1.subsets.create(d, d, public, SubsetPatch.builder().name("S").bind_elements(el(d, "A")).build())
    tm1.subsets.add_elements(d, d, public, "S", ["C", "B"])
    assert subset_elements(tm1, d, public, "S") == ["A", "C", "B"], "appended, in order"
    tm1.subsets.clear_elements(d, d, public, "S")
    assert subset_elements(tm1, d, public, "S") == []
    with pytest.raises(ValueError, match="add_elements"):
        tm1.subsets.update(d, d, public, "S", SubsetPatch.builder().bind_elements(el(d, "D")).build())


def test_places_elements_last_before_or_after_others(tm1: Tm1Client, abcd: str) -> None:
    d, public = abcd, SubsetScope.PUBLIC
    tm1.subsets.create(d, d, public, SubsetPatch.builder().name("S").bind_elements(el(d, "A"), el(d, "B")).build())
    tm1.subsets.set_element(d, d, public, "S", "C")
    assert subset_elements(tm1, d, public, "S") == ["A", "B", "C"], "no position puts it last"
    tm1.subsets.set_element_before(d, d, public, "S", "D", "B")
    assert subset_elements(tm1, d, public, "S") == ["A", "D", "B", "C"]
    tm1.subsets.set_element_after(d, d, public, "S", "A", "B")
    assert subset_elements(tm1, d, public, "S") == ["D", "B", "A", "C"], "moved, not added again"


def test_keeps_private_subsets_apart_and_saves_copies(tm1: Tm1Client, abcd: str) -> None:
    d = abcd
    tm1.subsets.create(d, d, SubsetScope.PRIVATE, SubsetPatch.builder().name("Mine").bind_elements(el(d, "D")).build())
    assert names(tm1.subsets.list(d, d, SubsetScope.PRIVATE)) == ["Mine"]
    assert tm1.subsets.list(d, d, SubsetScope.PUBLIC) == []
    tm1.subsets.create(d, d, SubsetScope.PUBLIC, SubsetPatch.builder().name("All").expression(f"{{TM1SUBSETALL([{d}].[{d}])}}").build())
    assert tm1.subsets.save_as(d, d, SubsetScope.PUBLIC, "All", "Copy").expression
    frozen = tm1.subsets.save_as(d, d, SubsetScope.PUBLIC, "All", "Frozen", True, True)
    assert not frozen.expression, "static"
    assert sorted(subset_elements(tm1, d, SubsetScope.PRIVATE, "Frozen")) == ["A", "B", "C", "D"]


def test_creates_session_subsets_under_the_servers_name(tm1: Tm1Client, abcd: str) -> None:
    d = abcd
    defined = tm1.subsets.create_session_subset(d, d, SubsetPatch.builder().name("Wanted").expression(f"{{[{d}].[{d}].[B]}}").build())
    assert defined.name != "Wanted", "TM1 ignores the name it's given"
    assert subset_elements(tm1, d, SubsetScope.SESSION, defined.name) == ["B"]
    tm1.subsets.create(d, d, SubsetScope.PUBLIC, SubsetPatch.builder().name("S").bind_elements(el(d, "C"), el(d, "D")).build())
    copied = tm1.subsets.create_session_subset_from(d, d, SubsetScope.PUBLIC, "S")
    assert subset_elements(tm1, d, SubsetScope.SESSION, copied.name) == ["C", "D"]
    assert tm1.subsets.list(d, d, SubsetScope.SESSION) == [], "TM1 lists no session subsets, though each reads by its name"
