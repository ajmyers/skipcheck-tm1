"""ViewService against a real TM1: a port of the Java client's ViewServiceTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1PreconditionFailedError
from skipcheck_tm1.aio.services.cube import ViewScope
from skipcheck_tm1.model import (
    CubePatch,
    MDXView,
    MDXViewPatch,
    NativeView,
    NativeViewPatch,
    ViewAxisSelectionPatch,
    ViewTitlePatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dim,
    dimension,
    drop,
    element,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


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
