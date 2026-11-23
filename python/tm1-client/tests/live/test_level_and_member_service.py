"""LevelService against a real TM1: a port of the Java client's LevelAndMemberServiceTm1Test. The
dimension is conftest.py's world."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    ODataQuery,
    Tm1Client,
)
from skipcheck_tm1.model import (
    Member,
)

from .conftest import CONFIGURED, REASON
from .support import (
    names,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_lists_levels_and_their_members(tm1: Tm1Client, world: str) -> None:
    d = world
    levels = tm1.levels.list(d, d)
    assert [level.number for level in levels] == [0, 1, 2]
    assert [level.cardinality for level in levels] == [1, 2, 3]
    assert tm1.levels.get(d, d, 2).cardinality == 3
    assert sorted(names(tm1.levels.members(d, d, 2, ODataQuery.none().select(Member.NAME)))) == ["France", "Japan", "Spain"]
    assert tm1.levels.hierarchy(d, d, 0).name == d
