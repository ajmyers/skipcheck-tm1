"""The asyncio session: :class:`~tm1_client.session.Tm1Session`, on ``httpx.AsyncClient``.

It behaves the same (lazy login, cookie, re-login on 401, views for sandboxes, change sets,
async operations and batches), since both are loops over :mod:`tm1_client._protocol`.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import timedelta
from typing import Any, AsyncIterator

import httpx

from .. import _protocol as p
from ..auth import Authenticator
from ..errors import Tm1ProtocolError, Tm1TransportError, api_error
from ..odata import ODataPath, ODataQuery
from ..session import _Queue, items_only, poll_interval, settle, unsupported

__all__ = ["AsyncTm1Batch", "AsyncTm1Session"]

log = logging.getLogger("tm1_client")


class _AsyncConnection:
    def __init__(
        self,
        base_url: str,
        authenticator: Authenticator,
        session_context: str | None,
        http: httpx.AsyncClient,
        owns_http: bool,
        check_capabilities: bool,
    ):
        self.base_url = base_url if base_url.endswith("/") else base_url + "/"
        self.authenticator = authenticator
        self.session_context = session_context
        self.http = http
        self.owns_http = owns_http
        self.check_capabilities = check_capabilities
        self.cookie: str | None = None
        self.lock = asyncio.Lock()
        self.caps: p.Capabilities | None = None

    def url(self, relative: str) -> str:
        return str(httpx.URL(self.base_url).join(relative))

    async def login(self, expired: str | None = None) -> str:
        async with self.lock:
            if self.cookie is not None and self.cookie != expired:
                return self.cookie
            url = self.base_url + p.LOGIN
            headers = p.headers(self.session_context, extra={"Authorization": self.authenticator.authorization()})
            try:
                response = await self.http.get(url, headers=headers)
            except httpx.HTTPError as e:
                raise Tm1TransportError(
                    f"Logging in to {self.base_url} as {self.authenticator.describe()} failed: {e}"
                ) from e
            self.http.cookies.clear()
            if response.status_code >= 400:
                raise api_error("GET", url, response.status_code, response.content)
            cookie = p.session_cookie(response.headers.get_list("set-cookie"))
            if not cookie:
                raise Tm1ProtocolError(f"TM1 at {self.base_url} accepted the login but set no {p.COOKIE} cookie")
            self.cookie = cookie
            return cookie

    async def send(self, method: str, url: str, content: bytes | None, headers: dict[str, str], accept: str) -> p.Reply:
        cookie = self.cookie or await self.login()
        for attempt in (1, 2):
            try:
                response = await self.http.request(
                    method,
                    url,
                    content=content,
                    headers=p.headers(self.session_context, accept, {**headers, "Cookie": f"{p.COOKIE}={cookie}"}),
                )
            except httpx.HTTPError as e:
                raise Tm1TransportError(f"{method} {url} failed: {e}") from e
            self.http.cookies.clear()
            if response.status_code == 401 and attempt == 1:
                cookie = await self.login(expired=cookie)
                continue
            return p.Reply(response.status_code, {k.lower(): v for k, v in response.headers.items()}, response.content)
        raise AssertionError("unreachable")

    async def capabilities(self) -> p.Capabilities:
        if self.caps is None:
            url = self.base_url + p.METADATA
            reply = p.check("GET", url, await self.send("GET", url, None, {}, "application/xml"))
            self.caps = p.capabilities(reply.content)
        return self.caps

    async def close(self) -> None:
        cookie, self.cookie = self.cookie, None
        try:
            if cookie is not None:
                try:
                    await self.http.post(
                        self.base_url + p.CLOSE,
                        headers=p.headers(self.session_context, extra={"Cookie": f"{p.COOKIE}={cookie}"}),
                    )
                except httpx.HTTPError as e:
                    log.debug("Closing the TM1 session failed: %s", e)
        finally:
            if self.owns_http:
                await self.http.aclose()


class AsyncTm1Session:
    """A session with the TM1 REST API, for asyncio; see :class:`~tm1_client.session.Tm1Session`::

        async with AsyncTm1Session("https://tm1:8010/api/v1", auth.basic("admin", "apple")) as session:
            client = AsyncTm1Client(session)
    """

    def __init__(
        self,
        base_url: str,
        authenticator: Authenticator,
        *,
        session_context: str | None = "tm1-client (Python)",
        sandbox: str | None = None,
        check_capabilities: bool = True,
        verify: bool | str = True,
        timeout: float | httpx.Timeout = 60.0,
        client: httpx.AsyncClient | None = None,
    ):
        http = client or httpx.AsyncClient(verify=verify, timeout=timeout)
        self._connection = _AsyncConnection(
            base_url, authenticator, session_context, http, client is None, check_capabilities
        )
        self._context = p.Context(sandbox=sandbox)
        self._async_every: float | None = None
        self._queue: _Queue | None = None

    def _view(self, context: p.Context, async_every: float | None, queue: _Queue | None) -> AsyncTm1Session:
        view = object.__new__(type(self))
        view._connection = self._connection
        view._context = context
        view._async_every = async_every
        view._queue = queue
        return view

    def with_sandbox(self, name: str) -> AsyncTm1Session:
        if not name or not name.strip():
            raise ValueError("sandbox name is blank")
        return self._view(p.Context(name, self._context.change_set), self._async_every, self._queue)

    def without_sandbox(self) -> AsyncTm1Session:
        return self._view(p.Context(None, self._context.change_set), self._async_every, self._queue)

    def with_change_set(self, change_set: str) -> AsyncTm1Session:
        if not change_set or not change_set.strip():
            raise ValueError("change set ID is blank")
        return self._view(p.Context(self._context.sandbox, change_set), self._async_every, self._queue)

    def without_change_set(self) -> AsyncTm1Session:
        return self._view(p.Context(self._context.sandbox, None), self._async_every, self._queue)

    def with_async(self, every: float | timedelta = 1.0) -> AsyncTm1Session:
        return self._view(self._context, poll_interval(every), self._queue)

    def without_async(self) -> AsyncTm1Session:
        return self._view(self._context, None, self._queue)

    def batch(self) -> AsyncTm1Batch:
        queue = _Queue()
        return AsyncTm1Batch(queue, self._view(self._context, None, None), self._view(self._context, None, queue))

    @property
    def sandbox(self) -> str | None:
        return self._context.sandbox

    @property
    def change_set(self) -> str | None:
        return self._context.change_set

    @property
    def base_url(self) -> str:
        return self._connection.base_url

    @property
    def logged_in(self) -> bool:
        return self._connection.cookie is not None

    @property
    def session_id(self) -> str | None:
        return self._connection.cookie

    async def __aenter__(self) -> AsyncTm1Session:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def login(self) -> None:
        await self._connection.login()

    async def close(self) -> None:
        await self._connection.close()

    async def capabilities(self) -> p.Capabilities:
        return await self._connection.capabilities()

    async def execute(self, call: p.Call) -> Any:
        if call.operation is not None and self._connection.check_capabilities:
            caps = await self._connection.capabilities()
            if not caps.supports(call.operation):
                raise unsupported(call, caps)
        if self._queue is not None:
            return self._queue.add(call, self._context)
        url = self._connection.url(p.target(call, self._context))
        content, headers = call.body()
        if self._async_every is not None and not call.raw:
            reply = await self._send_async(call.method, url, content, headers, call.accept)
        else:
            reply = await self._connection.send(call.method, url, content, headers, call.accept)
        return call.read(p.check(call.method, url, reply))

    async def collect(self, call: p.Call) -> Any:
        if self._queue is not None:
            return await self.execute(items_only(call))
        return [item async for item in self.iterate(call)]

    async def iterate(self, call: p.Call) -> AsyncIterator[Any]:
        if self._queue is not None:
            raise RuntimeError("A collection can't be iterated in a batch: use list(), which returns its first page")
        page = await self.execute(call)
        while True:
            for item in page.items:
                yield item
            if page.next_link is None:
                return
            page = await self.execute(p.Call("GET", page.next_link, read=call.read, contextual=False))

    async def _send_async(
        self, method: str, url: str, content: bytes | None, headers: dict[str, str], accept: str
    ) -> p.Reply:
        assert self._async_every is not None
        connection = self._connection
        reply = await connection.send(method, url, content, {**headers, "Prefer": "respond-async"}, accept)
        if reply.status != 202:
            return reply
        location = reply.headers.get("location")
        if not location:
            raise Tm1ProtocolError(f"TM1 accepted {method} {url} async without a Location")
        monitor = str(httpx.URL(url).join(location))
        finished = False
        try:
            while True:
                await asyncio.sleep(self._async_every)
                polled = p.check("GET", monitor, await connection.send("GET", monitor, None, {}, "*/*"))
                if polled.status != 202:
                    finished = True
                    return p.async_result(polled.content)
        finally:
            if not finished:
                # Deleting the monitor is what stops the work on TM1 (also when the task is cancelled)
                try:
                    await asyncio.shield(connection.send("DELETE", monitor, None, {}, p.JSON))
                except BaseException as e:  # noqa: BLE001
                    log.debug("Couldn't delete the async monitor %s: %s", monitor, e)

    async def request(
        self,
        method: str,
        path: ODataPath | str,
        *,
        query: ODataQuery | None = None,
        json_body: Any = None,
        content: bytes | None = None,
        headers: dict[str, str] | None = None,
        accept: str = p.JSON,
    ) -> p.Reply:
        return await self.execute(
            p.Call(method, str(path), query, json_body, content, headers or {}, accept, read=lambda r: r)
        )

    async def get_json(self, path: ODataPath | str, query: ODataQuery | None = None) -> Any:
        return (await self.request("GET", path, query=query)).json()


class AsyncTm1Batch:
    """A JSON ``$batch``, for asyncio; see :class:`~tm1_client.session.Tm1Batch`. Calls through
    :attr:`client` return :class:`~tm1_client._protocol.Deferred` results (awaiting them is
    immediate)::

        async with tm1.batch() as batch:
            a = await batch.client.cubes["A"].get()
        print(a.result().name)
    """

    def __init__(self, queue: _Queue, sender: AsyncTm1Session, session: AsyncTm1Session):
        self._queue = queue
        self._sender = sender
        self.session = session

    @property
    def client(self) -> Any:
        from .client import AsyncTm1Client

        return AsyncTm1Client(self.session)

    def __len__(self) -> int:
        return len(self._queue.entries)

    async def __aenter__(self) -> AsyncTm1Batch:
        return self

    async def __aexit__(self, exc_type: object, *exc: object) -> None:
        if exc_type is None and not self._queue.ran:
            await self.run()

    async def run(self, per_request: int = 1000) -> None:
        for chunk in self._queue.chunks(per_request):
            body = p.batch_body([(call, context) for call, context, _ in chunk])
            try:
                reply = await self._sender.execute(p.Call("POST", "$batch", json_body=body, read=lambda r: r, contextual=False))
            except Exception as e:  # noqa: BLE001
                for _, _, deferred in chunk:
                    deferred._fail(e)
                continue
            settle(chunk, reply, self._sender._connection.url)
