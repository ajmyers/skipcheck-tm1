# SPDX-License-Identifier: Apache-2.0

"""CellsetService against a real TM1: a port of the Java client's CellsetServiceTm1Test. The cube is
4 x 2, cell (row r, column c) holding r * 2 + c + 1."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1NotFoundError
from skipcheck_tm1.aio.services.cube import ViewScope
from skipcheck_tm1.model import (
    CubeCreate,
    MDXViewCreate,
    NativeViewCreate,
    ViewAxisSelectionPatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dim,
    dimension,
    drop,
    element,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

REGIONS = ["B", "C", "E", "F"]
MEASURES = ["Amount", "Count"]


@pytest.fixture
def cellset_cube(tm1: Tm1Client, name: str) -> Iterator[Any]:
    region = dimension(tm1, name + "-region", [element(e) for e in REGIONS], subset=True)
    measure = dimension(tm1, name + "-measure", [element(e) for e in MEASURES], subset=True)
    tm1.cubes.create(CubeCreate.builder().name(name).bind_dimensions(dim(region), dim(measure)).build())
    value = 1
    for r in REGIONS:
        for m in MEASURES:
            tm1.cells.update(name, {region: r, measure: m}, value)
            value += 1
    mdx = f"SELECT {{[{measure}].[{measure}].Members}} ON 0, {{[{region}].[{region}].Members}} ON 1 FROM [{name}]"
    yield name, region, measure, mdx
    tm1.cubes.delete(name)
    drop(tm1, region, measure)


def gone(tm1: Tm1Client, id: str) -> bool:
    try:
        tm1.cellsets.get(id)
    except Tm1NotFoundError:
        return True
    return False


def test_runs_mdx_and_reads_the_cellset_piece_by_piece(tm1: Tm1Client, cellset_cube: Any) -> None:
    cube, region, _, mdx = cellset_cube
    cellsets = tm1.cellsets
    cellset = cellsets.execute(mdx, ODataQuery.none().expand("Axes").expand("Cells", lambda c: c.select("Ordinal", "Value")))
    id = cellset.id
    assert id
    assert len(cellset.axes) == 3, "columns, rows and the slicer TM1 adds"
    assert len(cellset.cells) == 8
    try:
        assert cellsets.list() == [], "TM1 lists none, though each reads by ID"
        assert cellsets.get(id).id == id
        assert [a.cardinality for a in cellsets.axes(id)] == [2, 4, 1]
        assert [h.name for h in cellsets.hierarchies(id, 1)] == [region]
        assert len(cellsets.tuples(id, 1)) == 4
        assert [m.name for m in cellsets.tuple_members(id, 1, 2)] == ["E"]
        assert [float(c.value) for c in cellsets.cells(id, select=["Ordinal", "Value"])] == [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        assert [m.name for m in cellsets.cell_members(id, 5)] == ["Base", "E", "Count"], "Sandboxes' Base first"
        assert cellsets.cube(id).name == cube
        assert [c.ordinal for c in cellsets.partition(id, 2, 4, select="Ordinal")] == [2, 4], "a rectangle"
        assert [c.ordinal for c in cellsets.partition(id, 2, 5, select="Ordinal")] == [2, 3, 4, 5], "whole rows"
    finally:
        cellsets.delete(id)
    assert gone(tm1, id)


def test_streams_cells_in_order_across_pages_and_deletes_the_cellset(tm1: Tm1Client, cellset_cube: Any) -> None:
    seen: dict[str, str] = {}

    def values(cellset: Any, cells: Any) -> list[float]:
        seen["id"] = cellset.id
        return [float(c.value) for c in cells]

    assert tm1.cellsets.stream(cellset_cube[3], ODataQuery.none().select("Ordinal", "Value"), 3, values) == [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
    assert gone(tm1, seen["id"])


def test_streams_at_least_a_row_per_page(tm1: Tm1Client, cellset_cube: Any) -> None:
    ordinals = tm1.cellsets.stream(cellset_cube[3], ODataQuery.none().select("Ordinal", "Value"), 1, lambda cs, cells: [c.ordinal for c in cells])
    assert ordinals == list(range(8))


def test_deletes_the_cellset_when_the_stream_is_cancelled(tm1: Tm1Client, cellset_cube: Any) -> None:
    # Java cancels the stream after three cells; here the handler stops reading them
    seen: dict[str, str] = {}

    def first_three(cellset: Any, cells: Any) -> list[int]:
        seen["id"] = cellset.id
        out = []
        for cell in cells:
            out.append(cell.ordinal)
            if len(out) == 3:
                break
        return out

    assert tm1.cellsets.stream(cellset_cube[3], ODataQuery.none().select("Ordinal"), 2, first_three) == [0, 1, 2]
    assert gone(tm1, seen["id"])


def test_executes_mdx_and_native_views(tm1: Tm1Client, cellset_cube: Any) -> None:
    cube, region, measure, mdx = cellset_cube

    def axis(d: str) -> Any:
        return ViewAxisSelectionPatch.builder().bind_subset(dim(d).nav("Hierarchies").key(d).nav("Subsets").key("All")).build()

    tm1.views.create(cube, ViewScope.PUBLIC, MDXViewCreate.builder().name("Mdx").mdx(mdx).build())
    tm1.views.create(cube, ViewScope.PUBLIC, NativeViewCreate.builder().name("Native").columns([axis(measure)]).rows([axis(region)]).build())
    for view in ("Mdx", "Native"):
        cellset = tm1.cellsets.execute_view(cube, ViewScope.PUBLIC, view, ODataQuery.none().expand("Cells", lambda c: c.select("Value")))
        try:
            assert [float(c.value) for c in cellset.cells] == [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0], view
        finally:
            tm1.cellsets.delete(cellset.id)
