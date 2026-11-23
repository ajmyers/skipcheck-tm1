"""User, group, session, thread, OIDC key and SQL data source services against a real TM1:
ports of the Java client's UserAndGroupTm1Test, SessionAndThreadTm1Test and
OidcKeyAndSqlDataSourceTm1Test (see conftest.py for how to point them at a server)."""

from __future__ import annotations

import os
import threading
import time
from collections.abc import Iterator
from typing import Any

import pytest

from tm1_client import (
    ODataPath,
    Tm1Client,
    Tm1Error,
    Tm1NotFoundError,
    Tm1Request,
    Tm1ServerError,
    auth,
)
from tm1_client.model import ProcessPatch, UserPatch, UserType

from .conftest import CONFIGURED, REASON, _builder

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

ADMIN = os.environ.get("TM1_USER", "admin")


def other_client(**settings: Any) -> Tm1Client:
    """Another client of the configured TM1: as the admin, or as ``user``/``password``."""
    builder = _builder(Tm1Client.builder())
    if "user" in settings:
        builder = builder.authenticator(auth.basic(settings["user"], settings["password"]))
    if "session_context" in settings:
        builder = builder.session_context(settings["session_context"])
    return builder.build()


# UserAndGroupTm1Test


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
    tm1.users.create(UserPatch.builder().name(p.user).password("pw").bind_groups(ODataPath.of("Groups").key(p.g1)).build())
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
    with pytest.raises(ValueError, match="set_groups"):
        tm1.users.update(people.user, UserPatch.builder().bind_groups(ODataPath.of("Groups").key(people.g1)).build())


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


# SessionAndThreadTm1Test


def test_reads_sessions_their_users_and_threads_and_closes_another(tm1: Tm1Client, name: str) -> None:
    other = other_client(session_context=name)
    try:
        other.connect()
        id = other.sessions.active_session().id
        assert tm1.sessions.active_session().id != id, "each client its own"
        assert id in [s.id for s in tm1.sessions.list()]
        assert tm1.sessions.get(id).context == name
        assert tm1.sessions.user(id).name.lower() == ADMIN.lower()
        assert tm1.sessions.threads(id) is not None
        assert id in [s.id for s in tm1.users.sessions(ADMIN)]

        tm1.sessions.close(id)
        assert other.sessions.active_session().id != id, "the other client logs in again, as a new session"
    finally:
        other.close()


def test_cancels_a_running_process_through_its_thread(tm1: Tm1Client, name: str) -> None:
    tm1.processes.create(ProcessPatch.builder().name(name).prolog_procedure("i = 0;\nWhile(i < 100000000);\n  i = i + 1;\nEnd;\n").build())
    runner = other_client()
    outcome: list[BaseException | object] = []

    def run() -> None:
        try:
            outcome.append(runner.processes.execute_with_return(name))
        except BaseException as e:  # noqa: BLE001
            outcome.append(e)

    worker = threading.Thread(target=run)
    worker.start()
    try:
        running = None
        for _ in range(100):
            running = next(
                (t for t in tm1.threads.list() if t.function and f"Processes('{name}')/tm1.ExecuteWithReturn" in t.function),
                None,
            )
            if running is not None:
                break
            time.sleep(0.1)
        assert running is not None, "the process's thread"
        assert running.object_name == "", "a process run through REST shows as its request"
        assert name in tm1.threads.get(running.id).function
        assert tm1.threads.session(running.id).id is not None
        tm1.threads.cancel(running.id)
        worker.join(60)
        (result,) = outcome
        # The run's request fails, rather than reporting Aborted
        assert isinstance(result, Tm1ServerError) and "Cancel" in str(result)
    finally:
        worker.join(120)
        try:
            runner.close()
        except Tm1Error:
            pass
        tm1.processes.delete(name)


# OidcKeyAndSqlDataSourceTm1Test


def test_reads_and_clears_the_oidc_key_cache(tm1: Tm1Client) -> None:
    assert tm1.oidc_keys.keys() == []
    tm1.oidc_keys.remove([])
    with pytest.raises(Tm1NotFoundError, match="ObjectNotFound"):
        # TM1 answers 400 ObjectNotFound, read as not found
        tm1.oidc_keys.remove(["not-cached"])


def test_lists_the_hosts_odbc_sources(tm1: Tm1Client) -> None:
    assert tm1.sql_data_sources.list() == []
    with pytest.raises(Tm1NotFoundError):
        tm1.sql_data_sources.get("none")
