# SPDX-License-Identifier: Apache-2.0

"""DrillthroughService against a real TM1: a port of the Java client's DrillthroughTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1Request
from skipcheck_tm1.aio.services.cube import ViewScope
from skipcheck_tm1.model import (
    CubeDrillthrough,
    CubePatch,
    NativeViewPatch,
    ProcessParameterPatch,
    ProcessPatch,
    ProcessVariableType,
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
