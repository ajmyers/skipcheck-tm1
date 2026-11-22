"""The session pool and identities, ported from the Java client's Tm1SessionPoolTest, in both
flavors. Where Java's scheduler closes an evicted session after its grace period on its own,
these tests call ``evict_expired()``, which is when this pool does it."""

from __future__ import annotations

from typing import Any

import pytest

from tm1_client import Tm1Identity, Tm1SessionClosedError, auth

from ..conftest import target
from .conftest import SERVER, Clock, Runtime

CLOSE = "POST ActiveSession/tm1.Close"


@pytest.fixture
def clock() -> Clock:
    return Clock()


def pool(rt: Runtime, clock: Clock, **settings: Any) -> Any:
    builder = rt.Pool.builder(rt.builder()).clock(clock)
    for name, value in settings.items():
        builder = getattr(builder, name)(value)
    return builder.build()


def closes(rt: Runtime) -> int:
    return sum(1 for r in rt.fake.requests if target(r) == CLOSE)


def test_keeps_one_session_per_identity_matching_names_as_tm1_does(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    alice = p.session(Tm1Identity.impersonating("alice"))
    assert p.session(Tm1Identity.impersonating("ALICE")) is alice
    assert p.session(Tm1Identity.impersonating("al ice")) is alice
    assert p.session(Tm1Identity.impersonating("bob")) is not alice
    assert p.session() is not alice
    assert p.stats().held == 3
    assert rt.fake.requests == [] and rt.fake.login_requests == [], "getting a session sends nothing"


def test_logs_each_identity_in_as_itself(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    rt.run(p.session(Tm1Identity.impersonating("alice")).execute(SERVER))
    rt.run(p.session().execute(SERVER))
    rt.run(p.session(Tm1Identity.with_credentials("carol", auth.basic("carol", "c"))).execute(SERVER))
    logins = rt.fake.login_requests
    assert [r.headers.get("tm1-impersonate") for r in logins] == ["alice", None, None]
    assert [r.headers["authorization"] for r in logins] == [
        "Basic YWRtaW46YXBwbGU=",
        "Basic YWRtaW46YXBwbGU=",
        "Basic Y2Fyb2w6Yw==",
    ]


def test_evicts_an_idle_session_and_closes_it_after_the_grace_period(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    alice = p.session(Tm1Identity.impersonating("alice"))
    rt.run(alice.execute(SERVER))
    clock.advance(600)
    fresh = p.session(Tm1Identity.impersonating("alice"))
    assert fresh is not alice
    assert p.stats().evicted_idle == 1
    rt.run(p.evict_expired())
    assert not alice.is_closed, "still open during the grace period"
    clock.advance(30)
    rt.run(p.evict_expired())
    assert alice.is_closed
    assert closes(rt) == 1
    assert p.stats().closed == 1 and p.stats().closing == 0


def test_keeps_a_session_thats_in_use(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    alice = p.session(Tm1Identity.impersonating("alice"))
    for _ in range(5):
        clock.advance(9 * 60)
        assert p.session(Tm1Identity.impersonating("alice")) is alice


def test_evicts_a_session_past_its_maximum_age_even_if_in_use(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock, max_age=30 * 60)
    alice = p.session(Tm1Identity.impersonating("alice"))
    for _ in range(3):
        clock.advance(9 * 60)
        p.session(Tm1Identity.impersonating("alice"))
    clock.advance(4 * 60)
    assert p.session(Tm1Identity.impersonating("alice")) is not alice
    assert p.stats().evicted_age == 1


def test_evicts_the_least_recently_used_session_when_full(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock, max_size=2)
    alice = p.session(Tm1Identity.impersonating("alice"))
    bob = p.session(Tm1Identity.impersonating("bob"))
    p.session(Tm1Identity.impersonating("alice"))
    p.session(Tm1Identity.impersonating("carol"))
    assert p.stats().held == 2
    assert p.stats().evicted_size == 1
    assert p.session(Tm1Identity.impersonating("alice")) is alice
    assert p.session(Tm1Identity.impersonating("bob")) is not bob


def test_sweeps_expired_sessions(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    alice = p.session(Tm1Identity.impersonating("alice"))
    rt.run(alice.execute(SERVER))
    clock.advance(11 * 60)
    rt.run(p.evict_expired())
    assert p.stats().held == 0
    clock.advance(30)
    rt.run(p.evict_expired())
    assert alice.is_closed


def test_closes_every_session_at_once_including_those_in_their_grace_period(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    alice = p.session(Tm1Identity.impersonating("alice"))
    rt.run(alice.execute(SERVER))
    clock.advance(600)
    fresh = p.session(Tm1Identity.impersonating("alice"))
    rt.run(fresh.execute(SERVER))
    rt.run(p.close())
    assert alice.is_closed and fresh.is_closed
    assert closes(rt) == 2
    with pytest.raises(Tm1SessionClosedError):
        p.session()


def test_refuses_identities_tm1_would_misread() -> None:
    with pytest.raises(ValueError):
        Tm1Identity.impersonating(" ")
    with pytest.raises(ValueError):
        Tm1Identity.with_credentials("", auth.basic("x", "y"))
    assert Tm1Identity.impersonating("Alice") == Tm1Identity.impersonating("alice")
    assert Tm1Identity.impersonating("alice") != Tm1Identity.with_credentials("alice", auth.basic("alice", "a"))
    assert "secret" not in repr(Tm1Identity.with_credentials("carol", auth.basic("carol", "secret")))
    assert repr(Tm1Identity.service_account()) == "Tm1Identity[service account]"


def test_refuses_settings_that_make_no_sense(rt: Runtime) -> None:
    builder = rt.Pool.builder(rt.builder())
    with pytest.raises(ValueError):
        builder.max_size(0)
    with pytest.raises(ValueError):
        builder.idle_timeout(0)
    with pytest.raises(ValueError):
        builder.close_grace(-1)
