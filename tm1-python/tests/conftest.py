"""Shared fixtures: the spec, and a fake TM1 on httpx's MockTransport."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

import httpx
import pytest

from tm1_client import Tm1Client, Tm1Session, auth

REPO = Path(__file__).resolve().parents[2]
SPEC = REPO / "spec" / "tm1-api.json"
CONFORMANCE = REPO / "spec" / "conformance"
PAYLOADS = REPO / "tm1-client" / "src" / "test" / "resources" / "payloads"
BASE = "http://tm1:8010/api/v1/"


@pytest.fixture(scope="session")
def spec() -> dict[str, Any]:
    return json.loads(SPEC.read_text(encoding="utf-8"))


class FakeTm1:
    """Answers logins with a session cookie, records every other request, and answers it with
    ``respond(request)`` (a JSON body, or an httpx.Response)."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.logins = 0
        self.respond: Callable[[httpx.Request], Any] = lambda request: {}

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.raw_path.decode()
        if path.startswith("/api/v1/ActiveSession?$select=ID") and "authorization" in request.headers:
            self.logins += 1
            return httpx.Response(
                200, json={"ID": self.logins}, headers={"Set-Cookie": f"TM1SessionId=s{self.logins}; Path=/api/; HttpOnly"}
            )
        self.requests.append(request)
        answer = self.respond(request)
        if isinstance(answer, httpx.Response):
            return answer
        if answer is None:
            return httpx.Response(204)
        if isinstance(answer, str):
            return httpx.Response(200, text=answer)
        return httpx.Response(200, json=answer)

    @property
    def last(self) -> httpx.Request:
        return self.requests[-1]


def target(request: httpx.Request) -> str:
    """``METHOD path?query`` of a request, relative to the service root, as sent (encoded)."""
    raw = request.url.raw_path.decode()
    return f"{request.method} {raw.removeprefix('/api/v1/')}"


@pytest.fixture
def fake() -> FakeTm1:
    return FakeTm1()


@pytest.fixture
def session(fake: FakeTm1) -> Tm1Session:
    http = httpx.Client(transport=httpx.MockTransport(fake.handler))
    return Tm1Session(BASE, auth.basic("admin", "apple"), client=http)


@pytest.fixture
def client(session: Tm1Session) -> Tm1Client:
    return Tm1Client(session)
