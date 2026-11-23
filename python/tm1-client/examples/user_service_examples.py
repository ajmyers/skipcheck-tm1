"""Examples for UserService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client
from skipcheck_tm1.model import Group, User, UserPatch


def create(tm1: Tm1Client) -> User:
    # @start region = create
    # A user in two existing groups, bound by their paths
    groups = ODataPath.of("Groups")
    alice = UserPatch.builder().name("alice").password("initial-password").bind_groups(groups.key("Planners"), groups.key("Sales")).build()
    return tm1.users.create(alice)
    # @end


def leave_group(tm1: Tm1Client) -> None:
    # @start region = leave-group
    # Groups are set whole: keep every group but Planners
    groups = tm1.users.groups("alice", ODataQuery.none().select(Group.NAME))
    tm1.users.set_groups("alice", [group.name for group in groups if group.name and group.name != "Planners"])
    # @end


def lock_out(tm1: Tm1Client) -> None:
    # @start region = lock-out
    # Stop the user logging in, then end the sessions they already have
    tm1.users.update("alice", UserPatch.builder().enabled(False).build())
    tm1.users.disconnect("alice")
    # @end
