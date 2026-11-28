# SPDX-License-Identifier: Apache-2.0

"""The session pool and identities, ported from the Java client's Tm1SessionPoolTest, in both
flavors. Where Java's scheduler closes an evicted session after its grace period on its own,
these tests call ``evict_expired()``, which is when this pool does it."""

from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Dialect, Tm1Identity, Tm1Request, Tm1SessionClosedError, auth
from skipcheck_tm1.aio import cognos as aio_cognos

from ..conftest import BASE, target
from .conftest import SERVER, Clock, Runtime

CLOSE = "POST ActiveSession/tm1.Close"


@pytest.fixture
def clock() -> Clock:
    return Clock()


def pool(rt: Runtime, clock: Clock, sessions: Any = None, **settings: Any) -> Any:
    builder = rt.Pool.builder(sessions or rt.builder()).clock(clock)
    for name, value in settings.items():
        builder = getattr(builder, name)(value)
    return builder.build()


def closes(rt: Runtime) -> int:
    return sum(1 for r in rt.fake.requests if target(r) == CLOSE)


class Held:
    """A fake TM1 that doesn't answer ``GET Server?$select=Name`` until it's told to, and a request
    for it that runs meanwhile: Java's ``Sinks.One`` of a response, and its ``subscribe``."""

    def __init__(self, rt: Runtime) -> None:
        self.rt = rt
        self.result: list[Any] = []
        self.started: Any
        self.release: Any
        if rt.flavor == "sync":
            self.started, self.release = threading.Event(), threading.Event()

            def handler(request: httpx2.Request) -> httpx2.Response:
                if target(request) == "GET Server?$select=Name":
                    self.started.set()
                    assert self.release.wait(10)
                return rt.fake.handler(request)

            self.http: Any = httpx2.Client(transport=httpx2.MockTransport(handler))
        else:
            self.started, self.release = asyncio.Event(), asyncio.Event()

            async def waiting(request: httpx2.Request) -> httpx2.Response:
                if target(request) == "GET Server?$select=Name":
                    self.started.set()
                    await self.release.wait()
                return rt.fake.handler(request)

            self.http = httpx2.AsyncClient(transport=httpx2.MockTransport(waiting))
        rt.clients.append(self.http)

    def sessions(self) -> Any:
        return self.rt.Session.builder().base_url(BASE).basic_auth("admin", "apple").http_client(self.http)

    def start(self, session: Any) -> None:
        """Sends the request, and returns once TM1 has it."""
        if self.rt.loop is None:
            self.thread = threading.Thread(target=lambda: self.result.append(session.execute(SERVER)), daemon=True)
            self.thread.start()
            assert self.started.wait(5)
        else:
            self.task = self.rt.loop.create_task(session.execute(SERVER))
            self.rt.loop.run_until_complete(asyncio.wait_for(self.started.wait(), 5))

    def answer(self) -> None:
        """Lets TM1 answer, and waits for the request to end."""
        self.release.set()
        if self.rt.loop is None:
            self.thread.join(5)
        else:
            self.result.append(self.rt.loop.run_until_complete(self.task))


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


def test_gives_different_credentials_for_the_same_name_their_own_session(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    carol = p.session(Tm1Identity.with_credentials("carol", auth.basic("carol", "c")))
    assert p.session(Tm1Identity.with_credentials("Carol", auth.basic("carol", "c"))) is carol, "the same credentials, made again"
    rt.run(carol.execute(SERVER))

    other = p.session(Tm1Identity.with_credentials("carol", auth.basic("carol", "wrong")))
    assert other is not carol, "never the session someone else's credentials opened"
    rt.run(other.execute(SERVER))
    assert [r.headers["authorization"] for r in rt.fake.login_requests] == ["Basic Y2Fyb2w6Yw==", "Basic Y2Fyb2w6d3Jvbmc="], (
        "each logs in with what it was given"
    )


def test_shares_a_session_for_the_same_authenticator_when_its_credentials_cant_be_compared(rt: Runtime, clock: Clock) -> None:
    p = pool(rt, clock)
    passport = auth.cam_passport(lambda: "p1")
    dave = p.session(Tm1Identity.with_credentials("dave", passport))
    assert p.session(Tm1Identity.with_credentials("dave", passport)) is dave
    assert p.session(Tm1Identity.with_credentials("dave", auth.cam_passport(lambda: "p1"))) is not dave
    assert auth.cam_passport("p1") == auth.cam_passport("p1")
    assert auth.cam_passport("p1") != auth.cam_passport("p2")
    logon = auth.cognos_logon if rt.flavor == "sync" else aio_cognos.cognos_logon
    assert logon("http://cognos:9300", "LDAP", "dave", "d") == logon("http://cognos:9300", "LDAP", "dave", "d")
    assert logon("http://cognos:9300", "LDAP", "dave", "d") != logon("http://cognos:9300", "LDAP", "dave", "other")


def test_keeps_one_session_for_an_authenticator_of_the_callers_own_that_is_equal_but_cant_be_hashed(rt: Runtime, clock: Clock) -> None:
    # Not in the Java tests: a Java object always has a hash code, and a Python one that defines == alone has none
    class Token:
        def __init__(self, token: str) -> None:
            self.token = token

        def authorization(self) -> str:
            return "Bearer " + self.token

        def describe(self) -> str:
            return "Token"

        def __eq__(self, other: object) -> bool:
            return isinstance(other, Token) and self.token == other.token

    p = pool(rt, clock)
    erin = p.session(Tm1Identity.with_credentials("erin", Token("t1")))
    assert p.session(Tm1Identity.with_credentials("erin", Token("t1"))) is erin
    assert p.session(Tm1Identity.with_credentials("erin", Token("t2"))) is not erin


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


def test_keeps_a_session_while_a_request_is_running_on_it(rt: Runtime, clock: Clock) -> None:
    held = Held(rt)
    p = pool(rt, clock, sessions=held.sessions())
    alice = p.session(Tm1Identity.impersonating("alice"))
    held.start(alice)
    try:
        clock.advance(25 * 60)
        rt.run(p.evict_expired())
        assert p.stats().evicted_idle == 0, "a running request is use"
        assert not alice.is_closed
    finally:
        held.answer()
    assert held.result == [{}]
    clock.advance(12 * 60)
    rt.run(p.evict_expired())
    assert p.stats().evicted_idle == 1, "idle once it has finished"
    clock.advance(30)
    rt.run(p.evict_expired())
    assert alice.is_closed


def test_closes_an_evicted_session_only_once_its_requests_have_finished(rt: Runtime, clock: Clock) -> None:
    held = Held(rt)
    p = pool(rt, clock, sessions=held.sessions(), max_age=30 * 60)
    alice = p.session(Tm1Identity.impersonating("alice"))
    held.start(alice)
    try:
        clock.advance(40 * 60)
        rt.run(p.evict_expired())
        assert p.stats().evicted_age == 1, "out of the pool at its age"
        assert p.session(Tm1Identity.impersonating("alice")) is not alice, "so the next caller gets a new one"
        clock.advance(31)
        rt.run(p.evict_expired())
        assert not alice.is_closed, "but open for the request still running"
        assert p.stats().closing == 1
    finally:
        held.answer()
    assert held.result == [{}]
    rt.run(p.evict_expired())
    assert alice.is_closed
    assert p.stats().closing == 0


def test_counts_a_collection_as_running_until_its_iterator_is_finished_or_closed(rt: Runtime, clock: Clock) -> None:
    # Not in the Java tests, where a stream is one subscription: here its pages are separate requests
    pages = {"GET Cubes": {"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$skiptoken=2"}, "GET Cubes?$skiptoken=2": {"value": [{"Name": "b"}]}}
    rt.fake.respond = lambda r: pages[target(r)]
    session = pool(rt, clock).session()
    cubes = session.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), dict))
    step = (lambda: next(cubes)) if rt.flavor == "sync" else (lambda: rt.run(cubes.__anext__()))
    assert not session._is_busy(), "nothing is sent until the first item is asked for"
    assert step() == {"Name": "a"}
    assert session._is_busy(), "between two pages"
    assert step() == {"Name": "b"}
    rt.run(cubes.close() if rt.flavor == "sync" else cubes.aclose())
    assert not session._is_busy()


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


def test_sweeps_expired_sessions_on_its_own(rt: Runtime, clock: Clock) -> None:
    # Java's pool sweeps on its scheduler; this one sweeps while sweep_forever() runs, as a task or a thread
    p = pool(rt, clock, sweep_interval=0.01)
    alice = p.session(Tm1Identity.impersonating("alice"))
    rt.run(alice.execute(SERVER))
    if rt.loop is None:
        sweeper = threading.Thread(target=p.sweep_forever, daemon=True)
        sweeper.start()

        def wait() -> None:
            time.sleep(0.1)
    else:
        sweeper = rt.loop.create_task(p.sweep_forever())

        def wait() -> None:
            rt.run(asyncio.sleep(0.1))

    try:
        clock.advance(11 * 60)
        wait()
        assert p.stats().held == 0
        clock.advance(30)
        wait()
        assert alice.is_closed
    finally:
        rt.run(p.close())
        if rt.loop is None:
            sweeper.join(5)
        else:
            rt.run(asyncio.wait_for(sweeper, 5))


def test_observes_evictions_with_their_reason(rt: Runtime, clock: Clock, caplog: pytest.LogCaptureFixture) -> None:
    # Java observes evictions with Micrometer; this pool logs them to ``skipcheck_tm1`` with their reason
    caplog.set_level(logging.DEBUG, logger="skipcheck_tm1")
    p = pool(rt, clock)
    p.session(Tm1Identity.impersonating("alice"))
    clock.advance(10 * 60)
    p.session(Tm1Identity.impersonating("alice"))
    evictions = [r.getMessage() for r in caplog.records if r.name == "skipcheck_tm1" and r.getMessage().startswith("Evicting the TM1 session")]
    assert len(evictions) == 1
    assert "(idle)" in evictions[0]


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


def test_pools_per_user_application_sessions(rt: Runtime, clock: Clock) -> None:
    seen: list[httpx2.Request] = []
    db_root = "http://tm1.test/tm1/api/v1/Databases('sales')/"
    auth_url = "http://tm1.test/tm1/auth/v1/session"
    n = 0

    def handle(request: httpx2.Request) -> httpx2.Response:
        nonlocal n
        seen.append(request)
        url = str(request.url)
        if request.method == "POST" and url == auth_url:
            n += 1
            return httpx2.Response(201, headers={"Set-Cookie": f"TM1SessionId=u{n}; Path=/; HttpOnly"})
        if request.method == "GET" and url == db_root + "ActiveSession?$select=ID":
            return httpx2.Response(200, json={"ID": 1})
        if request.method == "GET" and url == db_root + "Server?$select=Name":
            return httpx2.Response(200, json={"Name": "sales"})
        if request.method == "POST" and url == "http://tm1.test/tm1/api/v1/ActiveSession/tm1s.Close":
            return httpx2.Response(204)
        return httpx2.Response(404)

    http = httpx2.Client(transport=httpx2.MockTransport(handle)) if rt.flavor == "sync" else httpx2.AsyncClient(transport=httpx2.MockTransport(handle))
    rt.clients.append(http)
    sessions = (
        rt.Session.builder()
        .dialect(Tm1Dialect.v12("sales"))
        .address("http://tm1.test")
        .authenticator(auth.application("svc", "secret", "default-user"))
        .http_client(http)
    )
    p = pool(rt, clock, sessions=sessions)
    try:
        alice = p.session(Tm1Identity.with_credentials("alice", auth.application("svc", "secret", "alice")))
        assert p.session(Tm1Identity.with_credentials("ALICE", auth.application("svc", "secret", "alice"))) is alice
        bob = p.session(Tm1Identity.with_credentials("bob", auth.application("svc", "secret", "bob")))
        assert bob is not alice
        with pytest.raises(ValueError):
            p.session(Tm1Identity.impersonating("charlie"))

        rt.run(alice.execute(SERVER))
        rt.run(bob.execute(SERVER))
        auth_bodies = [json.loads(r.content) for r in seen if str(r.url) == auth_url]
        assert auth_bodies == [{"User": "alice"}, {"User": "bob"}]
    finally:
        rt.run(p.close())
