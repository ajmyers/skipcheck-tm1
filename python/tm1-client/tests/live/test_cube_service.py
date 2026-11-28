# SPDX-License-Identifier: Apache-2.0

"""CubeService against a real TM1: a port of the Java client's CubeServiceTm1Test (see conftest.py for how to point it at a server).
Dimensions are made through the raw session, so these tests depend only on the service they're
about."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1NotFoundError, Tm1NotImplementedError, Tm1PreconditionFailedError, Tm1Request
from skipcheck_tm1._generated.operations import Tm1Operations
from skipcheck_tm1.model import (
    Cube,
    CubeCreate,
    CubePatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    assume_supports,
    dim,
    dimension,
    drop,
    element,
    is_v12,
    names,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def cube(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str, str]]:
    region = dimension(tm1, name + "-region", [element("North"), element("South")])
    measure = dimension(tm1, name + "-measure", [element("Amount")])
    tm1.cubes.create(CubeCreate.builder().name(name).bind_dimensions(dim(region), dim(measure)).build())
    yield name, region, measure
    try:
        tm1.cubes.delete(name)
    except Tm1NotFoundError:
        pass
    drop(tm1, region, measure)


def test_creates_reads_lists_and_deletes_a_cube(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name, region, measure = cube
    assert tm1.cubes.get(name).etag
    assert [c.name for c in tm1.cubes.list(ODataQuery.none().select(Cube.NAME).filter(Cube.NAME.eq(name)))] == [name]
    if is_v12(tm1):
        assert names(tm1.cubes.dimensions(name, select="Name")) == [region, measure]
    else:
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
    if is_v12(tm1):
        assert names(tm1.cubes.dimensions(name)) == [region, measure], "presentation order unchanged"
    else:
        assert names(tm1.cubes.dimensions(name)) == ["Sandboxes", region, measure], "presentation order unchanged"


def test_loads_unloads_locks_and_reserves(tm1: Tm1Client, cube: tuple[str, str, str]) -> None:
    name = cube[0]
    if is_v12(tm1):
        with pytest.raises(Tm1NotImplementedError):
            tm1.cubes.unload(name)
    else:
        tm1.cubes.unload(name)
        tm1.cubes.load(name)
    tm1.cubes.lock(name)
    tm1.cubes.unlock(name)
    assume_supports(tm1, Tm1Operations.CUBE_RESERVE)
    tm1.cubes.reserve(name)
    tm1.cubes.release(name)
