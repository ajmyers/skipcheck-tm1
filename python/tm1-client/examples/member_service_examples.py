# SPDX-License-Identifier: Apache-2.0

"""Examples for MemberService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Member


def unique_name(tm1: Tm1Client) -> str | None:
    # @start region = unique-name
    # A member's unique name, such as [Region].[Region].[North], for use in MDX
    return tm1.members.get("Region", "Region", "North", ODataQuery.none().select(Member.UNIQUE_NAME)).unique_name
    # @end


def children(tm1: Tm1Client) -> list[str | None]:
    # @start region = children
    # The children of Total, in order
    return [member.name for member in tm1.members.children("Region", "Region", "Total", ODataQuery.none().select(Member.NAME))]
    # @end


def parent(tm1: Tm1Client) -> str | None:
    # @start region = parent
    # The parent of North (the first, if it has several); a root has none
    north = tm1.members.parent("Region", "Region", "North", ODataQuery.none().select(Member.NAME))
    return "(a root)" if north is None else north.name
    # @end
