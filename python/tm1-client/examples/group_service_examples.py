# SPDX-License-Identifier: Apache-2.0

"""Examples for GroupService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Group, GroupCreate, User


def members(tm1: Tm1Client) -> list[str | None]:
    # @start region = members
    return [user.name for user in tm1.groups.users("Planners", ODataQuery.none().select(User.NAME))]
    # @end


def create_and_add(tm1: Tm1Client) -> None:
    # @start region = create
    # A new group, then alice added to it, from her side: her groups plus this one
    tm1.groups.create(GroupCreate.builder().name("Planners").build())
    groups = [group.name for group in tm1.users.groups("alice", ODataQuery.none().select(Group.NAME)) if group.name]
    tm1.users.set_groups("alice", [*groups, "Planners"])
    # @end
