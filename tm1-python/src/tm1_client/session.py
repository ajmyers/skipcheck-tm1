"""The session: one logged-in connection to a TM1 server, and views of it.

Like the Java client's ``Tm1Session``. It logs in lazily with ``GET ActiveSession`` and the
authenticator's header, then sends only the ``TM1SessionId`` cookie; when TM1 answers 401
(the session expired or was closed) it logs in again once and resends. ``close()`` ends the
session on the server (``ActiveSession/tm1.Close``).

A *view* shares the login and connections, and changes how requests are sent:

* :meth:`Tm1Session.with_sandbox`: in a sandbox (``!sandbox``);
* :meth:`Tm1Session.with_change_set`: recorded in a change set (``!ChangeSet``), so
  ``UndoChangeSet`` can revert them;
* :meth:`Tm1Session.with_async`: as OData async requests (``Prefer: respond-async``), polled
  until done, so a long process never holds a request open;
* :meth:`Tm1Session.batch`: queued, and sent together as JSON ``$batch``.

Views are derived rather than set, so one caller's sandbox can't leak into another's requests.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import replace
from datetime import timedelta
from typing import Any, Iterator

import httpx

from . import _protocol as p
from .auth import Authenticator
from .errors import Tm1ProtocolError, Tm1TransportError, Tm1UnsupportedOperationError, api_error
from .odata import ODataPath, ODataQuery

__all__ = ["Tm1Batch", "Tm1Session"]

log = logging.getLogger("tm1_client")


def poll_interval(every: float | timedelta) -> float:
    seconds = every.total_seconds() if isinstance(every, timedelta) else float(every)
    if seconds <= 0:
        raise ValueError(f"The poll interval must be positive: {every}")
    return seconds


def unsupported(call: p.Call, caps: p.Capabilities) -> Tm1UnsupportedOperationError:
    from ._generated.index import SINCE

    since = SINCE.get(call.operation or "")
    return Tm1UnsupportedOperationError(
        f"{call.operation} isn't in this server's $metadata (schema {caps.schema_version or 'unknown'})"
        + (f"; TM1 added it in schema {since}" if since else "")
    )


def items_only(call: p.Call) -> p.Call:
    """A paged call read as its first page's items, for a batch, which can't follow links."""

    def read(reply: p.Reply) -> Any:
        page = call.read(reply)
        if page.next_link is not None:
            raise Tm1ProtocolError(
                f"{call.path}: TM1 sent more than one page, which a batch can't follow; ask for fewer with top="
            )
        return page.items

    return replace(call, read=read)


class _Connection:
    """The login, cookie and HTTP connections every view of a session shares."""

    def __init__(
        self,
        base_url: str,
        authenticator: Authenticator,
        session_context: str | None,
        http: httpx.Client,
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
        self.lock = threading.Lock()
        self.caps: p.Capabilities | None = None

    def url(self, relative: str) -> str:
        return str(httpx.URL(self.base_url).join(relative))

    def login(self, expired: str | None = None) -> str:
        with self.lock:
            if self.cookie is not None and self.cookie != expired:
                return self.cookie
            url = self.base_url + p.LOGIN
            headers = p.headers(self.session_context, extra={"Authorization": self.authenticator.authorization()})
            try:
                response = self.http.get(url, headers=headers)
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
            log.debug("Logged in to %s as %s", self.base_url, self.authenticator.describe())
            self.cookie = cookie
            return cookie

    def send(self, method: str, url: str, content: bytes | None, headers: dict[str, str], accept: str) -> p.Reply:
        """Sends a request with the session cookie, logging in again once on 401."""
        cookie = self.cookie or self.login()
        for attempt in (1, 2):
            try:
                response = self.http.request(
                    method,
                    url,
                    content=content,
                    headers=p.headers(self.session_context, accept, {**headers, "Cookie": f"{p.COOKIE}={cookie}"}),
                )
            except httpx.HTTPError as e:
                raise Tm1TransportError(f"{method} {url} failed: {e}") from e
            # The session cookie is this session's to send, not the HTTP client's jar
            self.http.cookies.clear()
            if response.status_code == 401 and attempt == 1:
                log.debug("TM1 session expired; logging in again")
                cookie = self.login(expired=cookie)
                continue
            return p.Reply(response.status_code, {k.lower(): v for k, v in response.headers.items()}, response.content)
        raise AssertionError("unreachable")

    def capabilities(self) -> p.Capabilities:
        if self.caps is None:
            url = self.base_url + p.METADATA
            reply = p.check("GET", url, self.send("GET", url, None, {}, "application/xml"))
            self.caps = p.capabilities(reply.content)
        return self.caps

    def close(self) -> None:
        cookie, self.cookie = self.cookie, None
        try:
            if cookie is not None:
                try:
                    self.http.post(
                        self.base_url + p.CLOSE,
                        headers=p.headers(self.session_context, extra={"Cookie": f"{p.COOKIE}={cookie}"}),
                    )
                except httpx.HTTPError as e:
                    log.debug("Closing the TM1 session failed: %s", e)
        finally:
            if self.owns_http:
                self.http.close()


class Tm1Session:
    """A session with the TM1 REST API at ``base_url`` (``https://host:port/api/v1/``).

    Use it as a context manager, or call :meth:`close`::

        with Tm1Session("https://tm1:8010/api/v1", auth.basic("admin", "apple")) as session:
            client = Tm1Client(session)

    :param session_context: sent as ``TM1-SessionContext``, which TM1 shows for the session
    :param sandbox: run every request in this sandbox (``!sandbox``); see :meth:`with_sandbox`
    :param check_capabilities: before calling an operation, check the server's ``$metadata``
        has it (read once, at the first operation), and refuse it if not
    :param client: an ``httpx.Client`` to send through (for TLS, proxies, transports, tests)
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
        client: httpx.Client | None = None,
    ):
        http = client or httpx.Client(verify=verify, timeout=timeout)
        self._connection = _Connection(base_url, authenticator, session_context, http, client is None, check_capabilities)
        self._context = p.Context(sandbox=sandbox)
        self._async_every: float | None = None
        self._queue: _Queue | None = None

    def _view(self, context: p.Context, async_every: float | None, queue: _Queue | None) -> Tm1Session:
        view = object.__new__(type(self))
        view._connection = self._connection
        view._context = context
        view._async_every = async_every
        view._queue = queue
        return view

    # Views

    def with_sandbox(self, name: str) -> Tm1Session:
        """A view whose requests run in sandbox ``name``."""
        if not name or not name.strip():
            raise ValueError("sandbox name is blank")
        return self._view(p.Context(name, self._context.change_set), self._async_every, self._queue)

    def without_sandbox(self) -> Tm1Session:
        """A view whose requests run in base data."""
        return self._view(p.Context(None, self._context.change_set), self._async_every, self._queue)

    def with_change_set(self, change_set: str) -> Tm1Session:
        """A view whose writes are recorded in change set ``change_set`` (from ``BeginChangeSet``)."""
        if not change_set or not change_set.strip():
            raise ValueError("change set ID is blank")
        return self._view(p.Context(self._context.sandbox, change_set), self._async_every, self._queue)

    def without_change_set(self) -> Tm1Session:
        return self._view(p.Context(self._context.sandbox, None), self._async_every, self._queue)

    def with_async(self, every: float | timedelta = 1.0) -> Tm1Session:
        """A view whose requests TM1 runs asynchronously: each returns at once with a monitor,
        polled every ``every`` (seconds) until the result is there. Methods return what they
        always do. Interrupting the wait deletes the monitor, which stops the work on TM1.
        TM1 (11.8) sends no ``Retry-After``, so ``every`` is the only pace."""
        return self._view(self._context, poll_interval(every), self._queue)

    def without_async(self) -> Tm1Session:
        return self._view(self._context, None, self._queue)

    def batch(self) -> Tm1Batch:
        """Starts a JSON ``$batch``: requests through its :attr:`Tm1Batch.session` are queued, and
        return :class:`~tm1_client._protocol.Deferred` results, there once it has run."""
        queue = _Queue()
        return Tm1Batch(queue, self._view(self._context, None, None), self._view(self._context, None, queue))

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
    def authenticator(self) -> Authenticator:
        return self._connection.authenticator

    # Lifecycle

    def __enter__(self) -> Tm1Session:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    @property
    def logged_in(self) -> bool:
        return self._connection.cookie is not None

    @property
    def session_id(self) -> str | None:
        """The ``TM1SessionId`` cookie, once logged in."""
        return self._connection.cookie

    def login(self) -> None:
        """Logs in now, rather than with the first request."""
        self._connection.login()

    def close(self) -> None:
        """Ends the session on the server, for every view, and releases the connections."""
        self._connection.close()

    def capabilities(self) -> p.Capabilities:
        """What the server's own ``$metadata`` defines, read once per session."""
        return self._connection.capabilities()

    # Sending

    def execute(self, call: p.Call) -> Any:
        """Sends a call and reads its answer; in a batch, queues it and returns a ``Deferred``."""
        if call.operation is not None and self._connection.check_capabilities:
            caps = self._connection.capabilities()
            if not caps.supports(call.operation):
                raise unsupported(call, caps)
        if self._queue is not None:
            return self._queue.add(call, self._context)
        url = self._connection.url(p.target(call, self._context))
        content, headers = call.body()
        if self._async_every is not None and not call.raw:
            reply = self._send_async(call.method, url, content, headers, call.accept)
        else:
            reply = self._connection.send(call.method, url, content, headers, call.accept)
        return call.read(p.check(call.method, url, reply))

    def collect(self, call: p.Call) -> Any:
        """Every item of a paged collection, following ``@odata.nextLink``; in a batch, the
        first page's items (as a ``Deferred``)."""
        if self._queue is not None:
            return self.execute(items_only(call))
        return list(self.iterate(call))

    def iterate(self, call: p.Call) -> Iterator[Any]:
        """The items of a paged collection one by one, fetching pages as they're needed."""
        if self._queue is not None:
            raise RuntimeError("A collection can't be iterated in a batch: use list(), which returns its first page")
        page = self.execute(call)
        while True:
            yield from page.items
            if page.next_link is None:
                return
            # The link carries the query and TM1's options already
            page = self.execute(p.Call("GET", page.next_link, read=call.read, contextual=False))

    def _send_async(self, method: str, url: str, content: bytes | None, headers: dict[str, str], accept: str) -> p.Reply:
        assert self._async_every is not None
        connection = self._connection
        reply = connection.send(method, url, content, {**headers, "Prefer": "respond-async"}, accept)
        if reply.status != 202:
            return reply  # TM1 answered at once
        location = reply.headers.get("location")
        if not location:
            raise Tm1ProtocolError(f"TM1 accepted {method} {url} async without a Location")
        # The Location is relative to the request's URL: ../_async('id')
        monitor = str(httpx.URL(url).join(location))
        finished = False
        try:
            while True:
                time.sleep(self._async_every)
                polled = p.check("GET", monitor, connection.send("GET", monitor, None, {}, "*/*"))
                if polled.status != 202:
                    finished = True
                    return p.async_result(polled.content)
        finally:
            if not finished:
                # Deleting the monitor is what stops the work on TM1
                try:
                    connection.send("DELETE", monitor, None, {}, p.JSON)
                except Exception as e:  # noqa: BLE001
                    log.debug("Couldn't delete the async monitor %s: %s", monitor, e)

    # Raw requests, for hand-written code

    def request(
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
        """Sends any request through this view, and returns TM1's answer (raising for a refusal)."""
        return self.execute(p.Call(method, str(path), query, json_body, content, headers or {}, accept, read=lambda r: r))

    def get_json(self, path: ODataPath | str, query: ODataQuery | None = None) -> Any:
        return self.request("GET", path, query=query).json()


class _Queue:
    def __init__(self) -> None:
        self.entries: list[tuple[p.Call, p.Context, p.Deferred[Any]]] = []
        self.ran = False

    def add(self, call: p.Call, context: p.Context) -> p.Deferred[Any]:
        if self.ran:
            raise RuntimeError(
                "The batch has already run, so this call can't join it: a call whose request depends on "
                f"another's result can't be batched. {call.method} {call.path}"
            )
        if call.raw:
            raise ValueError(f"Bytes can't go in a JSON batch: {call.method} {call.path}")
        deferred: p.Deferred[Any] = p.Deferred(call)
        self.entries.append((call, context, deferred))
        return deferred

    def chunks(self, per_request: int) -> Iterator[list[tuple[p.Call, p.Context, p.Deferred[Any]]]]:
        if self.ran:
            raise RuntimeError("The batch has already run")
        self.ran = True
        for start in range(0, len(self.entries), per_request):
            yield self.entries[start : start + per_request]


def settle(chunk: list[tuple[p.Call, p.Context, p.Deferred[Any]]], reply: p.Reply, url: Any) -> None:
    replies = p.batch_replies(reply.json(), len(chunk))
    for (call, context, deferred), item in zip(chunk, replies):
        deferred._settle(url(p.target(call, context)), item)


class Tm1Batch:
    """A JSON ``$batch``: what's sent through :attr:`session` (or :attr:`client`) is queued, and
    :meth:`run` sends it together. As a context manager it runs on leaving the block::

        with tm1.batch() as batch:
            a = batch.client.cubes["A"].get()
            b = batch.client.cubes["B"].get()
        print(a.result().name, b.result().name)

    TM1 (11.8) runs a batch's requests in order (a later one sees an earlier one's writes), and
    carries on past failed ones: each result holds its own value or error. Multipart batches,
    ``atomicityGroup`` and ``dependsOn`` are refused, so none are used; 1000 requests in one
    batch work, and :meth:`run` splits a bigger queue.
    """

    def __init__(self, queue: _Queue, sender: Tm1Session, session: Tm1Session):
        self._queue = queue
        self._sender = sender
        self.session = session

    @property
    def client(self) -> Any:
        """A :class:`~tm1_client.Tm1Client` whose calls join this batch."""
        from .client import Tm1Client

        return Tm1Client(self.session)

    def __len__(self) -> int:
        return len(self._queue.entries)

    def __enter__(self) -> Tm1Batch:
        return self

    def __exit__(self, exc_type: object, *exc: object) -> None:
        if exc_type is None and not self._queue.ran:
            self.run()

    def run(self, per_request: int = 1000) -> None:
        """Sends the queued requests, at most ``per_request`` to each ``$batch``."""
        for chunk in self._queue.chunks(per_request):
            body = p.batch_body([(call, context) for call, context, _ in chunk])
            try:
                reply = self._sender.execute(p.Call("POST", "$batch", json_body=body, read=lambda r: r, contextual=False))
            except Exception as e:  # noqa: BLE001 (each result gets it)
                for _, _, deferred in chunk:
                    deferred._fail(e)
                continue
            settle(chunk, reply, self._sender._connection.url)
