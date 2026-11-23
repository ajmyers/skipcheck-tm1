"""SandboxService and ChangeSetService against a real TM1: a port of the Java client's
SandboxAndChangeSetTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import (
    CubePatch,
    SandboxPatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dim,
    dimension,
    drop,
    element,
    first_value,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


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
