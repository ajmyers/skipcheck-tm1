"""MemberService against a real TM1: a port of the Java client's MemberServiceTm1Test. The
dimension is conftest.py's world."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    Tm1Client,
)
from skipcheck_tm1.model import (
    ElementType,
    Member,
)

from .conftest import CONFIGURED, REASON
from .support import (
    names,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_navigates_members(tm1: Tm1Client, world: str) -> None:
    d, members = world, tm1.members
    listed = members.list(d, d)
    assert len(listed) == 6 and all(type(m) is Member for m in listed)
    assert members.get(d, d, "Europe").unique_name == f"[{d}].[{d}].[Europe]"
    assert members.parent(d, d, "France").name == "Europe"
    assert members.parent(d, d, "World") is None, "a root has none"
    assert names(members.children(d, d, "Europe")) == ["France", "Spain"]
    assert members.element(d, d, "Japan").type is ElementType.NUMERIC
    assert members.level(d, d, "Japan").number == 2
    assert members.hierarchy(d, d, "Japan").name == d
