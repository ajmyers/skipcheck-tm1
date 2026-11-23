# SPDX-License-Identifier: Apache-2.0

"""CellService against a real TM1: a port of the Java client's CellServiceTm1Test. The cube is
Region (Total = North + South) by Measure (Amount, and Note as text)."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, Tm1BadRequestError, Tm1Client, Tm1Request
from skipcheck_tm1.aio.services.cube import ViewScope
from skipcheck_tm1.aio.services.data import Tuples
from skipcheck_tm1.model import (
    CellsetUpdatePatch,
    CubePatch,
    CubeUpdatePatch,
    ElementType,
    UpdateOrder,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dim,
    dimension,
    drop,
    edge,
    element,
    first_value,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


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
