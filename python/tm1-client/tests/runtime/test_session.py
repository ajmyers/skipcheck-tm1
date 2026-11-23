# SPDX-License-Identifier: Apache-2.0

"""The session, ported from the Java client's Tm1SessionTest, Tm1SessionReloginTest,
Tm1SessionImpersonationTest, Tm1SessionViewsTest, Tm1SessionBatchTest and
Tm1SessionCapabilitiesTest: the same behaviour, in both flavors."""

from __future__ import annotations

import asyncio
import base64
import json
import threading
import time
from typing import Any

import httpx
import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1ApiError,
    Tm1AuthenticationError,
    Tm1BadRequestError,
    Tm1Capability,
    Tm1ErrorKind,
    Tm1ForbiddenError,
    Tm1NotFoundError,
    Tm1ProtocolError,
    Tm1Request,
    Tm1ServerError,
    Tm1SessionClosedError,
    Tm1Timeouts,
    Tm1TransportError,
    Tm1UnsupportedOperationError,
    Tm1Version,
    auth,
)
from skipcheck_tm1._protocol import Call
from skipcheck_tm1.aio import cognos as aio_cognos

from ..conftest import BASE, target
from .conftest import SERVER, Clock, Runtime, each, json_response, text_response

LOGIN = "GET ActiveSession?$select=ID"
CLOSE = "POST ActiveSession/tm1.Close"


def cookie(request: httpx.Request) -> str | None:
    value = request.headers.get("cookie")
    return None if value is None else value.split("=", 1)[1]


def requests_to(rt: Runtime, line: str) -> list[httpx.Request]:
    return [r for r in rt.fake.requests if target(r) == line]


def server(rt: Runtime, answer: object = None) -> None:
    """Answers ``GET Server?$select=Name`` (and anything else) as Java's tests do."""
    rt.fake.respond = (lambda r: {"Name": "tm1"}) if answer is None else answer  # type: ignore[assignment]


# Tm1SessionTest


def outcome(rt: Runtime, call: Any, seconds: float = 5.0) -> BaseException | None:
    """What a call fails with, or None if it succeeds; fails the test if it's still running after
    ``seconds`` (Java's ``verify(Duration)``), so a call that hangs can't hang the tests."""
    if rt.flavor == "sync":
        ended: list[BaseException | None] = []

        def go() -> None:
            try:
                call()
                ended.append(None)
            except BaseException as e:  # noqa: BLE001
                ended.append(e)

        thread = threading.Thread(target=go, daemon=True)
        thread.start()
        thread.join(seconds)
        assert ended, f"still running after {seconds} s"
        return ended[0]

    async def bounded() -> BaseException | None:
        task = asyncio.ensure_future(call())
        done, _ = await asyncio.wait({task}, timeout=seconds)
        if not done:
            task.cancel()
            pytest.fail(f"still running after {seconds} s")
        return task.exception()

    return rt.run(bounded())


def test_gives_up_on_an_authenticator_that_never_answers(rt: Runtime) -> None:
    if rt.flavor == "sync":
        pytest.skip("the sync session can't give up on a call that blocks without a thread of its own, which the library never starts")

    class Never:
        def authorization(self) -> Any:
            return asyncio.Event().wait()

        def describe(self) -> str:
            return "Never"

    session = rt.session(rt.builder().authenticator(Never()).timeouts(Tm1Timeouts.builder().login(0.1).build()))
    error = outcome(rt, lambda: session.execute(SERVER))
    assert isinstance(error, Tm1TransportError), error
    assert error.phase is Tm1TransportError.Phase.CONNECT and error.is_retryable(), "nothing was sent to TM1"
    assert "Never" in str(error) and "0.1" in str(error)
    assert outcome(rt, lambda: session.close()) is None
    assert rt.fake.login_requests == []


def test_gives_up_on_a_cognos_that_never_answers(rt: Runtime) -> None:
    # In both flavors, the authenticators that ask Cognos are bounded by their HTTP client's timeouts
    def silent(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    client = httpx.Client if rt.flavor == "sync" else httpx.AsyncClient
    logon = auth.cognos_logon if rt.flavor == "sync" else aio_cognos.cognos_logon
    authenticator = logon("http://cognos:9300", "LDAP", "alice", "secret", http_client=lambda: client(transport=httpx.MockTransport(silent)))
    session = rt.session(rt.builder().authenticator(authenticator))
    error = outcome(rt, lambda: session.execute(SERVER))
    assert isinstance(error, Tm1ProtocolError) and "Cognos" in str(error), error
    assert outcome(rt, lambda: session.close()) is None
    assert rt.fake.login_requests == []


def test_passes_an_authenticators_own_timeout_through_unchanged(rt: Runtime) -> None:
    # Only the session's own timeout becomes a transport error: what the authenticator raises is the caller's to read
    def no_passport() -> str:
        raise TimeoutError("Cognos took too long")

    session = rt.session(rt.builder().authenticator(auth.cam_passport(no_passport)))
    error = rt.raises(TimeoutError, lambda: session.connect())
    assert str(error) == "Cognos took too long"


def test_logs_in_once_for_concurrent_first_requests_and_sends_only_the_cookie_afterwards(rt: Runtime) -> None:
    def login(request: httpx.Request) -> httpx.Response:
        time.sleep(0.05)  # widen the window for the threads
        return json_response(200, {"ID": 7}, "TM1SessionId=abc; Path=/api/; HttpOnly")

    rt.fake.on_login = login
    server(rt)
    session = rt.session()
    rt.gather(*(lambda: session.execute(SERVER) for _ in range(3)))
    assert len(rt.fake.login_requests) == 1
    assert rt.fake.login_requests[0].headers["authorization"] == "Basic YWRtaW46YXBwbGU="
    sent = requests_to(rt, "GET Server?$select=Name")
    assert len(sent) == 3
    for r in sent:
        assert cookie(r) == "abc"
        assert "authorization" not in r.headers, "no credentials after login"
        assert r.headers["odata-version"] == "4.0" and r.headers["odata-maxversion"] == "4.0"
    assert rt.run(session.session_id()) == 7


def test_finds_the_session_cookie_by_name_among_others(rt: Runtime) -> None:
    rt.fake.on_login = lambda r: json_response(200, {"ID": 3}, "other=1; Path=/", "TM1SessionId=right; Path=/api/; HttpOnly", "more=2")
    server(rt)
    rt.run(rt.session().execute(SERVER))
    assert cookie(requests_to(rt, "GET Server?$select=Name")[0]) == "right"


def test_reports_refused_credentials_and_can_try_again_on_the_next_request(rt: Runtime) -> None:
    rt.fake.on_login = each(
        json_response(401, None, "TM1SessionId=anon; Path=/api/"),
        json_response(200, {"ID": 2}, "TM1SessionId=ok; Path=/api/"),
    )
    server(rt)
    session = rt.session(rt.builder().timeouts(Tm1Timeouts.builder().refused_login(0).build()))
    error = rt.raises(Tm1AuthenticationError, lambda: session.execute(SERVER))
    assert error.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    assert requests_to(rt, "GET Server?$select=Name") == [], "nothing sent without a session"
    rt.run(session.execute(SERVER))
    assert len(rt.fake.login_requests) == 2


def test_maps_error_responses_through_the_classifier(rt: Runtime) -> None:
    rt.fake.respond = lambda r: json_response(404, {"error": {"code": "278", "message": "'Nope' can not be found in collection of type 'Cube'."}})
    error = rt.raises(Tm1NotFoundError, lambda: rt.session().execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none(), dict)))
    assert error.status == 404 and error.error.code == "278"
    assert error.url == BASE + "Cubes('Nope')"


def test_uses_the_sessions_own_classifier(rt: Runtime) -> None:
    from skipcheck_tm1 import Tm1ConflictError, Tm1ErrorClassifier
    from skipcheck_tm1.errors import Rule

    rt.fake.respond = lambda r: json_response(400, {"error": {"code": "1", "message": "Cube is frozen"}})
    classifier = Tm1ErrorClassifier.defaults().with_rule(Rule.of_message("frozen", Tm1ErrorKind.LOCKED))
    session = rt.session(rt.builder().error_classifier(classifier))
    error = rt.raises(Tm1ConflictError, lambda: session.execute(SERVER))
    assert error.kind is Tm1ErrorKind.LOCKED


def test_reads_a_plain_text_error_body_as_the_message(rt: Runtime) -> None:
    rt.fake.respond = lambda r: text_response(400, "Error: Bad Request\r\nSomething TM1 says in plain text.\r\n")
    error = rt.raises(Tm1ApiError, lambda: rt.session().execute(SERVER))
    assert error.error.message == "Something TM1 says in plain text."


def test_reads_text_and_no_content_and_sends_json_bodies(rt: Runtime) -> None:
    rt.fake.respond = lambda r: "11.8.03500.6" if r.method == "GET" else None
    session = rt.session()
    text = rt.run(session.execute(Tm1Request.text(ODataPath.of("Configuration").nav("ProductVersion").value())))
    assert text == "11.8.03500.6"
    assert rt.run(session.execute(Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Lock"), None))) is None
    get, lock = rt.fake.requests
    assert get.headers["accept"] == "text/plain, application/json"
    assert lock.headers["content-type"] == "application/json" and lock.content == b"{}"


def test_reads_bytes_that_arent_utf8_as_the_replacement_character(rt: Runtime) -> None:
    # As TM1's transaction log gives a password's hash, with "é" (two bytes) beside it
    hash = b"\xe0\xecr\xff\xff"
    body = '{"value":[{"Name":"é'.encode() + hash + '","Type":"é"}]}'.encode()
    rt.fake.respond = lambda r: httpx.Response(200, content=body, headers={"Content-Type": "application/json"})
    replaced = "é" + chr(0xFFFD) * 2 + "r" + chr(0xFFFD) * 2
    listed = rt.run(rt.session().collect(Tm1Request.list(ODataPath.of("Elements"), ODataQuery.none(), dict)))
    assert listed == [{"Name": replaced, "Type": "é"}]
    assert rt.run(rt.session().execute(Tm1Request.get(ODataPath.of("Elements"), ODataQuery.none(), dict)))["value"][0]["Name"] == replaced


def test_streams_collections_across_next_links(rt: Runtime) -> None:
    pages = {
        "GET Dimensions?$select=Name": {
            "@odata.context": "$metadata#Dimensions(Name)",
            "value": [{"Name": "a"}, {"Name": "b"}],
            "@odata.nextLink": "Dimensions?$skiptoken=2",
        },
        "GET Dimensions?$skiptoken=2": {"value": [{"Name": "c"}]},
    }
    rt.fake.respond = lambda r: pages[target(r)]
    request = Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().select("Name"), dict)
    session = rt.session()
    assert [d["Name"] for d in rt.run(session.collect(request))] == ["a", "b", "c"]
    if rt.flavor == "sync":
        assert [d["Name"] for d in session.stream(request)] == ["a", "b", "c"]
    else:

        async def streamed() -> list[str]:
            return [d["Name"] async for d in session.stream(request)]

        assert rt.run(streamed()) == ["a", "b", "c"]


def test_refuses_to_stream_a_single_response_and_to_execute_a_collection_without_sending_it(rt: Runtime) -> None:
    session = rt.session()
    with pytest.raises(ValueError):
        session.stream(SERVER)
    rt.raises(ValueError, lambda: session.execute(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), dict)))
    assert rt.fake.requests == [] and rt.fake.login_requests == []


def test_reports_a_body_that_isnt_json_as_the_callers_mistake_not_a_transport_failure(rt: Runtime) -> None:
    bad = Tm1Request.post(ODataPath.of("Cubes"), {"name": object()})
    error = rt.raises((TypeError, ValueError), lambda: rt.session().execute(bad))
    assert not isinstance(error, Tm1TransportError)


def test_passes_other_failures_through_unwrapped(rt: Runtime) -> None:
    def bug(request: httpx.Request) -> None:
        raise RuntimeError("a bug, not the network")

    rt.fake.respond = bug
    rt.raises(RuntimeError, lambda: rt.session().execute(SERVER))


def test_maps_connect_failures_and_other_transport_failures(rt: Runtime) -> None:
    def fail(request: httpx.Request) -> None:
        if request.method == "GET":
            raise httpx.ConnectError("refused", request=request)
        raise httpx.ReadError("Connection reset", request=request)

    rt.fake.respond = fail
    session = rt.session()
    connect = rt.raises(Tm1TransportError, lambda: session.execute(SERVER))
    assert connect.phase is Tm1TransportError.Phase.CONNECT and not connect.may_have_applied()
    run = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), None)
    reset = rt.raises(Tm1TransportError, lambda: session.execute(run))
    assert reset.phase is Tm1TransportError.Phase.RESPONSE and reset.may_have_applied()
    assert not reset.is_retryable(), "a POST that may have run"


def test_sends_nothing_on_close_if_it_never_logged_in(rt: Runtime) -> None:
    session = rt.session()
    rt.run(session.close())
    assert rt.fake.requests == [] and rt.fake.login_requests == []
    assert session.is_closed
    rt.raises(Tm1SessionClosedError, lambda: session.execute(SERVER))
    assert rt.fake.requests == []


def test_closes_the_session_once_with_a_json_body_and_refuses_requests_afterwards(rt: Runtime) -> None:
    server(rt)
    session = rt.session()
    rt.run(session.execute(SERVER))
    rt.gather(lambda: session.close(), lambda: session.close())
    rt.run(session.close())
    closes = requests_to(rt, CLOSE)
    assert len(closes) == 1
    assert cookie(closes[0]) == "s1"
    assert closes[0].headers["content-type"] == "application/json"
    assert closes[0].headers["connection"] == "close", "not reused after a close"
    rt.raises(Tm1SessionClosedError, lambda: session.execute(SERVER))
    assert len(rt.fake.login_requests) == 1, "a closed session doesn't log in again"


def test_treats_a_401_on_close_as_already_closed(rt: Runtime) -> None:
    rt.fake.respond = lambda r: json_response(401) if target(r) == CLOSE else {}
    session = rt.session()
    rt.run(session.connect())
    rt.run(session.close())


# Tm1SessionReloginTest


CREATE = "POST Dimensions"
CREATE_KEY = Tm1Request.post(ODataPath.of("Dimensions"), {"Name": "d"}, dict)


def test_logs_in_again_with_the_cookie_from_the_401_and_replays_the_post_once(rt: Runtime) -> None:
    rt.fake.respond = each(json_response(401, None, "TM1SessionId=fresh; Path=/api/; HttpOnly"), json_response(201, {"Name": "d"}))
    rt.fake.on_login = each(
        json_response(200, {"ID": 1}, "TM1SessionId=c1; Path=/api/"),
        lambda r: json_response(200, {"ID": 2}),  # logs in the 401's session: no new cookie
    )
    session = rt.session()
    assert rt.run(session.execute(CREATE_KEY)) == {"Name": "d"}
    logins = rt.fake.login_requests
    assert len(logins) == 2
    assert cookie(logins[1]) == "fresh", "the re-login logs in the session TM1 opened for the 401"
    assert "authorization" in logins[1].headers
    creates = requests_to(rt, CREATE)
    assert [cookie(c) for c in creates] == ["c1", "fresh"]
    assert "authorization" not in creates[1].headers


def test_shares_one_relogin_between_requests_that_saw_the_same_expiry(rt: Runtime) -> None:
    rt.fake.on_login = each(
        json_response(200, {"ID": 1}, "TM1SessionId=old; Path=/api/"),
        lambda r: (time.sleep(0.05), json_response(200, {"ID": 2}, "TM1SessionId=new; Path=/api/"))[1],
    )
    rt.fake.respond = lambda r: json_response(401) if cookie(r) == "old" else {}
    session = rt.session()
    rt.run(session.connect())
    rt.gather(lambda: session.execute(SERVER), lambda: session.execute(SERVER))
    assert len(rt.fake.login_requests) == 2


def test_gives_up_if_the_replay_is_refused_too_without_logging_in_again(rt: Runtime) -> None:
    rt.fake.respond = lambda r: json_response(401, None, "TM1SessionId=anon; Path=/api/")
    error = rt.raises(Tm1AuthenticationError, lambda: rt.session().execute(SERVER))
    assert error.kind is Tm1ErrorKind.UNAUTHENTICATED
    assert len(rt.fake.login_requests) == 2
    assert len(rt.fake.requests) == 2


def test_reports_refused_credentials_on_relogin_without_replaying(rt: Runtime) -> None:
    rt.fake.on_login = each(json_response(200, {"ID": 1}, "TM1SessionId=c1; Path=/api/"), json_response(401))
    rt.fake.respond = lambda r: json_response(401, None, "TM1SessionId=anon; Path=/api/")
    error = rt.raises(Tm1AuthenticationError, lambda: rt.session().execute(CREATE_KEY))
    assert error.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    assert len(requests_to(rt, CREATE)) == 1, "the POST is sent once"


def test_closes_a_session_logged_in_again_rather_than_the_expired_one(rt: Runtime) -> None:
    rt.fake.respond = each(json_response(401, None, "TM1SessionId=fresh; Path=/api/"), json_response(201, {}), None)
    rt.fake.on_login = each(json_response(200, {"ID": 1}, "TM1SessionId=c1; Path=/api/"), json_response(200, {"ID": 2}))
    session = rt.session()
    rt.run(session.execute(CREATE_KEY))
    rt.run(session.close())
    closes = requests_to(rt, CLOSE)
    assert len(closes) == 1 and cookie(closes[0]) == "fresh"


def test_asks_the_authenticator_for_fresh_credentials_on_each_login(rt: Runtime) -> None:
    issued = iter(range(1, 10))
    rt.fake.respond = each(json_response(401, None, "TM1SessionId=fresh; Path=/api/"), json_response(201, {}))
    session = rt.session(rt.builder().authenticator(auth.cam_passport(lambda: f"passport{next(issued)}")))
    rt.run(session.execute(CREATE_KEY))
    assert [r.headers["authorization"] for r in rt.fake.login_requests] == ["CAMPassport passport1", "CAMPassport passport2"]


def test_passes_an_authenticator_failure_through_without_sending_a_login(rt: Runtime) -> None:
    def no_passport() -> str:
        raise RuntimeError("Cognos is down")

    session = rt.session(rt.builder().authenticator(auth.cam_passport(no_passport)))
    error = rt.raises(RuntimeError, lambda: session.connect())
    assert str(error) == "Cognos is down"
    assert rt.fake.login_requests == []


def test_doesnt_log_in_again_once_closed(rt: Runtime) -> None:
    session = rt.session()
    rt.run(session.connect())
    rt.run(session.close())
    rt.raises(Tm1SessionClosedError, lambda: session.execute(SERVER))
    assert len(rt.fake.login_requests) == 1


# Tm1SessionImpersonationTest


def test_impersonates_at_login_only(rt: Runtime) -> None:
    server(rt)
    session = rt.session(rt.builder().impersonate("alice"))
    rt.run(session.execute(SERVER))
    assert rt.fake.login_requests[0].headers["tm1-impersonate"] == "alice"
    assert "tm1-impersonate" not in rt.fake.requests[0].headers
    assert session.impersonating == "alice"


def test_refuses_names_tm1_would_misread(rt: Runtime) -> None:
    with pytest.raises(ValueError, match="no impersonation"):
        rt.Session.builder().impersonate("")
    with pytest.raises(ValueError):
        rt.Session.builder().impersonate("  ")
    with pytest.raises(ValueError, match="control character"):
        rt.Session.builder().impersonate("a\nb")
    rt.Session.builder().impersonate("carol smith")


def test_sends_a_name_outside_ascii_as_its_utf8_bytes(rt: Runtime) -> None:
    server(rt)
    session = rt.session(rt.builder().impersonate("zoë").session_context("café"))
    rt.run(session.execute(SERVER))

    # The bytes httpx puts on the wire, not its reading of them
    login = dict(rt.fake.login_requests[0].headers.raw)
    assert login[b"TM1-Impersonate"] == "zoë".encode()
    assert login[b"TM1-SessionContext"] == "café".encode()


def test_reports_an_unknown_user(rt: Runtime) -> None:
    rt.fake.on_login = lambda r: text_response(400, "Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n")
    error = rt.raises(Tm1BadRequestError, lambda: rt.session(rt.builder().impersonate("nobody")).execute(SERVER))
    assert error.kind is Tm1ErrorKind.UNKNOWN_USER


def test_reports_a_refused_impersonation_whatever_the_wording(rt: Runtime) -> None:
    rt.fake.on_login = lambda r: text_response(403, "Error: Forbidden\r\nSome other version's words.\r\n")
    error = rt.raises(Tm1ForbiddenError, lambda: rt.session(rt.builder().impersonate("Admin")).execute(SERVER))
    assert error.kind is Tm1ErrorKind.IMPERSONATION_REFUSED


def test_names_both_causes_of_a_401_when_impersonating(rt: Runtime) -> None:
    rt.fake.on_login = lambda r: json_response(401)
    error = rt.raises(Tm1AuthenticationError, lambda: rt.session(rt.builder().impersonate("bob")).execute(SERVER))
    assert error.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    assert "bob" in error.error.message and "disabled" in error.error.message


def test_remembers_a_refused_login_and_fails_fast_until_the_back_off_ends(rt: Runtime) -> None:
    rt.fake.on_login = each(json_response(401), json_response(401), json_response(200, {"ID": 9}, "TM1SessionId=ok; Path=/api/"))
    server(rt)
    clock = Clock()
    session = rt.session(rt.builder().timeouts(Tm1Timeouts.builder().refused_login(30).build()).clock(clock))
    rt.raises(Tm1AuthenticationError, lambda: session.execute(SERVER))
    clock.advance(29)
    rt.raises(Tm1AuthenticationError, lambda: session.execute(SERVER))
    assert len(rt.fake.login_requests) == 1, "within the back-off: no new login"
    clock.advance(2)
    rt.raises(Tm1AuthenticationError, lambda: session.execute(SERVER))
    assert len(rt.fake.login_requests) == 2, "after the back-off: tried again"
    clock.advance(31)
    rt.run(session.execute(SERVER))
    assert len(rt.fake.login_requests) == 3


def test_retries_at_once_after_a_failure_that_isnt_a_refusal(rt: Runtime) -> None:
    def reset(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadError("Connection reset", request=request)

    rt.fake.on_login = each(reset, json_response(200, {"ID": 9}, "TM1SessionId=ok; Path=/api/"))
    server(rt)
    session = rt.session()
    rt.raises(Tm1TransportError, lambda: session.execute(SERVER))
    rt.run(session.execute(SERVER))
    assert len(rt.fake.login_requests) == 2


def test_tries_again_at_once_when_the_back_off_is_zero(rt: Runtime) -> None:
    rt.fake.on_login = each(json_response(401), json_response(200, {"ID": 9}, "TM1SessionId=ok; Path=/api/"))
    server(rt)
    session = rt.session(rt.builder().timeouts(Tm1Timeouts.builder().refused_login(0).build()))
    rt.raises(Tm1ApiError, lambda: session.execute(SERVER))
    rt.run(session.execute(SERVER))


# Tm1SessionViewsTest


def test_adds_the_sandbox_to_every_request_of_the_view_and_shares_the_login(rt: Runtime) -> None:
    cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"), dict)
    base = rt.session()
    what_if = base.with_sandbox("What-if 1")
    rt.run(what_if.execute(cube))
    rt.run(base.execute(cube))
    rt.run(what_if.without_sandbox().execute(cube))
    assert [target(r) for r in rt.fake.requests] == [
        "GET Cubes('Sales')?$select=Name&!sandbox=What-if%201",
        "GET Cubes('Sales')?$select=Name",
        "GET Cubes('Sales')?$select=Name",
    ]
    assert len(rt.fake.login_requests) == 1, "views share one login"
    assert what_if.sandbox == "What-if 1" and base.sandbox is None
    assert repr(what_if) == f"{rt.Session.__name__}[{BASE}, sandbox=What-if 1]"


def test_keeps_a_sandbox_the_request_sets_itself(rt: Runtime) -> None:
    cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name").option("!sandbox", "Mine"), dict)
    rt.run(rt.session().with_sandbox("What-if 1").execute(cube))
    assert target(rt.fake.last) == "GET Cubes('Sales')?$select=Name&!sandbox=Mine"


def test_closing_a_view_closes_the_shared_session(rt: Runtime) -> None:
    base = rt.session()
    rt.run(base.connect())
    rt.run(base.with_sandbox("x").close())
    assert base.is_closed
    assert len(requests_to(rt, CLOSE)) == 1


def test_sends_the_session_context_with_the_login_only(rt: Runtime) -> None:
    server(rt)
    session = rt.session(rt.builder().session_context("planning-app"))
    rt.run(session.execute(SERVER))
    assert rt.fake.login_requests[0].headers["tm1-sessioncontext"] == "planning-app"
    assert "tm1-sessioncontext" not in rt.fake.requests[0].headers


def test_refuses_blank_sandboxes_and_overlong_contexts(rt: Runtime) -> None:
    with pytest.raises(ValueError):
        rt.session().with_sandbox(" ")
    with pytest.raises(ValueError):
        rt.Session.builder().session_context("x" * 251)


# Tm1SessionBatchTest


def test_sends_queued_requests_together_and_hands_each_its_result(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {
        "responses": [
            {"id": "1", "status": 200, "body": {"Name": "Sales"}},
            {"id": "2", "status": 404, "body": {"error": {"code": "278", "message": "'Nope' can not be found"}}},
            {"id": "3", "status": 200, "body": {"value": [{"Name": "a"}, {"Name": "b"}]}},
            {"id": "4", "status": 204},
        ]
    }
    batch = rt.session().batch()
    queued = batch.session
    sales = rt.run(queued.execute(Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none(), dict)))
    nope = rt.run(queued.execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none(), dict)))
    listed = rt.run(queued.collect(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), dict)))
    deleted = rt.run(queued.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old"))))
    assert batch.size() == 4
    assert rt.fake.requests == [], "nothing sent before run"
    rt.run(batch.run())
    assert sales.result()["Name"] == "Sales"
    with pytest.raises(Tm1NotFoundError):
        nope.result()
    assert deleted.result() is None
    assert [c["Name"] for c in listed.result()] == ["a", "b"]
    assert len(rt.fake.requests) == 1, "one $batch"
    sent = json.loads(rt.fake.requests[0].content)["requests"]
    assert [r["headers"] for r in sent] == [{"accept": "application/json"}] * 4, "each asks for its own media type, in lower case"


def test_refuses_calls_after_it_ran(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {"responses": []}
    batch = rt.session().batch()
    rt.run(batch.run())
    rt.raises(RuntimeError, lambda: batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("x"))))
    rt.raises(RuntimeError, lambda: batch.run())


def test_refuses_a_call_whose_body_cant_be_written_and_still_runs_the_rest(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {"responses": [{"id": "1", "status": 204}]}
    batch = rt.session().batch()
    deleted = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old"))))
    unwritable = Tm1Request.post(ODataPath.of("Cubes"), {"name": object()}, dict)
    error = rt.raises((TypeError, ValueError), lambda: batch.session.execute(unwritable))
    assert not isinstance(error, Tm1TransportError)
    # A call made without a Tm1Request, as the generated API's are, is written when it's queued
    rt.raises(ValueError, lambda: batch.session.execute(Call("POST", "Cubes", json_body={"name": object()})))
    assert batch.size() == 1, "never queued"
    rt.run(batch.run())
    assert deleted.result() is None


def test_sends_a_body_as_it_was_when_the_call_was_queued(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {"responses": [{"id": "1", "status": 201, "body": {}}]}
    batch = rt.session().batch()
    body = {"Name": "before"}
    rt.run(batch.session.execute(Tm1Request.post(ODataPath.of("Cubes"), body, dict)))
    body["Name"] = "after"
    rt.run(batch.run())
    assert json.loads(rt.fake.last.content)["requests"][0]["body"] == {"Name": "before"}


def test_fails_every_call_when_tm1_answers_the_batch_with_no_body(rt: Runtime) -> None:
    rt.fake.respond = lambda r: httpx.Response(200)
    batch = rt.session().batch()
    first = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("a"))))
    second = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("b"))))
    rt.run(batch.run())
    for deferred in (first, second):
        with pytest.raises(Tm1ProtocolError, match="no body"):
            deferred.result()


def test_fails_every_call_when_the_batchs_answer_cant_be_read(rt: Runtime) -> None:
    # Not in the Java tests: there, a response that isn't JSON fails the $batch request, and so every call
    rt.fake.respond = lambda r: text_response(200, "not a batch response")
    batch = rt.session().batch()
    first = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("a"))))
    second = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("b"))))
    rt.run(batch.run())
    for deferred in (first, second):
        with pytest.raises(Tm1ProtocolError):
            deferred.result()


def test_follows_a_next_link_in_an_answer(rt: Runtime) -> None:
    answers(
        rt,
        {
            "POST $batch": {"responses": [{"id": "1", "status": 200, "body": {"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$skiptoken=2"}}]},
            "GET Cubes?$skiptoken=2": {"value": [{"Name": "b"}]},
        },
    )
    batch = rt.session().with_sandbox("s").batch()
    names = rt.run(batch.session.collect(CUBES))
    rt.run(batch.run())
    assert [cube["Name"] for cube in names.result()] == ["a", "b"]
    assert [target(r) for r in rt.fake.requests] == ["POST $batch", "GET Cubes?$skiptoken=2"], "the link is followed as TM1 gave it"


def test_fails_a_call_whose_later_page_fails(rt: Runtime) -> None:
    answers(
        rt,
        {
            "POST $batch": {
                "responses": [
                    {"id": "1", "status": 200, "body": {"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$skiptoken=2"}},
                    {"id": "2", "status": 204},
                ]
            },
            "GET Cubes?$skiptoken=2": json_response(500, {"error": {"code": "1", "message": "no more"}}),
        },
    )
    batch = rt.session().batch()
    names = rt.run(batch.session.collect(CUBES))
    deleted = rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old"))))
    rt.run(batch.run())
    with pytest.raises(Tm1ServerError):
        names.result()
    assert deleted.result() is None


def test_splits_a_big_queue(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {"responses": []}
    batch = rt.session().batch()
    for i in range(5):
        rt.run(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key(f"c{i}"))))
    rt.run(batch.run(2))
    assert len(rt.fake.requests) == 3
    rt.raises(ValueError, lambda: rt.session().batch().run(0))


# Tm1SessionCapabilitiesTest


VERSION = "GET Server?$select=ProductVersion"


def test_reads_the_version_once_inside_the_session(rt: Runtime) -> None:
    seen: list[httpx.Request] = []
    handler = rt.fake.handler

    def spy(request: httpx.Request) -> httpx.Response:
        if target(request) == VERSION:
            seen.append(request)
        return handler(request)

    rt.fake.handler = spy  # type: ignore[method-assign]
    session = rt.session()
    assert rt.run(session.version()) == Tm1Version.parse("11.8.03500.6")
    rt.run(session.version())
    assert len(seen) == 1
    assert cookie(seen[0]) is not None, "never an anonymous read"


def test_fetches_metadata_once_per_server_version_for_every_session(rt: Runtime) -> None:
    first, second = rt.session(), rt.session()
    caps = rt.run(first.capabilities())
    assert caps.schema_version == "11.8.35"
    assert caps.product_version == Tm1Version.parse("11.8.03500.6")
    rt.run(second.capabilities())
    assert rt.fake.metadata_reads == 1


def test_sends_a_request_whose_requirement_the_server_meets(rt: Runtime) -> None:
    order = Tm1Request.get(ODataPath.of("Cubes").key("Sales").function("tm1.DimensionsStorageOrder", {}), ODataQuery.none(), dict).requires(
        Tm1Capability.bound_operation("tm1.Cube", "tm1.DimensionsStorageOrder").since("11.4.0")
    )
    rt.run(rt.session().execute(order))
    assert target(rt.fake.last) == "GET Cubes('Sales')/tm1.DimensionsStorageOrder()"


def test_refuses_a_request_the_server_cant_serve_without_sending_it(rt: Runtime) -> None:
    bananas = Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), dict).requires(Tm1Capability.entity_set("Bananas").since("99.0"))
    error = rt.raises(Tm1UnsupportedOperationError, lambda: rt.session().collect(bananas))
    assert error.missing == "entity set Bananas"
    assert "added in schema 99.0" in str(error) and "reports schema 11.8.35" in str(error)
    assert rt.fake.requests == []


def test_refuses_a_request_the_server_cant_serve_in_a_batch_and_still_sends_the_rest(rt: Runtime) -> None:
    rt.fake.respond = lambda r: {"responses": [{"id": "1", "status": 200, "body": {}}]}
    batch = rt.session().batch()
    bananas = rt.run(
        batch.session.collect(Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), dict).requires(Tm1Capability.entity_set("Bananas").since("99.0")))
    )
    server = rt.run(batch.session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), dict)))
    assert rt.fake.metadata_reads == 0 and rt.fake.login_requests == [], "queueing sends nothing: the check waits for the run"
    rt.run(batch.run())
    with pytest.raises(Tm1UnsupportedOperationError):
        bananas.result()
    assert server.result() == {}
    (sent,) = requests_to(rt, "POST $batch")
    assert "Server" in sent.content.decode() and "Bananas" not in sent.content.decode()


def test_fails_only_the_calls_that_need_them_in_a_batch_when_the_capabilities_cant_be_read(rt: Runtime) -> None:
    rt.fake.metadata = b"<html>Not metadata</html>"
    rt.fake.respond = lambda r: {"responses": [{"id": "1", "status": 200, "body": {}}]}
    batch = rt.session().batch()
    bananas = rt.run(
        batch.session.collect(Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), dict).requires(Tm1Capability.entity_set("Bananas").since("99.0")))
    )
    server = rt.run(batch.session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), dict)))
    rt.run(batch.run())
    with pytest.raises(Tm1ProtocolError):
        bananas.result()
    assert server.result() == {}


def test_reads_the_version_again_after_logging_in_again(rt: Runtime) -> None:
    reads: list[str] = []
    handler = rt.fake.handler

    def spy(request: httpx.Request) -> httpx.Response:
        if target(request) == VERSION:
            reads.append(cookie(request) or "")
        return handler(request)

    rt.fake.handler = spy  # type: ignore[method-assign]
    rt.fake.respond = each(json_response(401, None, "TM1SessionId=fresh; Path=/api/"), {})
    session = rt.session()
    rt.run(session.version())
    rt.run(session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), dict)))
    rt.run(session.version())
    assert len(reads) == 2


def test_reports_metadata_that_isnt_csdl(rt: Runtime) -> None:
    rt.fake.metadata = b"<html>Not metadata</html>"
    rt.raises(Tm1ProtocolError, lambda: rt.session().capabilities())


def test_doesnt_keep_a_failed_read(rt: Runtime) -> None:
    versions = each(json_response(503, {"error": {"code": "1", "message": "busy"}}), None)
    handler = rt.fake.handler

    def flaky(request: httpx.Request) -> httpx.Response:
        if target(request) == VERSION:
            answer = versions(request)
            if answer is not None:
                return answer
        return handler(request)

    rt.fake.handler = flaky  # type: ignore[method-assign]
    session = rt.session()
    rt.raises(Tm1ApiError, lambda: session.version())
    assert rt.run(session.version()) == Tm1Version.parse("11.8.03500.6")


# Tm1SessionAsyncTest


RUN = "POST Processes('p')/tm1.ExecuteWithReturn"
MONITOR = "GET _async('a1')"
REQUEST = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), None, dict)
CUBES = Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), dict)


def accepted() -> httpx.Response:
    return httpx.Response(202, headers={"Location": "../_async('a1')"})


def done(status_line: str, body: str) -> httpx.Response:
    """A finished monitor's answer: the result as an ``application/http`` message."""
    message = f"HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\nOData-Version: 4.0\r\n\r\n{body}"
    return httpx.Response(200, content=message.encode(), headers={"Content-Type": "application/http"})


def answers(rt: Runtime, routes: dict[str, Any]) -> None:
    """Answers each request by its ``METHOD path`` (Java's ``FakeTm1.on``)."""

    def respond(request: httpx.Request) -> Any:
        answer = routes[target(request)]
        return answer(request) if callable(answer) else answer

    rt.fake.respond = respond


def streamed(rt: Runtime, session: Any, request: Any) -> list[Any]:
    """What ``stream`` gives, to its end."""
    if rt.flavor == "sync":
        return list(session.stream(request))

    async def items() -> list[Any]:
        return [item async for item in session.stream(request)]

    return rt.run(items())


def test_reads_a_result_bigger_than_the_codecs_buffer_limit(rt: Runtime) -> None:
    # The Java client's codecs refused to buffer more than 256 KB. Nothing limits a body here: this pins that
    big = "x" * 300_000
    answers(rt, {RUN: accepted(), MONITOR: done("200 OK", json.dumps({"Text": big}))})
    result = rt.run(rt.session().with_async(0.01).execute(REQUEST))
    assert len(result["Text"]) == 300_000


def test_reads_a_big_collection_tm1_gave_at_once(rt: Runtime) -> None:
    big = "x" * 300_000
    answers(rt, {"GET Cubes": {"value": [{"Name": big}]}})
    cubes = streamed(rt, rt.session().with_async(0.01), CUBES)
    assert [len(cube["Name"]) for cube in cubes] == [300_000]


def test_follows_a_next_link_in_a_result(rt: Runtime) -> None:
    answers(
        rt,
        {
            "GET Cubes": {"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$skiptoken=2"},
            "GET Cubes?$skiptoken=2": {"value": [{"Name": "b"}]},
        },
    )
    session = rt.session().with_async(0.01)
    assert [cube["Name"] for cube in streamed(rt, session, CUBES)] == ["a", "b"]
    assert [cube["Name"] for cube in rt.run(session.collect(CUBES))] == ["a", "b"]


def test_follows_a_next_link_in_a_result_it_polled_for(rt: Runtime) -> None:
    # Not in the Java tests, where one decoder reads a result whether TM1 gave it at once or through the monitor
    answers(
        rt,
        {
            "GET Cubes": httpx.Response(202, headers={"Location": "_async('a1')"}),
            MONITOR: done("200 OK", json.dumps({"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$skiptoken=2"})),
            "GET Cubes?$skiptoken=2": {"value": [{"Name": "b"}]},
        },
    )
    assert [cube["Name"] for cube in rt.run(rt.session().with_async(0.01).collect(CUBES))] == ["a", "b"]


def test_reads_a_big_error_body(rt: Runtime) -> None:
    big = "x" * 300_000
    answers(rt, {RUN: json_response(500, {"error": {"code": "174", "message": big}})})
    error = rt.raises(Tm1ServerError, lambda: rt.session().execute(REQUEST))
    assert len(error.error.message) == 300_000


# Tm1Dialect, through a session


def test_lets_a_session_be_built_from_an_address(rt: Runtime) -> None:
    session = rt.Session.builder().address("http://tm1:8010").basic_auth("admin", "").build()
    assert session.service_root == "http://tm1:8010/api/v1/"


def test_basic_credentials_on_the_wire(rt: Runtime) -> None:
    server(rt)
    rt.run(rt.session(rt.builder().basic_auth("admin", None)).execute(SERVER))
    value = rt.fake.login_requests[0].headers["authorization"].split(" ", 1)[1]
    assert base64.b64decode(value).decode() == "admin:"
