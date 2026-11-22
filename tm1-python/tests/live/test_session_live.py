"""The session runtime against a real TM1, ported from the Java client's Tm1SessionTm1Test,
Tm1SessionImpersonationTm1Test, Tm1SessionPoolTm1Test, Tm1CapabilitiesTm1Test,
Tm1SessionAsyncTm1Test, Tm1BatchTm1Test and Tm1ClientTm1Test. Skipped unless ``TM1_URL`` is
set (see conftest.py); they need an administrator's credentials, as the Java tests do."""

from __future__ import annotations

import asyncio
import json
import time
import uuid
from typing import Any, Iterator

import pytest

from tm1_client import (
    ODataPath,
    ODataQuery,
    Tm1ApiError,
    Tm1AuthenticationError,
    Tm1BadRequestError,
    Tm1Capability,
    Tm1ErrorKind,
    Tm1ForbiddenError,
    Tm1Identity,
    Tm1NotFoundError,
    Tm1Request,
    Tm1Session,
    Tm1SessionPool,
    Tm1Timeouts,
    Tm1UnsupportedOperationError,
    Tm1Version,
    auth,
)

from ..conftest import SNAPSHOT
from .conftest import CONFIGURED, REASON, _builder, client, target

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name"), dict)


def session(**settings: Any) -> Tm1Session:
    builder = _builder(Tm1Session.builder())
    for name, value in settings.items():
        builder = getattr(builder, name)(value)
    return builder.build()


@pytest.fixture(scope="module")
def admin() -> Iterator[Tm1Session]:
    with session() as s:
        yield s


def session_ids(admin: Tm1Session) -> set[int]:
    return {s["ID"] for s in admin.collect(Tm1Request.list(ODataPath.of("Sessions"), ODataQuery.none().select("ID"), dict))}


def gone(admin: Tm1Session, session_id: int, within: float = 30) -> bool:
    """TM1 lists a closed session for a few seconds more."""
    end = time.monotonic() + within
    while time.monotonic() < end:
        if session_id not in session_ids(admin):
            return True
        time.sleep(0.5)
    return False


# Tm1SessionTm1Test


def test_reads_the_server_and_closes_its_session_leaving_none_behind(admin: Tm1Session) -> None:
    s = session()
    assert s.execute(SERVER)["Name"]
    sid = s.session_id()
    assert sid in session_ids(admin)
    s.close()
    assert gone(admin, sid)


def test_shares_one_login_between_concurrent_first_requests(admin: Tm1Session) -> None:
    async def run() -> set[int]:
        from tm1_client.aio import AsyncTm1Session

        async with _builder(AsyncTm1Session.builder()).build() as s:
            await asyncio.gather(*(s.execute(SERVER) for _ in range(5)))
            return {await s.session_id()}

    assert len(asyncio.run(run())) == 1


def test_logs_in_again_after_its_session_is_killed_and_runs_the_post_once(admin: Tm1Session) -> None:
    name = "tm1py_test_" + uuid.uuid4().hex[:8]
    with session() as victim:
        victim.connect()
        admin.execute(Tm1Request.post(ODataPath.of("Sessions").key(victim.session_id()).action("tm1.Close"), None))
        victim.execute(Tm1Request.post(ODataPath.of("Dimensions"), {"Name": name}, dict))
        try:
            assert admin.execute(Tm1Request.get(ODataPath.of("Dimensions").key(name), ODataQuery.none().select("Name"), dict))["Name"] == name
        finally:
            admin.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(name)))


def test_labels_the_session_with_its_context() -> None:
    with session(session_context="tm1py-live") as s:
        active = s.execute(Tm1Request.get(ODataPath.of("ActiveSession"), ODataQuery.none().select("Context"), dict))
        assert active["Context"] == "tm1py-live"


def test_runs_requests_in_the_views_sandbox(admin: Tm1Session) -> None:
    name = "tm1py_test_" + uuid.uuid4().hex[:8]
    admin.execute(Tm1Request.post(ODataPath.of("Sandboxes"), {"Name": name}, dict))
    try:
        view = admin.with_sandbox(name)
        assert view.execute(Tm1Request.get(ODataPath.of("Sandboxes").key(name), ODataQuery.none(), dict))["Name"] == name
    finally:
        admin.execute(Tm1Request.delete(ODataPath.of("Sandboxes").key(name)))


def test_streams_a_collection(admin: Tm1Session) -> None:
    names = [d["Name"] for d in admin.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), dict))]
    assert "}Clients" in names


def test_reports_a_missing_object_as_not_found(admin: Tm1Session) -> None:
    with pytest.raises(Tm1NotFoundError):
        admin.execute(Tm1Request.get(ODataPath.of("Cubes").key("tm1py_no_such_cube"), ODataQuery.none(), dict))


# Tm1SessionImpersonationTm1Test


@pytest.fixture(scope="module")
def user(admin: Tm1Session) -> Iterator[str]:
    name = "tm1py_user_" + uuid.uuid4().hex[:6]
    admin.execute(Tm1Request.post(ODataPath.of("Users"), {"Name": name, "Password": "pw"}, dict))
    yield name
    admin.execute(Tm1Request.delete(ODataPath.of("Users").key(name)))


def test_runs_as_the_impersonated_user(user: str) -> None:
    with session(impersonate=user) as s:
        me = s.execute(Tm1Request.get(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"), dict))
        assert me["Name"].lower() == user.lower()


def test_reports_an_unknown_user() -> None:
    with session(impersonate="tm1py_nobody_" + uuid.uuid4().hex[:6]) as s, pytest.raises(Tm1BadRequestError) as refused:
        s.execute(SERVER)
    assert refused.value.kind is Tm1ErrorKind.UNKNOWN_USER


def test_reports_that_administrators_cant_be_impersonated() -> None:
    with session(impersonate="Admin") as s, pytest.raises(Tm1ForbiddenError) as refused:
        s.execute(SERVER)
    assert refused.value.kind is Tm1ErrorKind.IMPERSONATION_REFUSED


def test_reports_a_disabled_user_as_refused_credentials(admin: Tm1Session, user: str) -> None:
    admin.execute(Tm1Request.patch(ODataPath.of("Users").key(user), {"Enabled": False}))
    try:
        with session(impersonate=user) as s, pytest.raises(Tm1AuthenticationError) as refused:
            s.execute(SERVER)
        assert refused.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    finally:
        admin.execute(Tm1Request.patch(ODataPath.of("Users").key(user), {"Enabled": True}))


def test_doesnt_log_in_again_while_a_refusal_is_remembered() -> None:
    builder = Tm1Session.builder().base_url(target()[0]).authenticator(auth.basic("tm1py_nobody", "wrong"))
    with builder.timeouts(Tm1Timeouts.builder().refused_login(60).build()).build() as s:
        with pytest.raises(Tm1AuthenticationError) as first:
            s.execute(SERVER)
        with pytest.raises(Tm1AuthenticationError) as second:
            s.execute(SERVER)
        assert second.value is first.value, "the same refusal, without another login"


# Tm1SessionPoolTm1Test


def test_runs_each_identity_as_itself_and_never_falls_back(user: str) -> None:
    pool = Tm1SessionPool.builder(_builder(Tm1Session.builder())).build()
    try:
        me = Tm1Request.get(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"), dict)
        assert pool.session(Tm1Identity.impersonating(user)).execute(me)["Name"].lower() == user.lower()
        assert pool.session().execute(me)["Name"] != user
        with pytest.raises(Tm1ApiError):
            pool.session(Tm1Identity.impersonating("tm1py_nobody_" + uuid.uuid4().hex[:6])).execute(me)
    finally:
        pool.close()


def test_closes_evicted_sessions_on_tm1_and_leaves_none_behind(admin: Tm1Session) -> None:
    now = [0.0]
    pool = Tm1SessionPool.builder(_builder(Tm1Session.builder())).clock(lambda: now[0]).close_grace(0).build()
    first = pool.session()
    first.execute(SERVER)
    sid = first.session_id()
    now[0] += 3600
    pool.session()
    pool.evict_expired()
    assert first.is_closed and gone(admin, sid)
    second = pool.session()
    second.execute(SERVER)
    sid2 = second.session_id()
    pool.close()
    assert gone(admin, sid2)


# Tm1CapabilitiesTm1Test


def test_reads_the_version_and_the_capabilities_the_snapshot_describes(admin: Tm1Session) -> None:
    version = admin.version()
    assert version.is_at_least(Tm1Version.parse("11.0"))
    caps = admin.capabilities()
    assert caps.product_version == version
    if caps.schema_version == "11.8.35":
        from tm1_client.version import Tm1Capabilities

        assert caps.endpoints == Tm1Capabilities.parse(SNAPSHOT.read_bytes()).endpoints


def test_refuses_a_request_the_server_doesnt_define(admin: Tm1Session) -> None:
    bananas = Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), dict).requires(Tm1Capability.entity_set("Bananas"))
    with pytest.raises(Tm1UnsupportedOperationError):
        admin.collect(bananas)


# Tm1SessionAsyncTm1Test


def test_runs_a_process_through_the_monitor(admin: Tm1Session) -> None:
    name = "tm1py_test_" + uuid.uuid4().hex[:8]
    admin.execute(Tm1Request.post(ODataPath.of("Processes"), {"Name": name, "PrologProcedure": "Sleep(1500);"}, dict))
    try:
        run = Tm1Request.post(ODataPath.of("Processes").key(name).action("tm1.ExecuteWithReturn"), {}, dict)
        result = admin.with_async(0.5).execute(run)
        assert result["ProcessExecuteStatusCode"] == "CompletedSuccessfully"
        cubes = admin.with_async(0.5).collect(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none().select("Name").top(3), dict))
        assert len(cubes) <= 3
    finally:
        admin.execute(Tm1Request.delete(ODataPath.of("Processes").key(name)))


# Tm1BatchTm1Test


def test_runs_reads_writes_and_failures_together(admin: Tm1Session) -> None:
    name = "tm1py_test_" + uuid.uuid4().hex[:8]
    with admin.batch() as batch:
        created = batch.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), {"Name": name}, dict))
        read = batch.session.execute(Tm1Request.get(ODataPath.of("Dimensions").key(name), ODataQuery.none().select("Name"), dict))
        missing = batch.session.execute(Tm1Request.get(ODataPath.of("Cubes").key("tm1py_none"), ODataQuery.none(), dict))
        deleted = batch.session.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(name)))
    assert created.result()["Name"] == name
    assert read.result()["Name"] == name, "a later request sees an earlier one's write"
    with pytest.raises(Tm1NotFoundError):
        missing.result()
    assert deleted.result() is None


def test_takes_a_thousand_requests(admin: Tm1Session) -> None:
    batch = admin.batch()
    results = [batch.session.execute(SERVER) for _ in range(1000)]
    batch.run(1000)
    assert all(r.result()["Name"] for r in results)


# Tm1ClientTm1Test


def test_connects_reads_the_server_and_closes() -> None:
    with client() as tm1:
        tm1.connect()
        assert json.dumps(tm1.session.execute(SERVER))
    assert tm1.session.is_closed
