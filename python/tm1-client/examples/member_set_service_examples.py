"""Examples for MemberSetService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import CellsetAxis, Member


def names(tm1: Tm1Client) -> list[str | None]:
    # @start region = names
    # The names of Region's leaves, with the member set deleted once they're read
    with_names = ODataQuery.none().expand(CellsetAxis.TUPLES, lambda t: t.expand("Members", lambda m: m.select(Member.NAME)))
    leaves = tm1.member_sets.execute("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}", with_names)
    try:
        return [tuple.members[0].name for tuple in leaves.tuples]
    finally:
        tm1.member_sets.delete(leaves.ordinal)
    # @end


def keep(tm1: Tm1Client) -> int | None:
    # @start region = keep
    # Keep a large set on the server and read it later by its ordinal; TM1 doesn't
    # list member sets, so hold on to the ordinal, and delete the set when done
    return tm1.member_sets.execute("{TM1SUBSETALL([Product].[Product])}").ordinal
    # @end
