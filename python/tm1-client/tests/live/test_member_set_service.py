# SPDX-License-Identifier: Apache-2.0

"""MemberSetService against a real TM1: a port of the Java client's MemberSetServiceTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1NotFoundError

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


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
