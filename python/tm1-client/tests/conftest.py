"""Shared fixtures: the spec, and a fake TM1 on httpx's MockTransport."""

from __future__ import annotations

import asyncio
import inspect
import json
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
import pytest

from skipcheck_tm1 import Tm1Client, Tm1Session, auth
from skipcheck_tm1.aio import AsyncTm1Client, AsyncTm1Session

REPO = Path(__file__).resolve().parents[3]
SPEC = REPO / "spec" / "tm1-api.json"
CONFORMANCE = REPO / "spec" / "conformance"
PAYLOADS = REPO / "java" / "tm1-client" / "src" / "test" / "resources" / "payloads"
SNAPSHOT = REPO / "java" / "tm1-client" / "src" / "model" / "metadata" / "tm1-schema-11.8.35.xml"
BASE = "http://tm1:8010/api/v1/"


@pytest.fixture(scope="session")
def spec() -> dict[str, Any]:
    return json.loads(SPEC.read_text(encoding="utf-8"))


class FakeTm1:
    """Answers logins with a session cookie and ``$metadata`` with the snapshot (or what it's
    given), records every other request, and answers it with ``respond(request)`` (a JSON
    body, text, ``None`` for 204, or an httpx.Response)."""

    def __init__(self, metadata: bytes | None = None) -> None:
        self.requests: list[httpx.Request] = []
        self.logins = 0
        self.metadata_reads = 0
        self.metadata = SNAPSHOT.read_bytes() if metadata is None else metadata
        self.product_version = "11.8.03500.6"
        self.respond: Callable[[httpx.Request], Any] = lambda request: {}
        self.login_requests: list[httpx.Request] = []
        self.on_login: Callable[[httpx.Request], Any] | None = None
        """Answers logins instead of the default (``ID`` n, cookie ``s<n>``), when set."""

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.raw_path.decode()
        if path.startswith("/api/v1/ActiveSession?$select=ID") and "authorization" in request.headers:
            self.logins += 1
            self.login_requests.append(request)
            if self.on_login is not None:
                answer = self.on_login(request)
                if answer is not None:
                    return answer
            return httpx.Response(200, json={"ID": self.logins}, headers={"Set-Cookie": f"TM1SessionId=s{self.logins}; Path=/api/; HttpOnly"})
        if path == "/api/v1/Server?$select=ProductVersion":
            return httpx.Response(200, json={"ProductVersion": self.product_version})
        if path == "/api/v1/$metadata":
            self.metadata_reads += 1
            return httpx.Response(200, content=self.metadata, headers={"Content-Type": "application/xml"})
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


@pytest.fixture(autouse=True)
def _fresh_capabilities() -> Any:
    """Capabilities are cached per server for the process: each test starts without them."""
    from skipcheck_tm1._views import CAPABILITIES

    CAPABILITIES.clear()
    yield
    CAPABILITIES.clear()


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


@dataclass
class Api:
    """A client of either flavor, and ``run``, which gives what a call returns: at once for the
    sync client, by running the coroutine for the asyncio one."""

    flavor: str
    client: Any
    loop: asyncio.AbstractEventLoop | None

    @property
    def api(self) -> Any:
        """The generated API of the client."""
        return self.client.api

    def run(self, value: Any) -> Any:
        if inspect.isawaitable(value):
            assert self.loop is not None, "a sync client returned an awaitable"
            return self.loop.run_until_complete(value)
        assert self.loop is None, "an asyncio client returned a plain value"
        return value


@pytest.fixture(params=["sync", "async"])
def api(request: pytest.FixtureRequest, fake: FakeTm1) -> Any:
    """The generated API in each flavor, over the same fake TM1."""
    if request.param == "sync":
        yield Api("sync", Tm1Client(Tm1Session(BASE, auth.basic("admin", "apple"), client=httpx.Client(transport=httpx.MockTransport(fake.handler)))), None)
        return
    loop = asyncio.new_event_loop()
    http = httpx.AsyncClient(transport=httpx.MockTransport(fake.handler))
    yield Api("async", AsyncTm1Client(AsyncTm1Session(BASE, auth.basic("admin", "apple"), client=http)), loop)
    loop.run_until_complete(http.aclose())
    loop.close()
