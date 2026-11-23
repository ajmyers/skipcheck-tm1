"""The runtime in each flavor: sessions over the fake TM1, as the Java tests' ``FakeTm1``."""

from __future__ import annotations

import asyncio
import inspect
import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx
import pytest

import skipcheck_tm1
import skipcheck_tm1.aio

from ..conftest import BASE, FakeTm1

# The Java tests' requests
SERVER = skipcheck_tm1.Tm1Request.get(skipcheck_tm1.ODataPath.of("Server"), skipcheck_tm1.ODataQuery.none().select("Name"), dict)


def json_response(status: int, body: Any = None, *cookies: str) -> httpx.Response:
    """A response with a JSON body (or none) and ``Set-Cookie`` headers, as FakeTm1.json."""
    headers = [("Set-Cookie", c) for c in cookies]
    if body is None:
        return httpx.Response(status, headers=headers)
    return httpx.Response(status, json=body, headers=headers)


def text_response(status: int, text: str) -> httpx.Response:
    return httpx.Response(status, text=text, headers={"Content-Type": "text/plain"})


def each(*answers: Any) -> Callable[[httpx.Request], Any]:
    """Answers in turn, then the last one again (Java's ``FakeTm1.onEach``)."""
    queue = list(answers)

    def answer(request: httpx.Request) -> Any:
        a = queue.pop(0) if len(queue) > 1 else queue[0]
        return a(request) if callable(a) else a

    return answer


@dataclass
class Clock:
    """A clock tests move by hand (Java's ``nanoTime`` and ``VirtualTimeScheduler``)."""

    now: float = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@dataclass
class Runtime:
    """Sessions of one flavor over a fake TM1; ``run`` gives what a call returns."""

    flavor: str
    fake: FakeTm1
    loop: asyncio.AbstractEventLoop | None
    clients: list[Any] = field(default_factory=list)

    @property
    def m(self) -> Any:
        """The flavor's module: ``skipcheck_tm1`` or ``skipcheck_tm1.aio``."""
        return skipcheck_tm1 if self.flavor == "sync" else skipcheck_tm1.aio

    @property
    def Session(self) -> Any:  # noqa: N802
        return skipcheck_tm1.Tm1Session if self.flavor == "sync" else skipcheck_tm1.aio.AsyncTm1Session

    @property
    def Pool(self) -> Any:  # noqa: N802
        return skipcheck_tm1.Tm1SessionPool if self.flavor == "sync" else skipcheck_tm1.aio.AsyncTm1SessionPool

    @property
    def Client(self) -> Any:  # noqa: N802
        return skipcheck_tm1.Tm1Client if self.flavor == "sync" else skipcheck_tm1.aio.AsyncTm1Client

    def http(self) -> Any:
        transport = httpx.MockTransport(self.fake.handler)
        client = httpx.Client(transport=transport) if self.flavor == "sync" else httpx.AsyncClient(transport=transport)
        self.clients.append(client)
        return client

    def builder(self) -> Any:
        """Session settings for the fake: its root, ``admin``/``apple``, and its transport."""
        return self.Session.builder().base_url(BASE).basic_auth("admin", "apple").http_client(self.http())

    def session(self, builder: Any = None) -> Any:
        return (builder or self.builder()).build()

    def run(self, value: Any) -> Any:
        if inspect.isawaitable(value):
            assert self.loop is not None, "a sync call returned an awaitable"
            return self.loop.run_until_complete(value)
        return value

    def gather(self, *calls: Callable[[], Any]) -> list[Any]:
        """Runs calls at the same time: as tasks for asyncio, as threads for sync."""
        if self.loop is not None:

            async def all_of() -> list[Any]:
                return list(await asyncio.gather(*(c() for c in calls)))

            return self.loop.run_until_complete(all_of())
        results: list[Any] = [None] * len(calls)
        errors: list[BaseException] = []

        def go(i: int, c: Callable[[], Any]) -> None:
            try:
                results[i] = c()
            except BaseException as e:  # noqa: BLE001
                errors.append(e)

        threads = [threading.Thread(target=go, args=(i, c)) for i, c in enumerate(calls)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        if errors:
            raise errors[0]
        return results

    def raises(self, error: Any, value: Any) -> Any:
        """The error a call fails with (``pytest.raises`` on what ``run`` does)."""
        with pytest.raises(error) as caught:
            self.run(value() if callable(value) else value)
        return caught.value


@pytest.fixture(params=["sync", "async"])
def rt(request: pytest.FixtureRequest, fake: FakeTm1) -> Any:
    loop = None if request.param == "sync" else asyncio.new_event_loop()
    runtime = Runtime(request.param, fake, loop)
    yield runtime
    for client in runtime.clients:
        if loop is not None:
            loop.run_until_complete(client.aclose())
        else:
            client.close()
    if loop is not None:
        loop.close()
