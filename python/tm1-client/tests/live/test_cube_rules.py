# SPDX-License-Identifier: Apache-2.0

"""CubeService's rules and feeders against a real TM1: a port of the Java client's CubeRulesTm1Test."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1Request
from skipcheck_tm1.model import (
    CheckFeedersSelection,
    CubeCreate,
    ElementType,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dim,
    dimension,
    drop,
    edge,
    element,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\nFEEDERS;\n['Amount'] => ['Double'];\n"


@pytest.fixture
def ruled(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str, str]]:
    region = dimension(
        tm1,
        name + "-region",
        [element("Total", ElementType.CONSOLIDATED), element("North"), element("South")],
        [edge("Total", "North"), edge("Total", "South")],
    )
    measure = dimension(tm1, name + "-measure", [element("Amount"), element("Double")])
    tm1.cubes.create(CubeCreate.builder().name(name).rules(RULES).bind_dimensions(dim(region), dim(measure)).build())
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
