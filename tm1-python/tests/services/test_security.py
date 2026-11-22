"""UserService, GroupService, SessionService, ThreadService and OidcKeyService, in both
flavors, against a fake TM1: each method sends what the Java client's does."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from tm1_client import Filter, ODataPath, ODataQuery, Tm1NotFoundError
from tm1_client.model import Group, Session, Thread, User, UserPatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> Any:
    return json.loads(request.content) if request.content else None


# UserService


def test_users(api: Api, fake: FakeTm1) -> None:
    users = api.client.users
    fake.respond = lambda r: {"value": [{"Name": "alice"}]}
    assert [u.name for u in api.run(users.list(select="Name"))] == ["alice"]
    assert target(fake.last) == "GET Users?$select=Name"
    fake.respond = lambda r: {"Name": "alice"}
    api.run(users.get("alice", expand="Groups"))
    assert target(fake.last) == "GET Users('alice')?$expand=Groups"
    api.run(users.active_user())
    assert target(fake.last) == "GET ActiveUser"
    groups = ODataPath.of("Groups")
    api.run(users.create(UserPatch.builder().name("alice").password("pw").bind_groups(groups.key("Planners"), groups.key("Sales")).build()))
    assert (target(fake.last), body(fake.last)) == (
        "POST Users",
        {"Name": "alice", "Password": "pw", "Groups@odata.bind": ["Groups('Planners')", "Groups('Sales')"]},
    )
    fake.respond = lambda r: None
    api.run(users.update("alice", UserPatch.builder().enabled(False).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Users('alice')", {"Enabled": False})
    api.run(users.delete("alice"))
    assert target(fake.last) == "DELETE Users('alice')"


def test_groups_are_set_whole_never_through_a_ref_delete(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.users.set_groups("alice", ["Sales", "O'Neil"]))
    assert (target(fake.last), body(fake.last)) == (
        "PATCH Users('alice')",
        {"Groups@odata.bind": ["Groups('Sales')", "Groups('O''Neil')"]},
    )
    api.run(api.client.users.set_groups("alice", []))
    assert body(fake.last) == {"Groups@odata.bind": []}
    # A user PATCH with Groups replaces them: refused, so it's never done by accident
    sent = len(fake.requests)
    for patch in (
        UserPatch.builder().bind_groups(ODataPath.of("Groups").key("x")).build(),
        UserPatch.builder().groups([]).build(),
        {"Groups@odata.bind": []},
        User(name="alice", groups=[]),
    ):
        with pytest.raises(ValueError, match="set_groups"):
            api.run(api.client.users.update("alice", patch))
    assert len(fake.requests) == sent
    assert not any(r.method == "DELETE" for r in fake.requests)


def test_user_navigation_and_disconnect(api: Api, fake: FakeTm1) -> None:
    users = api.client.users
    fake.respond = lambda r: {"value": [{"Name": "Planners"}]}
    assert [g.name for g in api.run(users.groups("alice", ODataQuery.none().select(Group.NAME)))] == ["Planners"]
    assert target(fake.last) == "GET Users('alice')/Groups?$select=Name"
    fake.respond = lambda r: {"value": [{"ID": 8}]}
    assert api.run(users.sessions("alice"))[0].id == 8
    assert target(fake.last) == "GET Users('alice')/Sessions"
    fake.respond = lambda r: None
    api.run(users.disconnect("alice"))
    assert (target(fake.last), body(fake.last)) == ("POST Users('alice')/tm1.Disconnect", {})


# GroupService


def test_groups(api: Api, fake: FakeTm1) -> None:
    groups = api.client.groups
    fake.respond = lambda r: {"value": [{"Name": "ADMIN"}]}
    api.run(groups.list())
    assert target(fake.last) == "GET Groups"
    fake.respond = lambda r: {"Name": "Planners"}
    api.run(groups.get("Planners"))
    assert target(fake.last) == "GET Groups('Planners')"
    assert api.run(groups.create("Planners")).name == "Planners"
    assert (target(fake.last), body(fake.last)) == ("POST Groups", {"Name": "Planners"})
    fake.respond = lambda r: {"value": [{"Name": "alice"}]}
    assert [u.name for u in api.run(groups.users("Planners", ODataQuery.none().select(User.NAME)))] == ["alice"]
    assert target(fake.last) == "GET Groups('Planners')/Users?$select=Name"
    fake.respond = lambda r: None
    api.run(groups.delete("Planners"))
    assert target(fake.last) == "DELETE Groups('Planners')"


# SessionService


def test_sessions(api: Api, fake: FakeTm1) -> None:
    sessions = api.client.sessions
    fake.respond = lambda r: {"value": [{"ID": 8, "Context": "Nightly load", "User": {"Name": "alice"}}]}
    query = ODataQuery.none().select(Session.ID, Session.CONTEXT).expand(Session.USER, lambda u: u.select(User.NAME))
    (only,) = api.run(sessions.list(query))
    assert (only.id, only.user.name, only.context) == (8, "alice", "Nightly load")
    assert target(fake.last) == "GET Sessions?$select=ID,Context&$expand=User($select%3DName)"
    fake.respond = lambda r: {"ID": 8}
    api.run(sessions.get(8))
    assert target(fake.last) == "GET Sessions(8)"
    api.run(sessions.active_session(ODataQuery.none().select(Session.ID)))
    assert target(fake.last) == "GET ActiveSession?$select=ID"
    fake.respond = lambda r: {"Name": "alice"}
    api.run(sessions.user(8))
    assert target(fake.last) == "GET Sessions(8)/User"
    fake.respond = lambda r: {"value": []}
    api.run(sessions.threads(8))
    assert target(fake.last) == "GET Sessions(8)/Threads"
    fake.respond = lambda r: None
    api.run(sessions.close(8))
    assert (target(fake.last), body(fake.last)) == ("POST Sessions(8)/tm1.Close", {})


# ThreadService


def test_threads(api: Api, fake: FakeTm1) -> None:
    threads = api.client.threads
    fake.respond = lambda r: {"value": [{"ID": 5, "Function": "POST /api/v1/Processes('Sales.Load')/tm1.ExecuteWithReturn"}]}
    found = api.run(
        threads.list(
            ODataQuery.none()
            .select(Thread.ID)
            .filter(Filter.contains(Thread.FUNCTION, "Processes('Sales.Load')/tm1.ExecuteWithReturn"))
        )
    )
    assert found[0].id == 5
    assert target(fake.last).startswith("GET Threads?$select=ID&$filter=contains(Function,")
    fake.respond = lambda r: {"ID": 5}
    api.run(threads.get(5))
    assert target(fake.last) == "GET Threads(5)"
    fake.respond = lambda r: {"ID": 8}
    assert api.run(threads.session(5)).id == 8
    assert target(fake.last) == "GET Threads(5)/Session"
    fake.respond = lambda r: None
    api.run(threads.cancel(5))
    assert (target(fake.last), body(fake.last)) == ("POST Threads(5)/tm1.CancelOperation", {})
    # The service still has its session
    assert threads._session is api.client.session


# OidcKeyService


def test_oidc_keys(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": ["k1", "k2"]}
    keys = api.run(api.client.oidc_keys.keys())
    assert keys == ["k1", "k2"]
    assert target(fake.last) == "GET GetOIDCKeys()"
    fake.respond = lambda r: None
    api.run(api.client.oidc_keys.remove(keys))
    assert (target(fake.last), body(fake.last)) == ("POST RemoveOIDCKeyFromCache", {"KeyId": ["k1", "k2"]})


def test_a_key_tm1_hasnt_cached_reads_as_not_found(api: Api, fake: FakeTm1) -> None:
    # TM1 answers 400 with ObjectNotFound (code 55)
    fake.respond = lambda r: httpx.Response(400, json={"error": {"code": "55", "message": "ObjectNotFound"}})
    with pytest.raises(Tm1NotFoundError):
        api.run(api.client.oidc_keys.remove(["nope"]))
