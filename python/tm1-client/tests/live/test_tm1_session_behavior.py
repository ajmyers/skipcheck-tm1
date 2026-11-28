# SPDX-License-Identifier: Apache-2.0

"""Pins how TM1 handles sessions, which Tm1Session's design relies on: a port of the Java client's
Tm1SessionBehaviorTest.

These tests talk to TM1 over bare HTTP (RawTm1), not through the client, so they show what TM1
does rather than what the client does. If a TM1 version changes one of these behaviors, its test
fails, and the session code needs a version branch.
"""

from __future__ import annotations

import json
import socket
import ssl
import time
import uuid
from urllib.parse import urlsplit

import httpx2
import pytest

from .conftest import CONFIGURED, IS_ENGINE, REASON, target
from .raw_tm1 import RawTm1

pytestmark = [
    pytest.mark.skipif(not CONFIGURED, reason=REASON),
    pytest.mark.skipif(IS_ENGINE, reason="TM1 11 bare-HTTP session behavior tests"),
]


def dimension(name: str) -> str:
    return json.dumps({"Name": name, "Hierarchies": [{"Name": name}]})


def scratch_name() -> str:
    return "zz_session_" + uuid.uuid4().hex[:8]


def test_refuses_a_request_on_a_killed_session_without_running_it(raw: RawTm1, admin: str) -> None:
    """The client replays a request once after a 401 on an established session, POSTs included.
    That's only safe if TM1 refuses the request before running it. Checked with a cookie-only
    request: a request that also sent credentials would open a new session and succeed, and prove
    nothing."""
    victim = raw.login()
    name = scratch_name()
    create = dimension(name)
    try:
        assert raw.kill(admin, raw.session_id(victim)) == 204

        refused = raw.send("POST", "Dimensions", cookie=victim, body=create)
        assert refused.status_code == 401
        assert raw.send("GET", f"Dimensions('{name}')", cookie=admin).status_code == 404, "the refused POST must not have created the dimension"

        # Log in again, with the cookie the 401 handed out (see the next test), and replay
        relogged = RawTm1.cookie(refused)
        assert relogged is not None
        assert raw.send("GET", "ActiveSession?$select=ID", cookie=relogged, basic=True).status_code == 200
        assert raw.send("POST", "Dimensions", cookie=relogged, body=create).status_code == 201
        assert raw.send("GET", f"Dimensions/$count?$filter=Name%20eq%20'{name}'", cookie=admin).text == "1"
        raw.close(relogged)
    finally:
        raw.send("DELETE", f"Dimensions('{name}')", cookie=admin)


def test_opens_a_session_on_every401_and_logs_that_session_in_when_its_cookie_is_sent_with_credentials(raw: RawTm1, admin: str) -> None:
    """Every 401 opens a TM1 session with no user, and sets its cookie. Sending that cookie with
    the credentials logs that same session in, instead of opening another. So the client logs in
    again with the cookie from the 401, and a re-login doesn't leave a session behind."""
    before = raw.all_session_count(admin)
    anonymous = raw.send("GET", "ActiveSession?$select=ID")
    assert anonymous.status_code == 401
    assert anonymous.headers.get("WWW-Authenticate") == 'Basic realm="TM1"'
    cookie = RawTm1.cookie(anonymous)
    assert cookie is not None
    assert raw.all_session_count(admin) == before + 1, "sessions after one 401"

    login = raw.send("GET", "ActiveSession?$select=ID", cookie=cookie, basic=True)
    assert login.status_code == 200
    assert RawTm1.cookie(login) is None, "no new cookie: the same session was logged in"
    assert raw.all_session_count(admin) == before + 1, "sessions after logging in with the 401's cookie"

    raw.close(cookie)
    assert raw.all_session_count(admin) == before


def test_opens_a_session_for_an_anonymous_read_of_server(raw: RawTm1, admin: str) -> None:
    """``Server`` can be read with no credentials, but even that opens a session, which then sits
    there with no user until it times out. So the client never reads anything, the version
    included, outside its own session."""
    before = raw.all_session_count(admin)
    server = raw.send("GET", "Server?$select=Name")
    assert server.status_code == 200
    assert raw.all_session_count(admin) == before + 1
    cookie = RawTm1.cookie(server)
    assert cookie is not None
    raw.send("GET", "ActiveSession?$select=ID", cookie=cookie, basic=True)
    raw.close(cookie)


def test_refuses_a_closed_session_at_once_but_lists_it_for_seconds_more(raw: RawTm1, admin: str) -> None:
    """A closed session is refused at once, but once it has served requests on several
    connections, TM1 keeps listing it in ``Sessions`` for several seconds (5 to 9 s observed),
    until it has ended the threads that served it. So a check that no session is left behind has
    to wait for the count to settle."""
    cookie = raw.login()
    id = raw.session_id(cookie)
    reads = [raw.send_async("GET", "Server?$select=Name", cookie=cookie) for _ in range(8)]
    for read in reads:
        assert read.result().status_code == 200
    assert raw.send("POST", "ActiveSession/tm1.Close", cookie=cookie, body="{}").status_code == 204
    assert raw.send("GET", f"Sessions({id})", cookie=admin).status_code == 200, "still listed just after close"
    assert raw.await_gone(admin, id, 30), "gone within 30 s"


def test_expires_the_cookie_on_close_and_refuses_it_afterwards(raw: RawTm1) -> None:
    """Closing a session expires its cookie, and later requests with that cookie get a 401."""
    cookie = raw.login()
    close = raw.send("POST", "ActiveSession/tm1.Close", cookie=cookie, body="{}")
    assert close.status_code == 204
    assert any(c.startswith("TM1SessionId=") and "Max-Age=0" in c for c in close.headers.get_list("set-cookie"))

    after = raw.send("GET", "ActiveSession?$select=ID", cookie=cookie)
    assert after.status_code == 401
    # That 401 opened a session too; log it in and close it so the test leaves nothing behind
    leftover = RawTm1.cookie(after)
    assert leftover is not None
    raw.send("GET", "ActiveSession?$select=ID", cookie=leftover, basic=True)
    raw.close(leftover)


def test_runs_concurrent_requests_on_one_session_in_parallel(raw: RawTm1) -> None:
    """Requests on one session run in parallel, not one at a time: three one-second process runs
    finish in about one second. So one shared session doesn't serialize the client's traffic."""
    cookie = raw.login()
    process = scratch_name()
    try:
        assert raw.send("POST", "Processes", cookie=cookie, body={"Name": process, "PrologProcedure": "Sleep(1000);"}).status_code == 201

        start = time.monotonic()
        runs = [raw.send_async("POST", f"Processes('{process}')/tm1.ExecuteWithReturn", cookie=cookie, body="{}") for _ in range(3)]
        for run in runs:
            assert run.result().json()["ProcessExecuteStatusCode"] == "CompletedSuccessfully"
        assert time.monotonic() - start < 2.5, "three 1 s runs on one session"
    finally:
        raw.send("DELETE", f"Processes('{process}')", cookie=cookie)
        raw.close(cookie)


def test_labels_the_session_with_the_last_session_context_sent(raw: RawTm1) -> None:
    """``TM1-SessionContext`` labels the whole session, and the last request to send it wins. So it
    can't be a per-request setting on views that share one session: they'd keep relabelling each
    other's session."""
    login = raw.send("GET", "ActiveSession?$select=Context", basic=True, headers={"TM1-SessionContext": "first"})
    cookie = RawTm1.cookie(login)
    assert cookie is not None
    try:
        assert login.json()["Context"] == "first"
        raw.send("GET", "Server?$select=Name", cookie=cookie, headers={"TM1-SessionContext": "second"})
        assert raw.send("GET", "ActiveSession?$select=Context", cookie=cookie).json()["Context"] == "second"
    finally:
        raw.close(cookie)


def test_answers_a_create_with_its_location(raw: RawTm1) -> None:
    """A create answers 201 with a relative ``Location`` and an ``ETag``. With ``Prefer:
    return=minimal`` it answers 204 with ``Location`` and ``OData-EntityId``."""
    cookie = raw.login()
    full = scratch_name()
    minimal = scratch_name()
    try:
        created = raw.send("POST", "Dimensions", cookie=cookie, body=dimension(full))
        assert created.status_code == 201
        assert created.headers.get("Location") == f"Dimensions('{full}')"
        assert created.headers.get("ETag")

        bare = raw.send("POST", "Dimensions", cookie=cookie, headers={"Prefer": "return=minimal"}, body=dimension(minimal))
        assert bare.status_code == 204
        assert bare.headers.get("Location") == f"Dimensions('{minimal}')"
        assert bare.headers.get("OData-EntityId") == f"Dimensions('{minimal}')"
    finally:
        raw.send("DELETE", f"Dimensions('{full}')", cookie=cookie)
        raw.send("DELETE", f"Dimensions('{minimal}')", cookie=cookie)
        raw.close(cookie)


def test_drops_the_etag_but_not_the_context_with_metadata_none(raw: RawTm1) -> None:
    """``odata.metadata=none`` drops ``@odata.etag`` but still sends ``@odata.context``."""
    cookie = raw.login()
    name = scratch_name()
    try:
        raw.send("POST", "Dimensions", cookie=cookie, body=dimension(name))
        minimal = raw.send("GET", f"Dimensions('{name}')", cookie=cookie).json()
        none = raw.send("GET", f"Dimensions('{name}')", cookie=cookie, headers={"Accept": "application/json;odata.metadata=none"}).json()
        assert "@odata.etag" in minimal
        assert "@odata.etag" not in none
        assert "@odata.context" in none
    finally:
        raw.send("DELETE", f"Dimensions('{name}')", cookie=cookie)
        raw.close(cookie)


def test_closes_a_connection_idle_for_ninety_seconds() -> None:
    """TM1 closes a kept-alive connection that's idle for about 90 seconds, though
    ``IdleConnectionTimeOut`` is 0, and a request sent on it as it closes gets no response at
    all. The client sends through httpx2's own connection pool, which closes idle connections after
    5 seconds, so that never happens."""
    url, user, password = target()
    root = urlsplit(url.rstrip("/") + "/")
    port = root.port or (443 if root.scheme == "https" else 80)
    connection: socket.socket = socket.create_connection((root.hostname, port), timeout=30)
    try:
        if root.scheme == "https":
            trusting = ssl.create_default_context()
            trusting.check_hostname = False
            trusting.verify_mode = ssl.CERT_NONE
            connection = trusting.wrap_socket(connection, server_hostname=root.hostname)
        request = f"GET {root.path}Server?%24select=Name HTTP/1.1\r\nHost: {root.netloc}\r\nAuthorization: {RawTm1.basic(user, password)}\r\n\r\n"
        connection.sendall(request.encode("ascii"))
        answer = b""
        while b"\r\n\r\n" not in answer:
            answer += connection.recv(65536)
        assert answer.startswith(b"HTTP/1.1 200")
        used = time.monotonic()
        # Whatever is left of the answer, then nothing until TM1 closes: recv returns no bytes
        connection.settimeout(150)
        try:
            while connection.recv(65536):
                used = time.monotonic()
        except ConnectionResetError:
            pass
        idle = int(time.monotonic() - used)
        assert 80 <= idle <= 100, "seconds idle before TM1 closed it"
        with httpx2.Client() as default:
            assert default._transport._pool._keepalive_expiry < 80  # type: ignore[attr-defined]
    finally:
        connection.close()
