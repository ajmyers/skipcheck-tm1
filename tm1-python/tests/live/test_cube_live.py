"""CubeService and ViewService against a real TM1: ports of the Java client's
CubeServiceTm1Test, CubeRulesTm1Test and ViewServiceTm1Test (see conftest.py for how to point
them at a server). Dimensions and subsets are made through the raw session, so these tests
depend only on the services they're about."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from tm1_client import ODataPath, ODataQuery, Tm1Client, Tm1NotFoundError, Tm1PreconditionFailedError, Tm1Request
from tm1_client.aio.services.cube import ViewScope
from tm1_client.model import (
    CheckFeedersSelection,
    Cube,
    CubePatch,
    DimensionPatch,
    EdgePatch,
    ElementPatch,
    ElementType,
    HierarchyPatch,
    MDXView,
    MDXViewPatch,
    NativeView,
    NativeViewPatch,
    SubsetPatch,
    ViewAxisSelectionPatch,
    ViewTitlePatch,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\nFEEDERS;\n['Amount'] => ['Double'];\n"


def dim(name: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(name)


def element(name: str, type: ElementType = ElementType.NUMERIC) -> Any:
    return ElementPatch.builder().name(name).type(type).build()


def edge(parent: str, component: str) -> Any:
    return EdgePatch.builder().parent_name(parent).component_name(component).weight(1.0).build()


def dimension(tm1: Tm1Client, name: str, elements: list[Any], edges: list[Any] | None = None, subset: bool = False) -> str:
    hierarchy = HierarchyPatch.builder().name(name).elements(elements)
    if edges:
        hierarchy = hierarchy.edges(edges)
    patch = DimensionPatch.builder().name(name).hierarchies([hierarchy.build()]).build()
    tm1.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), patch, None))
    if subset:
        path = dim(name).nav("Hierarchies").key(name).nav("Subsets")
        all_ = SubsetPatch.builder().name("All").expression(f"{{TM1SUBSETALL([{name}].[{name}])}}").build()
        tm1.session.execute(Tm1Request.post(path, all_, None))
    return name


def drop(tm1: Tm1Client, *dimensions: str) -> None:
    for d in dimensions:
        tm1.session.execute(Tm1Request.delete(dim(d)))


# CubeServiceTm1Test


@pytest.fixture
def cube(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str, str]]:
    region = dimension(tm1, name + "-region", [element("North"), element("South")])
    measure = dimension(tm1, name + "-measure", [element("Amount")])
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(region), dim(measure)).build())
    yield name, region, measure
    try:
        tm1.cubes.delete(name)
    except Tm1NotFoundError:
        pass
    drop(tm1, region, measure)


def names(dimensions: list[Any]) -> list[str]:
    return [d.name for d in dimensions]


def test_creates_reads_lists_and_deletes_a_cube(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name, region, measure = cube
    assert tm1.cubes.get(name).etag
    assert [c.name for c in tm1.cubes.list(ODataQuery.none().select(Cube.NAME).filter(Cube.NAME.eq(name)))] == [name]
    assert names(tm1.cubes.dimensions(name, select="Name")) == ["Sandboxes", region, measure], "Sandboxes first"
    tm1.cubes.delete(name)
    with pytest.raises(Tm1NotFoundError):
        tm1.cubes.get(name)
    assert tm1.session.execute(Tm1Request.get(dim(region), None, dict))["Name"] == region, "its dimensions stay"


def test_saves_rules_even_if_they_dont_compile_and_checks_the_etag(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name = cube[0]

    def rules() -> str | None:
        return tm1.cubes.get(name, ODataQuery.none().select(Cube.RULES)).rules

    etag = tm1.cubes.get(name).etag
    tm1.cubes.update(name, CubePatch.builder().rules("SKIPCHECK;\n").build(), etag)
    assert "SKIPCHECK" in (rules() or "")
    with pytest.raises(Tm1PreconditionFailedError):
        tm1.cubes.update(name, CubePatch.builder().rules("").build(), etag)
    tm1.cubes.update(name, CubePatch.builder().rules("['Nowhere'] = N: 1 +;\n").build())
    assert "Nowhere" in (rules() or ""), "TM1 keeps rules that don't compile"
    tm1.cubes.update(name, CubePatch.builder().rules(None).build())
    assert not rules()


def test_reorders_the_storage_order(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name, region, measure = cube
    assert sorted(names(tm1.cubes.dimensions_storage_order(name))) == sorted([region, measure])
    assert tm1.cubes.reorder_dimensions(name, [measure, region]) is not None
    assert names(tm1.cubes.dimensions_storage_order(name)) == [measure, region]
    assert names(tm1.cubes.dimensions(name)) == ["Sandboxes", region, measure], "presentation order unchanged"


def test_loads_unloads_locks_and_reserves(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name = cube[0]
    tm1.cubes.unload(name)
    tm1.cubes.load(name)
    tm1.cubes.lock(name)
    tm1.cubes.unlock(name)
    tm1.cubes.reserve(name)
    tm1.cubes.release(name)


# CubeRulesTm1Test


@pytest.fixture
def ruled(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str, str]]:
    region = dimension(
        tm1,
        name + "-region",
        [element("Total", ElementType.CONSOLIDATED), element("North"), element("South")],
        [edge("Total", "North"), edge("Total", "South")],
    )
    measure = dimension(tm1, name + "-measure", [element("Amount"), element("Double")])
    tm1.cubes.create(CubePatch.builder().name(name).rules(RULES).bind_dimensions(dim(region), dim(measure)).build())
    yield name, region, measure
    tm1.cubes.delete(name)
    drop(tm1, region, measure)


def test_checks_saved_and_proposed_rules(tm1: Tm1Client, ruled: tuple[str, str, str]) -> None:
    name = ruled[0]
    assert tm1.cubes.check_rules(name) == []
    errors = tm1.cubes.check_rules(name, "SKIPCHECK;\n['Double'] = N: ['Amount'] *;\n")
    assert errors and all(e.line_number == 2 and e.message for e in errors)
    assert tm1.cubes.get(name).rules == RULES, "checking doesn't save"


def test_traces_a_calculation_and_feeders(tm1: Tm1Client, ruled: tuple[str, str, str]) -> None:
    name, region, measure = ruled
    calculation = tm1.cubes.trace_cell_calculation(name, {region: "North", measure: "Double"}, ODataQuery.none().expand("Tuple").expand("Cube"))
    assert any("['Amount'] * 2" in s for s in calculation.statements or [])
    assert calculation.cube.name == name
    assert [e.name for e in calculation.tuple] == ["North", "Double"]
    feeders = tm1.cubes.trace_feeders(name, {region: "North", measure: "Amount"}, ODataQuery.none().expand("FedCells/Tuple"))
    assert any([e.name for e in c.tuple] == ["North", "Double"] for c in feeders.fed_cells or [])


def test_checks_feeders_under_a_consolidation(tm1: Tm1Client, ruled: tuple[str, str, str]) -> None:
    name, region, measure = ruled
    tm1.session.execute(
        Tm1Request.post(
            ODataPath.of("Cubes").key(name).action("tm1.Update"),
            {
                "Cells": [
                    {
                        "Tuple@odata.bind": [
                            str(dim(region).nav("Hierarchies").key(region).nav("Elements").key("North")),
                            str(dim(measure).nav("Hierarchies").key(measure).nav("Elements").key("Amount")),
                        ]
                    }
                ],
                "Value": "5",
            },
            None,
        )
    )
    cells = tm1.cubes.check_feeders(name, {region: "Total", measure: "Double"}, CheckFeedersSelection.BOTH, expand="Tuple")
    assert any([e.name for e in c.tuple] == ["North", "Double"] and c.fed for c in cells)


# ViewServiceTm1Test


@pytest.fixture
def viewed(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str, str, str]]:
    region = dimension(tm1, name + "-region", [element("North"), element("South")], subset=True)
    measure = dimension(tm1, name + "-measure", [element("Amount")], subset=True)
    year = dimension(tm1, name + "-year", [element("2026")], subset=True)
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(region), dim(measure), dim(year)).build())
    yield name, region, measure, year
    tm1.cubes.delete(name)
    drop(tm1, region, measure, year)


def _subset(dimension: str) -> ODataPath:
    return dim(dimension).nav("Hierarchies").key(dimension).nav("Subsets").key("All")


def _native(view: str, region: str, measure: str, year: str) -> Any:
    return (
        NativeViewPatch.builder()
        .name(view)
        .columns([ViewAxisSelectionPatch.builder().bind_subset(_subset(measure)).build()])
        .rows([ViewAxisSelectionPatch.builder().bind_subset(_subset(region)).build()])
        .titles([ViewTitlePatch.builder().bind_subset(_subset(year)).bind_selected(dim(year).nav("Hierarchies").key(year).nav("Elements").key("2026")).build()])
        .build()
    )


def _mdx(cube: str, region: str, measure: str) -> str:
    return f"SELECT {{[{measure}].[{measure}].Members}} ON 0, {{[{region}].[{region}].Members}} ON 1 FROM [{cube}]"


def test_creates_reads_updates_and_deletes_both_kinds_of_view(tm1: Tm1Client, viewed: tuple[str, str, str, str]) -> None:
    cube, region, measure, year = viewed
    views = tm1.views
    views.create(cube, ViewScope.PUBLIC, MDXViewPatch.builder().name("Mdx").mdx(_mdx(cube, region, measure)).build())
    views.create(cube, ViewScope.PUBLIC, _native("Native", region, measure, year))
    every = {v.name: v for v in views.list(cube, ViewScope.PUBLIC)}
    assert set(every) == {"Mdx", "Native"}
    assert isinstance(every["Mdx"], MDXView) and isinstance(every["Native"], NativeView)

    etag = views.get(cube, ViewScope.PUBLIC, "Mdx").etag
    changed = f"SELECT {{[{measure}].[{measure}].[Amount]}} ON 0 FROM [{cube}]"
    views.update(cube, ViewScope.PUBLIC, "Mdx", MDXViewPatch.builder().mdx(changed).build(), etag)
    read = views.get(cube, ViewScope.PUBLIC, "Mdx")
    assert isinstance(read, MDXView) and read.mdx == changed
    with pytest.raises(Tm1PreconditionFailedError):
        views.update(cube, ViewScope.PUBLIC, "Mdx", MDXViewPatch.builder().mdx(_mdx(cube, region, measure)).build(), etag)
    assert views.cube(cube, ViewScope.PUBLIC, "Mdx").name == cube

    views.delete(cube, ViewScope.PUBLIC, "Mdx")
    assert [v.name for v in views.list(cube, ViewScope.PUBLIC)] == ["Native"]


def test_keeps_private_views_apart(tm1: Tm1Client, viewed: tuple[str, str, str, str]) -> None:
    cube, region, measure, _ = viewed
    tm1.views.create(cube, ViewScope.PRIVATE, MDXViewPatch.builder().name("Mine").mdx(_mdx(cube, region, measure)).build())
    assert [v.name for v in tm1.views.list(cube, ViewScope.PRIVATE)] == ["Mine"]
    assert tm1.views.list(cube, ViewScope.PUBLIC) == []


def test_expands_a_native_views_axes_through_its_type(tm1: Tm1Client, viewed: tuple[str, str, str, str]) -> None:
    cube, region, measure, year = viewed
    tm1.views.create(cube, ViewScope.PUBLIC, _native("Native", region, measure, year))
    read = tm1.views.get_native(
        cube,
        ViewScope.PUBLIC,
        "Native",
        ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset").expand("Titles/Subset").expand("Titles/Selected"),
    )
    assert [c.subset.name for c in read.columns] == ["All"]
    assert region in read.rows[0].subset.unique_name
    assert read.titles[0].selected.name == "2026"


def test_expands_native_views_axes_in_a_list_with_mdx_views(tm1: Tm1Client, viewed: tuple[str, str, str, str]) -> None:
    cube, region, measure, year = viewed
    tm1.views.create(cube, ViewScope.PUBLIC, _native("Native", region, measure, year))
    tm1.views.create(cube, ViewScope.PUBLIC, MDXViewPatch.builder().name("Mdx").mdx(_mdx(cube, region, measure)).build())
    every = tm1.views.list(cube, ViewScope.PUBLIC, expand="tm1.NativeView/Columns/Subset")
    natives = [v for v in every if isinstance(v, NativeView)]
    assert len(natives) == 1 and [c.subset.name for c in natives[0].columns] == ["All"]
    assert len([v for v in every if isinstance(v, MDXView)]) == 1
