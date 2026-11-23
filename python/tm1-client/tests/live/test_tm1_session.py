"""Tm1Session against a real TM1: logging in, requests, and leaving no session behind. A port of
the Java client's Tm1SessionTm1Test; they need an administrator's credentials, as the Java tests
do."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1ApiError, Tm1Error, Tm1NotFoundError, Tm1Request, Tm1SessionClosedError
from skipcheck_tm1.aio import AsyncTm1Session

from .conftest import CONFIGURED, REASON, _builder, session, started_version
from .raw_tm1 import RawTm1

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"), dict)


@pytest.fixture
def context() -> str:
    """A label for this test's sessions, so the leak checks count only them."""
    return RawTm1.unique_context()


def test_reads_the_server_and_closes_its_session_leaving_none_behind(raw: RawTm1, admin: str, context: str) -> None:
    s = session(session_context=context)

    tm1 = s.execute(SERVER)
    version = started_version()
    if version is None:
        assert tm1["ProductVersion"]
    else:
        assert tm1["ProductVersion"] == str(version)
    id = s.session_id()
    assert raw.session_count(admin, context) == 1

    s.close()
    assert raw.await_gone(admin, id)
    assert raw.await_session_count(admin, context, 0) == 0


def test_shares_one_login_between_concurrent_first_requests(raw: RawTm1, admin: str, context: str) -> None:
    async def run() -> tuple[list[Any], int]:
        s = _builder(AsyncTm1Session.builder()).session_context(context).build()
        try:
            results = await asyncio.gather(*(s.execute(SERVER) for _ in range(8)))
            return results, raw.session_count(admin, context)
        finally:
            await s.close()

    results, sessions = asyncio.run(run())
    assert len(results) == 8
    assert sessions == 1
    assert raw.await_session_count(admin, context, 0) == 0


def test_leaves_no_session_when_close_races_the_first_request(raw: RawTm1, admin: str, context: str) -> None:
    async def run() -> None:
        s = _builder(AsyncTm1Session.builder()).session_context(context).build()

        async def read() -> None:
            try:
                await s.execute(SERVER)
            except (Tm1Error, Tm1SessionClosedError):  # whichever lost the race
                pass

        await asyncio.gather(read(), s.close())

    asyncio.run(run())
    assert raw.await_session_count(admin, context, 0) == 0


def test_logs_in_again_after_its_session_is_killed_and_runs_the_post_once(raw: RawTm1, admin: str, context: str) -> None:
    """The replay the client relies on, end to end: the session is killed from another session,
    then a POST through the client logs in again and creates the dimension exactly once, and the
    session TM1 opened for the 401 is the one logged in, so nothing is left behind."""
    s = session(session_context=context)
    name = "zz_session_" + uuid.uuid4().hex[:8]
    try:
        first = s.session_id()
        assert raw.kill(admin, first) == 204

        s.execute(Tm1Request.post(ODataPath.of("Dimensions"), {"Name": name, "Hierarchies": [{"Name": name}]}, None))

        assert raw.send("GET", f"Dimensions/$count?$filter=Name%20eq%20'{name}'", cookie=admin).text == "1"
        assert s.session_id() != first
    finally:
        raw.send("DELETE", f"Dimensions('{name}')", cookie=admin)
        s.close()
    assert raw.await_session_count(admin, context, 0) == 0


def test_labels_the_session_with_its_context() -> None:
    with session(session_context="tm1-client test") as s:
        active = s.execute(Tm1Request.get(ODataPath.of("ActiveSession"), ODataQuery.none().select("Context"), dict))
        assert active["Context"] == "tm1-client test"


def test_runs_requests_in_the_views_sandbox(context: str) -> None:
    """TM1 reads ``!sandbox`` on any request: a sandbox that exists works, one that doesn't fails."""
    sandbox = "zz_session_" + uuid.uuid4().hex[:8]
    with session(session_context=context) as s:
        try:
            s.execute(Tm1Request.post(ODataPath.of("Sandboxes"), {"Name": sandbox}, None))
            assert s.with_sandbox(sandbox).execute(SERVER) is not None
            with pytest.raises(Tm1ApiError):
                s.with_sandbox(sandbox + "_missing").execute(SERVER)
        finally:
            try:
                s.execute(Tm1Request.delete(ODataPath.of("Sandboxes").key(sandbox)))
            except Tm1Error:
                pass


def test_streams_a_collection(context: str) -> None:
    with session(session_context=context) as s:
        names = [d["Name"] for d in s.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), dict))]
        assert names and all(n.strip() for n in names)


def test_reports_a_missing_object_as_not_found(context: str) -> None:
    with session(session_context=context) as s, pytest.raises(Tm1NotFoundError):
        s.execute(Tm1Request.get(ODataPath.of("Cubes").key("zz_no_such_cube"), ODataQuery.none(), dict))
