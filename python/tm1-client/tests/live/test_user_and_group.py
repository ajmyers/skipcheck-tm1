# SPDX-License-Identifier: Apache-2.0

"""UserService and GroupService against a real TM1: a port of the Java client's UserAndGroupTm1Test."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import (
    ODataPath,
    Tm1Client,
    Tm1Error,
    Tm1NotFoundError,
    Tm1Request,
)
from skipcheck_tm1.model import (
    UserCreate,
    UserPatch,
    UserType,
)

from .conftest import ADMIN, CONFIGURED, REASON, other_client

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


class People:
    def __init__(self, name: str):
        self.user = name
        self.g1 = name + "_g1"
        self.g2 = name + "_g2"


@pytest.fixture
def people(tm1: Tm1Client, name: str) -> Iterator[People]:
    p = People(name)
    tm1.groups.create(p.g1)
    tm1.groups.create(p.g2)
    tm1.users.create(UserCreate.builder().name(p.user).password("pw").bind_groups(ODataPath.of("Groups").key(p.g1)).build())
    yield p
    for cleanup in (lambda: tm1.users.delete(p.user), lambda: tm1.groups.delete(p.g1), lambda: tm1.groups.delete(p.g2)):
        try:
            cleanup()
        except Tm1Error:
            pass


def test_creates_reads_updates_and_deletes_a_user(tm1: Tm1Client, people: People) -> None:
    users = tm1.users
    read = users.get(people.user)
    assert read.type is UserType.USER
    assert users.get(people.user.upper().replace("_", " _ ")) is not None, "names match ignoring case and spaces"
    assert read.etag is None, "TM1 sends none for a user"
    users.update(people.user, UserPatch.builder().friendly_name("Friend").build())
    assert users.get(people.user).friendly_name == "Friend"
    assert users.active_user().name.lower() == ADMIN.lower()

    users.delete(people.user)
    with pytest.raises(Tm1NotFoundError):
        users.get(people.user)


def test_sets_groups_whole_and_lists_members_from_both_sides(tm1: Tm1Client, people: People) -> None:
    def groups() -> list[str]:
        return [g.name for g in tm1.users.groups(people.user)]

    assert groups() == [people.g1]
    tm1.users.set_groups(people.user, [people.g1, people.g2])
    assert sorted(groups()) == sorted([people.g1, people.g2])
    tm1.users.set_groups(people.user, [people.g2])
    assert groups() == [people.g2], "replaced"
    assert [u.name for u in tm1.groups.users(people.g2)] == [people.user]
    assert tm1.groups.get(people.g1).name == people.g1, "left, not deleted"
    # UserPatch offers no Groups (#361); a mapping with them is refused, sending nothing
    assert not hasattr(UserPatch.builder(), "bind_groups")
    with pytest.raises(ValueError, match="set_groups"):
        tm1.users.update(people.user, {"Groups@odata.bind": [str(ODataPath.of("Groups").key(people.g1))]})


def test_deletes_the_group_itself_on_a_reference_delete(tm1: Tm1Client, people: People) -> None:
    # The call UserService never makes: OData's "remove the link" deletes the group
    tm1.session.execute(Tm1Request.delete(ODataPath.of("Users").key(people.user).nav("Groups").key(people.g1).ref()))
    with pytest.raises(Tm1NotFoundError):
        tm1.groups.get(people.g1)


def test_disconnects_a_user(tm1: Tm1Client, people: People) -> None:
    as_user = other_client(user=people.user, password="pw")
    try:
        as_user.connect()
        assert tm1.users.get(people.user).is_active is True
        tm1.users.disconnect(people.user)
        assert tm1.users.get(people.user).is_active is False
    finally:
        try:
            as_user.close()
        except Tm1Error:
            pass
