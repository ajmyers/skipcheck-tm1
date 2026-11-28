# SPDX-License-Identifier: Apache-2.0

"""Tm1SessionPool against a real TM1: identities, eviction and close. A port of the Java client's
Tm1SessionPoolTm1Test."""

from __future__ import annotations

import time
import uuid
from collections.abc import Iterator

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1ApiError, Tm1ErrorKind, Tm1Identity, Tm1Request, Tm1Session, Tm1SessionPool, auth

from .conftest import CONFIGURED, IS_ENGINE, REASON, _builder, authenticator
from .raw_tm1 import RawTm1

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

ACTIVE_USER = Tm1Request.get(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"), dict)


@pytest.fixture
def context() -> str:
    return RawTm1.unique_context()


@pytest.fixture
def people(raw: RawTm1, admin: str) -> Iterator[tuple[str, str]]:
    """Two users who aren't administrators: alice (password a) and bob (password b)."""
    run = uuid.uuid4().hex[:6]
    alice, bob = "zz_alice_" + run, "zz_bob_" + run
    raw.create_user(admin, alice, "a")
    raw.create_user(admin, bob, "b")
    try:
        yield alice, bob
    finally:
        raw.delete_user(admin, alice)
        raw.delete_user(admin, bob)


@pytest.fixture
def pool(people: tuple[str, str], context: str) -> Iterator[Tm1SessionPool]:
    # After people, so it's closed before the users are deleted
    made = Tm1SessionPool.builder(_builder(Tm1Session.builder()).session_context(context)).idle_timeout(1).close_grace(0).sweep_interval(3600).build()
    try:
        yield made
    finally:
        made.close()


def name(s: Tm1Session) -> str:
    return s.execute(ACTIVE_USER)["Name"]


def _identity(user: str, password: str) -> Tm1Identity:
    return Tm1Identity.with_credentials(user, authenticator(user, password)) if IS_ENGINE else Tm1Identity.impersonating(user)


def test_runs_each_identity_as_itself(pool: Tm1SessionPool, people: tuple[str, str], raw: RawTm1) -> None:
    alice, bob = people
    if IS_ENGINE:
        assert name(pool.session(_identity(alice, "a"))) == alice
        assert name(pool.session(_identity(bob, "b"))) == bob
        assert name(pool.session()).lower() == raw.user.lower()
        assert pool.stats().held == 3
        return
    assert name(pool.session(Tm1Identity.impersonating(alice))) == alice
    assert name(pool.session(Tm1Identity.impersonating(bob))) == bob
    assert name(pool.session(Tm1Identity.with_credentials(bob, auth.basic(bob, "b")))) == bob
    assert name(pool.session()).lower() == raw.user.lower()
    assert pool.stats().held == 4


def test_closes_evicted_sessions_on_tm1(pool: Tm1SessionPool, people: tuple[str, str], raw: RawTm1, admin: str) -> None:
    alice, _ = people
    s = pool.session(_identity(alice, "a"))
    assert name(s) == alice
    id = s.session_id()

    time.sleep(1.1)
    pool.evict_expired()
    assert raw.await_gone(admin, id)
    assert pool.stats().closed == 1


def test_leaves_no_session_behind_when_closed(pool: Tm1SessionPool, people: tuple[str, str], raw: RawTm1, admin: str, context: str) -> None:
    alice, bob = people
    name(pool.session(_identity(alice, "a")))
    name(pool.session(_identity(bob, "b")))
    pool.close()
    assert raw.await_session_count(admin, context, 0) == 0


def test_never_falls_back_to_another_identity(pool: Tm1SessionPool) -> None:
    """Asking for an administrator fails; nothing runs as the pool's own user instead."""
    if IS_ENGINE:
        pytest.skip("TM1 v12 does not support TM1-Impersonate")
    with pytest.raises(Tm1ApiError) as refused:
        pool.session(Tm1Identity.impersonating("Admin")).execute(ACTIVE_USER)
    assert refused.value.kind is Tm1ErrorKind.IMPERSONATION_REFUSED
