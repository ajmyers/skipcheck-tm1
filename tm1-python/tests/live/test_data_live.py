"""The data services against a real TM1: ports of the Java client's CellServiceTm1Test,
CellsetServiceTm1Test, MemberSetServiceTm1Test, MdxAndModelTm1Test, SandboxAndChangeSetTm1Test and DrillthroughTm1Test
(see conftest.py for how to point them at a server)."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from tm1_client import ODataPath, ODataQuery, Tm1BadRequestError, Tm1Client, Tm1NotFoundError, Tm1Request
from tm1_client.aio.services.cube import ViewScope
from tm1_client.aio.services.data import Tuples
from tm1_client.model import (
    CellsetUpdatePatch,
    CubeDrillthrough,
    CubePatch,
    CubeUpdatePatch,
    ElementType,
    MDXViewPatch,
    NativeViewPatch,
    ProcessParameterPatch,
    ProcessPatch,
    ProcessVariableType,
    SandboxPatch,
    UpdateOrder,
    ViewAxisSelectionPatch,
)

from .conftest import CONFIGURED, REASON
from .test_cube_live import dim, dimension, drop, edge, element

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def first_value(client: Tm1Client, mdx: str) -> Any:
    """The first cell's value of a query, read through ``stream``."""

    def first(cellset: Any, cells: Any) -> Any:
        for cell in cells:
            return cell.value
        return None

    return client.cellsets.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 10, first)


# CellServiceTm1Test: Region (Total = North + South) by Measure (Amount, Note as text)


@pytest.fixture
def cells_cube(tm1: Tm1Client, name: str) -> Iterator[Any]:
    region = dimension(
        tm1,
        name + "-region",
        [element("Total", ElementType.CONSOLIDATED), element("North"), element("South")],
        [edge("Total", "North"), edge("Total", "South")],
    )
    measure = dimension(tm1, name + "-measure", [element("Amount"), element("Note", ElementType.STRING)])
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(region), dim(measure)).build())

    class Cube:
        def __init__(self) -> None:
            self.name, self.region, self.measure = name, region, measure

        def cell(self, r: str, m: str) -> dict[str, str]:
            return {region: r, measure: m}

        def read(self, r: str, m: str) -> Any:
            return first_value(tm1, f"SELECT {{[{measure}].[{measure}].[{m}]}} ON 0, {{[{region}].[{region}].[{r}]}} ON 1 FROM [{name}]")

        def number(self, r: str, m: str) -> float:
            value = self.read(r, m)
            return 0.0 if value is None else float(value)

        def write(self, r: str, m: str, value: Any) -> Any:
            return CubeUpdatePatch.builder().bind_tuple(*Tuples.of(self.cell(r, m))).value(value).build()

    yield Cube()
    tm1.cubes.delete(name)
    drop(tm1, region, measure)


def test_writes_numbers_text_and_nulls_cell_by_cell(tm1: Tm1Client, cells_cube: Any) -> None:
    c = cells_cube
    tm1.cells.update(c.name, c.cell("North", "Amount"), 5)
    tm1.cells.update(c.name, c.cell("South", "Amount"), 7.5)
    tm1.cells.update(c.name, c.cell("North", "Note"), "hello")
    assert c.number("North", "Amount") == 5.0
    assert c.number("Total", "Amount") == 12.5, "consolidated"
    assert c.read("North", "Note") == "hello"
    tm1.cells.update(c.name, c.cell("North", "Amount"), None)
    assert c.number("North", "Amount") == 0.0, "None clears"


def test_needs_a_value_in_every_update_and_doesnt_copy_from_a_reference(tm1: Tm1Client, cells_cube: Any) -> None:
    c = cells_cube
    tm1.cells.update(c.name, c.cell("North", "Amount"), 40)

    def with_reference() -> Any:
        return (
            CubeUpdatePatch.builder()
            .bind_tuple(*Tuples.of(c.cell("South", "Amount")))
            .bind_reference_cube(ODataPath.of("Cubes").key(c.name))
            .bind_reference_cell(*Tuples.of(c.cell("North", "Amount")))
        )

    with pytest.raises(Tm1BadRequestError, match="Value"):
        tm1.cells.update_cells(c.name, [with_reference().build()])
    tm1.cells.update_cells(c.name, [with_reference().value(5).build()])
    assert c.number("South", "Amount") == 5.0, "the value, not the reference's 40"


def test_writes_only_the_first_of_several_cells_and_refuses_consolidations(tm1: Tm1Client, cells_cube: Any) -> None:
    c = cells_cube
    # Through the raw session: the service only offers what TM1 does
    body = {
        "Cells": [
            {"Tuple@odata.bind": [str(p) for p in Tuples.of(c.cell("North", "Amount"))]},
            {"Tuple@odata.bind": [str(p) for p in Tuples.of(c.cell("South", "Amount"))]},
        ],
        "Value": 3,
    }
    tm1.session.execute(Tm1Request.post(ODataPath.of("Cubes").key(c.name).action("tm1.Update"), body, None))
    assert c.number("North", "Amount") == 3.0
    assert c.number("South", "Amount") == 0.0, "only the first cell is written"
    with pytest.raises(Tm1BadRequestError, match="Consolidated"):
        tm1.cells.update(c.name, c.cell("Total", "Amount"), 100)


def test_writes_many_cells_in_one_request(tm1: Tm1Client, cells_cube: Any) -> None:
    c = cells_cube
    tm1.cells.update_cells(c.name, [c.write("North", "Amount", 10), c.write("South", "Amount", 20), c.write("North", "Note", "n")])
    assert c.number("Total", "Amount") == 30.0
    assert c.read("North", "Note") == "n"
    tm1.cells.update_cells(c.name, [c.write("North", "Amount", 1), c.write("South", "Amount", 2)], UpdateOrder.BY_LEVEL)
    assert c.number("Total", "Amount") == 3.0


def test_writes_through_a_cellset_and_saves_it_as_a_view(tm1: Tm1Client, cells_cube: Any) -> None:
    c = cells_cube
    r, m = c.region, c.measure
    mdx = f"SELECT {{[{m}].[{m}].[Amount]}} ON 0, {{[{r}].[{r}].[North], [{r}].[{r}].[South]}} ON 1 FROM [{c.name}]"
    id = tm1.cellsets.execute(mdx).id
    try:
        tm1.cellsets.update(id, 0, 4)
        assert c.number("North", "Amount") == 4.0
        tm1.cellsets.update(id, 1, 6)
        assert c.number("Total", "Amount") == 10.0
        tm1.session.execute(Tm1Request.post(ODataPath.of("Cellsets").key(id).action("tm1.Update"), {"BeginOrdinal": 0, "EndOrdinal": 1, "Value": 9}, None))
        assert c.number("Total", "Amount") == 15.0, "a range writes only its first cell"
        tm1.cellsets.update_cells(id, [CellsetUpdatePatch.builder().ordinal(0).value(1).build(), CellsetUpdatePatch.builder().ordinal(1).value(2).build()])
        assert c.number("Total", "Amount") == 3.0

        assert tm1.cellsets.save_view_as(id, "Saved").name == "Saved"
        assert tm1.views.get(c.name, ViewScope.PUBLIC, "Saved").name == "Saved"
        assert tm1.cellsets.save_view_as(id, "Mine", False, True).name == "Mine"
        assert tm1.views.get(c.name, ViewScope.PRIVATE, "Mine").name == "Mine"
    finally:
        tm1.cellsets.delete(id)


# CellsetServiceTm1Test: a 4 x 2 cube, cell (row r, column c) holding r * 2 + c + 1

REGIONS = ["B", "C", "E", "F"]
MEASURES = ["Amount", "Count"]


@pytest.fixture
def cellset_cube(tm1: Tm1Client, name: str) -> Iterator[Any]:
    region = dimension(tm1, name + "-region", [element(e) for e in REGIONS], subset=True)
    measure = dimension(tm1, name + "-measure", [element(e) for e in MEASURES], subset=True)
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(region), dim(measure)).build())
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


def test_deletes_the_cellset_when_the_handler_stops_early(tm1: Tm1Client, cellset_cube: Any) -> None:
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

    tm1.views.create(cube, ViewScope.PUBLIC, MDXViewPatch.builder().name("Mdx").mdx(mdx).build())
    tm1.views.create(cube, ViewScope.PUBLIC, NativeViewPatch.builder().name("Native").columns([axis(measure)]).rows([axis(region)]).build())
    for view in ("Mdx", "Native"):
        cellset = tm1.cellsets.execute_view(cube, ViewScope.PUBLIC, view, ODataQuery.none().expand("Cells", lambda c: c.select("Value")))
        try:
            assert [float(c.value) for c in cellset.cells] == [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0], view
        finally:
            tm1.cellsets.delete(cellset.id)


# MemberSetServiceTm1Test


def test_keeps_a_set_expressions_result_by_ordinal_but_lists_none(tm1: Tm1Client) -> None:
    members = tm1.member_sets.execute(
        "{TM1SUBSETALL([}Clients].[}Clients])}", ODataQuery.none().expand("Tuples", lambda t: t.expand("Members", lambda m: m.select("Name")))
    )
    assert any([m.name for m in t.members] == ["Admin"] for t in members.tuples)
    ordinal = members.ordinal
    assert tm1.member_sets.get(ordinal).cardinality == members.cardinality
    assert tm1.member_sets.list() == [], "TM1 lists none"
    tm1.member_sets.delete(ordinal)
    with pytest.raises(Tm1NotFoundError):
        tm1.member_sets.get(ordinal)


# MdxAndModelTm1Test


def test_runs_an_mdx_statement_but_not_create_set(tm1: Tm1Client) -> None:
    tm1.cellsets.execute_statement("SELECT {} ON 0 FROM [}ClientGroups]")
    with pytest.raises(Tm1BadRequestError):
        tm1.cellsets.execute_statement("CREATE SET [}Clients].[Probe] AS {TM1SUBSETALL([}Clients].[}Clients])}")


def test_separates_model_and_control_cubes(tm1: Tm1Client) -> None:
    model = [c.name for c in tm1.cubes.model_cubes(select="Name")]
    control = [c.name for c in tm1.cubes.control_cubes(select="Name")]
    assert not any(n.startswith("}") for n in model)
    assert control and all(n.startswith("}") for n in control) and "}ClientGroups" in control


# SandboxAndChangeSetTm1Test


@pytest.fixture
def small_cube(tm1: Tm1Client, name: str) -> Iterator[Any]:
    a, b = name + "-a", name + "-b"
    for d in (a, b):
        dimension(tm1, d, [element("X"), element("Y")])
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(a), dim(b)).build())

    def cell(e: str) -> dict[str, str]:
        return {a: e, b: e}

    def value(client: Tm1Client, e: str) -> float:
        v = first_value(client, f"SELECT {{[{b}].[{b}].[{e}]}} ON 0, {{[{a}].[{a}].[{e}]}} ON 1 FROM [{name}]")
        return 0.0 if v is None else float(v)

    yield name, cell, value
    for s in tm1.sandboxes.list():
        tm1.sandboxes.delete(s.name)
    tm1.cubes.delete(name)
    drop(tm1, a, b)


def test_keeps_sandbox_writes_apart_until_published(tm1: Tm1Client, small_cube: Any) -> None:
    cube, cell, value = small_cube
    tm1.sandboxes.create(SandboxPatch.builder().name("S").build())
    assert "S" in [s.name for s in tm1.sandboxes.list()]
    tm1.with_sandbox("S").cells.update(cube, cell("X"), 5)
    assert value(tm1.with_sandbox("S"), "X") == 5.0
    assert value(tm1, "X") == 0.0, "base data untouched"
    tm1.sandboxes.publish("S")
    assert value(tm1, "X") == 5.0


def test_merges_discards_loads_and_unloads(tm1: Tm1Client, small_cube: Any) -> None:
    cube, cell, value = small_cube
    tm1.sandboxes.create(SandboxPatch.builder().name("S").build())
    tm1.sandboxes.create(SandboxPatch.builder().name("T").build())
    tm1.with_sandbox("S").cells.update(cube, cell("X"), 7)
    tm1.sandboxes.merge("S", "T")
    assert value(tm1.with_sandbox("T"), "X") == 7.0
    tm1.sandboxes.discard_changes("S")
    assert value(tm1.with_sandbox("S"), "X") == 0.0
    tm1.sandboxes.unload("T")
    tm1.sandboxes.load("T")
    assert tm1.sandboxes.get("T").is_loaded
    tm1.sandboxes.delete("T")
    assert "T" not in [s.name for s in tm1.sandboxes.list()]


def test_undoes_the_writes_of_a_change_set_only(tm1: Tm1Client, small_cube: Any) -> None:
    cube, cell, value = small_cube

    def work(session: Any) -> str:
        Tm1Client.of(session).cells.update(cube, cell("X"), 5)
        return session.change_set

    change_set = tm1.change_sets.within(work)
    tm1.cells.update(cube, cell("Y"), 7)
    assert value(tm1, "X") == 5.0
    tm1.change_sets.undo(change_set)
    assert value(tm1, "X") == 0.0, "in the change set: reverted"
    assert value(tm1, "Y") == 7.0, "outside it: kept"


def test_begins_and_ends_a_change_set_by_hand(tm1: Tm1Client, small_cube: Any) -> None:
    cube, cell, value = small_cube
    change_set = tm1.change_sets.begin()
    assert change_set
    tm1.with_change_set(change_set).cells.update(cube, cell("Y"), 3)
    assert tm1.change_sets.end(change_set) is not None
    tm1.change_sets.undo(change_set)
    assert value(tm1, "Y") == 0.0


# DrillthroughTm1Test


@pytest.fixture
def drill(tm1: Tm1Client, name: str) -> Iterator[Any]:
    process = "}Drill_Detail" + name[-8:]
    a, b = name + "-a", name + "-b"
    for d in (a, b):
        dimension(tm1, d, [element("X"), element("Y")], subset=True)
    tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dim(a), dim(b)).drillthrough_rules(f"[] = S: '{process[len('}Drill_') :]}';\n").build())

    def axis(d: str) -> Any:
        return ViewAxisSelectionPatch.builder().bind_subset(dim(d).nav("Hierarchies").key(d).nav("Subsets").key("All")).build()

    tm1.views.create(name, ViewScope.PUBLIC, NativeViewPatch.builder().name("Detail").columns([axis(b)]).rows([axis(a)]).build())

    def parameter(p: str) -> Any:
        return ProcessParameterPatch.builder().name(p).value("").type(ProcessVariableType.STRING).build()

    patch = (
        ProcessPatch.builder()
        .name(process)
        .parameters([parameter("pCube"), parameter("p0"), parameter("p1")])
        .epilog_procedure(f"ReturnViewHandle('{name}', 'Detail');\n")
        .build()
    )
    tm1.session.execute(Tm1Request.post(ODataPath.of("Processes"), patch, None))
    yield name, a, b, process
    tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)))
    tm1.cubes.delete(name)
    drop(tm1, a, b)


def test_runs_a_drillthrough_process_directly(tm1: Tm1Client, drill: Any) -> None:
    cellset = tm1.drillthrough.execute_process(drill[3], [], ODataQuery.none().expand("Cells", lambda c: c.select("Ordinal")))
    try:
        assert len(cellset.cells) == 4, "the Detail view's 2 x 2 cells"
    finally:
        tm1.cellsets.delete(cellset.id)


def test_lists_and_runs_a_cells_drillthrough(tm1: Tm1Client, drill: Any) -> None:
    cube, a, b, _ = drill
    source = tm1.cellsets.execute(f"SELECT {{[{b}].[{b}].[X]}} ON 0, {{[{a}].[{a}].[X]}} ON 1 FROM [{cube}]")
    try:
        scripts = tm1.drillthrough.scripts(source.id, 0)
        assert len(scripts) == 1 and isinstance(scripts[0], CubeDrillthrough)
        detail = tm1.drillthrough.execute(source.id, 0, scripts[0].name, ODataQuery.none().expand("Cells", lambda c: c.select("Ordinal")))
        try:
            assert len(detail.cells) == 4
        finally:
            tm1.cellsets.delete(detail.id)
    finally:
        tm1.cellsets.delete(source.id)


@pytest.mark.skip(reason="Relational drillthrough reads an ODBC data source, which the TM1 test container can't host")
def test_runs_relational_drillthrough(tm1: Tm1Client) -> None:
    assert tm1.drillthrough.execute_relational_process("}Drill_Relational", []) is not None
    rowset = tm1.drillthrough.execute_relational_process_with_rowset("}Drill_Relational", []).id
    assert tm1.drillthrough.rows(rowset) is not None
    tm1.drillthrough.delete_rowset(rowset)
