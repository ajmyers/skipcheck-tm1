"""Examples for LevelService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Member


def list_levels(tm1: Tm1Client) -> list[str]:
    # @start region = list
    # Each level's number and how many members it has, as "1: 2"
    return [f"{level.number}: {level.cardinality}" for level in tm1.levels.list("Region", "Region")]
    # @end


def members(tm1: Tm1Client) -> list[str | None]:
    # @start region = members
    # Levels count from the top: level 0 holds the roots, level 1 their children
    return [member.name for member in tm1.levels.members("Region", "Region", 1, ODataQuery.none().select(Member.NAME))]
    # @end
