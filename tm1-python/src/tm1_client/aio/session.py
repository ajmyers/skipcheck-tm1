"""The session: one logged-in connection to a TM1 server, and views of it.

Like the Java client's ``Tm1Session``. It logs in lazily with ``GET ActiveSession`` and the
authenticator's header, then sends only the ``TM1SessionId`` cookie; when TM1 answers 401
(the session expired or was closed) it logs in again once and resends. ``close()`` ends the
session on the server (``ActiveSession/tm1.Close``).

A *view* shares the login and connections, and changes how requests are sent:

* :meth:`AsyncTm1Session.with_sandbox`: in a sandbox (``!sandbox``);
* :meth:`AsyncTm1Session.with_change_set`: recorded in a change set (``!ChangeSet``), so
  ``UndoChangeSet`` can revert them;
* :meth:`AsyncTm1Session.with_async`: as OData async requests (``Prefer: respond-async``),
  polled until done, so a long process never holds a request open;
* :meth:`AsyncTm1Session.batch`: queued, and sent together as JSON ``$batch``.

Views are derived rather than set, so one caller's sandbox can't leak into another's requests.

This module is the source of truth for both flavors: the sync ``Tm1Session`` is generated
from it (``tm1_pygen.unasync``).
"""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any, AsyncIterator

import httpx

from .. import _protocol as p
from ..auth import Authenticator, basic
from ..errors import Tm1ErrorClassifier
from ..timeouts import Tm1Timeouts
from ..version import Tm1Dialect
from ..errors import Tm1ProtocolError, Tm1TransportError, api_error
from ..odata import ODataPath, ODataQuery
from .._compat import AsyncIO as runtime
from .._views import CAPABILITIES, Queue, as_call, capability_of, items_only, poll_interval, settle
from ..request import Tm1Request
from ..version import Tm1Capabilities, Tm1Version

__all__ = ["AsyncTm1Session", "AsyncTm1SessionBatch", "AsyncTm1SessionBuilder"]

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
        self.lock = runtime.Lock()
        self.version: Tm1Version | None = None

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

    async def send(
        self,
        method: str,
        url: str,
        content: bytes | None,
        headers: dict[str, str],
        accept: str,
        timeout: float | None = None,
    ) -> p.Reply:
        cookie = self.cookie or await self.login()
        for attempt in (1, 2):
            try:
                response = await self.http.request(
                    method,
                    url,
                    content=content,
                    headers=p.headers(self.session_context, accept, {**headers, "Cookie": f"{p.COOKIE}={cookie}"}),
                    **({} if timeout is None else {"timeout": timeout}),
                )
            except httpx.HTTPError as e:
                raise Tm1TransportError(f"{method} {url} failed: {e}") from e
            self.http.cookies.clear()
            if response.status_code == 401 and attempt == 1:
                cookie = await self.login(expired=cookie)
                continue
            return p.Reply(response.status_code, {k.lower(): v for k, v in response.headers.items()}, response.content)
        raise AssertionError("unreachable")

    async def product_version(self) -> Tm1Version:
        """The server's version (``Configuration/ProductVersion``), read once per connection."""
        if self.version is None:
            url = self.base_url + "Configuration?$select=ProductVersion"
            doc = p.check("GET", url, await self.send("GET", url, None, {}, p.JSON)).json()
            text = doc.get("ProductVersion") if isinstance(doc, dict) else None
            if not text:
                raise Tm1ProtocolError(f"TM1 at {self.base_url} didn't report its ProductVersion")
            self.version = Tm1Version.parse(text)
        return self.version

    async def capabilities(self) -> Tm1Capabilities:
        """What the server's ``$metadata`` defines: read once per server and version, for
        every session in the process (a server that's upgraded reports a new version)."""
        version = await self.product_version()
        key = (self.base_url, str(version))
        cached = CAPABILITIES.get(key)
        if cached is None:
            url = self.base_url + p.METADATA
            reply = p.check("GET", url, await self.send("GET", url, None, {}, "application/xml"))
            cached = Tm1Capabilities.parse(reply.content, version)
            CAPABILITIES[key] = cached
        return cached

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
    """A session with the TM1 REST API at ``base_url`` (``https://host:port/api/v1/``)::

        async with AsyncTm1Session("https://tm1:8010/api/v1", auth.basic("admin", "apple")) as session:
            client = AsyncTm1Client(session)
    """

    def __init__(
        self,
        base_url: str | None = None,
        authenticator: Authenticator | None = None,
        *,
        address: str | None = None,
        dialect: Tm1Dialect = Tm1Dialect.V11,
        user: str | None = None,
        password: str | None = None,
        impersonate: str | None = None,
        session_context: str | None = "tm1-client (Python)",
        sandbox: str | None = None,
        timeouts: Tm1Timeouts | None = None,
        error_classifier: Tm1ErrorClassifier | None = None,
        check_capabilities: bool = True,
        verify: bool | str = True,
        timeout: float | httpx.Timeout | None = None,
        client: httpx.AsyncClient | None = None,
    ):
        """The server is ``base_url`` (the service root), or ``address`` read by ``dialect``
        (``http://tm1:8010`` → ``http://tm1:8010/api/v1/``). The login is ``authenticator``,
        or ``user`` and ``password`` for TM1's own security. See :meth:`builder` for the same
        settings one by one, as in the Java client."""
        if (base_url is None) == (address is None):
            raise ValueError("Give the server as exactly one of base_url and address")
        if base_url is None:
            assert address is not None
            base_url = dialect.service_root(address)
        if authenticator is None:
            if user is None:
                raise ValueError("Give an authenticator, or a user (and password) for basic authentication")
            authenticator = basic(user, password)
        self._settings = {
            "impersonate": impersonate,
            "timeouts": timeouts or Tm1Timeouts.DEFAULTS,
            "error_classifier": error_classifier or Tm1ErrorClassifier.defaults(),
        }
        http = client or runtime.HttpClient(verify=verify, timeout=timeout if timeout is not None else 600.0)
        self._connection = _AsyncConnection(
            base_url, authenticator, session_context, http, client is None, check_capabilities
        )
        self._context = p.Context(sandbox=sandbox)
        self._async_every: float | None = None
        self._queue: Queue | None = None

    def _view(self, context: p.Context, async_every: float | None, queue: Queue | None) -> AsyncTm1Session:
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

    def batch(self) -> AsyncTm1SessionBatch:
        """Starts a JSON ``$batch``: its :attr:`~AsyncTm1SessionBatch.session` is a view of this
        one (same sandbox and change set) whose requests are queued, and
        :meth:`~AsyncTm1SessionBatch.run` sends them together."""
        queue = Queue()
        return AsyncTm1SessionBatch(queue, self._view(self._context, None, None), self._view(self._context, None, queue))

    @property
    def async_every(self) -> float | None:
        """How often this view polls an async request's monitor (Java's ``async()``), or None."""
        return self._async_every

    @staticmethod
    def builder() -> AsyncTm1SessionBuilder:
        """The settings one by one, as the Java client's ``Tm1Session.builder()``."""
        return AsyncTm1SessionBuilder()

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

    async def version(self) -> Tm1Version:
        """The server's product version, such as ``11.8.03500.6``."""
        return await self._connection.product_version()

    async def capabilities(self) -> Tm1Capabilities:
        """What the server's own ``$metadata`` defines, and its versions."""
        return await self._connection.capabilities()

    @property
    def service_root(self) -> str:
        return self._connection.base_url

    async def _check(self, call: p.Call) -> None:
        needed = capability_of(call)
        if needed is not None and self._connection.check_capabilities:
            refused = (await self._connection.capabilities()).check(needed, self._connection.base_url)
            if refused is not None:
                raise refused

    async def execute(self, request: Tm1Request[Any] | p.Call) -> Any:
        """Sends a request and reads its answer; in a batch, queues it and returns a ``Deferred``.
        A collection is read with :meth:`stream` or :meth:`collect`."""
        if isinstance(request, Tm1Request) and request.response is Tm1Request.Response.COLLECTION:
            raise ValueError(f"Collection requests are streamed: use stream() or collect() for {request}")
        call = as_call(request)
        await self._check(call)
        if self._queue is not None:
            return self._queue.add(call, self._context)
        url = self._connection.url(p.target(call, self._context))
        content, headers = call.body()
        if self._async_every is not None and not call.raw:
            reply = await self._send_async(call.method, url, content, headers, call.accept)
        else:
            reply = await self._connection.send(call.method, url, content, headers, call.accept, call.timeout)
        return call.read(p.check(call.method, url, reply))

    def stream(self, request: Tm1Request[Any] | p.Call) -> AsyncIterator[Any]:
        """The items of a collection, fetching pages as they're needed (Java's ``stream``)."""
        if isinstance(request, Tm1Request) and request.response is not Tm1Request.Response.COLLECTION:
            raise ValueError(f"Only collection requests are streamed: use execute() for {request}")
        return self.iterate(as_call(request))

    async def collect(self, request: Tm1Request[Any] | p.Call) -> Any:
        """Every item of a collection, as a list; in a batch, the first page's (a ``Deferred``)."""
        call = as_call(request)
        if self._queue is not None:
            return await self.execute(items_only(call))
        return [item async for item in self.iterate(call)]

    async def iterate(self, call: p.Call) -> AsyncIterator[Any]:
        if self._queue is not None:
            raise RuntimeError("A collection can't be iterated in a batch: use collect(), which returns its first page")
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
                await runtime.sleep(self._async_every)
                polled = p.check("GET", monitor, await connection.send("GET", monitor, None, {}, "*/*"))
                if polled.status != 202:
                    finished = True
                    return p.async_result(polled.content)
        finally:
            if not finished:
                # Deleting the monitor is what stops the work on TM1 (also when the task is cancelled)
                try:
                    await runtime.shield(connection.send("DELETE", monitor, None, {}, p.JSON))
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


class AsyncTm1SessionBatch:
    """A JSON ``$batch``: calls through :attr:`client` (or :attr:`session`) are queued, return
    :class:`~tm1_client._protocol.Deferred` results, and :meth:`run` sends them together; as a
    context manager it runs on leaving the block::

        async with tm1.batch() as batch:
            a = await batch.client.cubes.get("A")
        print(a.result().name)

    TM1 (11.8) runs a batch's requests in order (a later one sees an earlier one's writes), and
    carries on past failed ones: each result holds its own value or error. Multipart batches,
    ``atomicityGroup`` and ``dependsOn`` are refused, so none are used; 1000 requests in one
    batch work, and :meth:`run` splits a bigger queue.
    """

    DEFAULT_PER_REQUEST = 1000

    def __init__(self, queue: Queue, sender: AsyncTm1Session, session: AsyncTm1Session):
        self._queue = queue
        self._sender = sender
        self._session = session

    @property
    def session(self) -> AsyncTm1Session:
        """The view whose requests join this batch."""
        return self._session

    @property
    def client(self) -> Any:
        """A client over :attr:`session`, whose calls join this batch."""
        from .client import AsyncTm1Client

        return AsyncTm1Client(self._session)

    def size(self) -> int:
        """How many requests are queued."""
        return len(self._queue.entries)

    def __len__(self) -> int:
        return self.size()

    async def __aenter__(self) -> AsyncTm1SessionBatch:
        return self

    async def __aexit__(self, exc_type: object, *exc: object) -> None:
        if exc_type is None and not self._queue.ran:
            await self.run()

    async def run(self, per_request: int = DEFAULT_PER_REQUEST) -> None:
        for chunk in self._queue.chunks(per_request):
            body = p.batch_body([(call, context) for call, context, _ in chunk])
            try:
                reply = await self._sender.execute(p.Call("POST", "$batch", json_body=body, read=lambda r: r, contextual=False))
            except Exception as e:  # noqa: BLE001
                for _, _, deferred in chunk:
                    deferred._fail(e)
                continue
            settle(chunk, reply, self._sender._connection.url)


class AsyncTm1SessionBuilder:
    """A session's settings one by one, as the Java client's ``Tm1Session.Builder``; each
    method returns the builder, and :meth:`build` makes the session. The pool copies one per
    identity."""

    def __init__(self, settings: dict[str, Any] | None = None):
        self._settings: dict[str, Any] = dict(settings or {})

    def copy(self) -> AsyncTm1SessionBuilder:
        return type(self)(self._settings)

    def settings(self) -> dict[str, Any]:
        return dict(self._settings)

    def _set(self, **values: Any) -> AsyncTm1SessionBuilder:
        self._settings.update(values)
        return self

    def base_url(self, base_url: str) -> AsyncTm1SessionBuilder:
        """The REST API's root, such as ``http://tm1:8010/api/v1/``."""
        self._settings.pop("address", None)
        return self._set(base_url=base_url)

    def address(self, address: str) -> AsyncTm1SessionBuilder:
        """The server's address, read by the dialect: ``http://tm1:8010``."""
        self._settings.pop("base_url", None)
        return self._set(address=address)

    def dialect(self, dialect: Tm1Dialect) -> AsyncTm1SessionBuilder:
        return self._set(dialect=dialect)

    def basic_auth(self, user: str, password: str | None) -> AsyncTm1SessionBuilder:
        return self._set(authenticator=basic(user, password))

    def authenticator(self, authenticator: Authenticator) -> AsyncTm1SessionBuilder:
        return self._set(authenticator=authenticator)

    def impersonate(self, user: str) -> AsyncTm1SessionBuilder:
        """Logs in as the service account but acts as ``user`` (``TM1-Impersonate``)."""
        return self._set(impersonate=user)

    def without_impersonation(self) -> AsyncTm1SessionBuilder:
        self._settings.pop("impersonate", None)
        return self

    def session_context(self, session_context: str) -> AsyncTm1SessionBuilder:
        return self._set(session_context=session_context)

    def timeouts(self, timeouts: Tm1Timeouts) -> AsyncTm1SessionBuilder:
        return self._set(timeouts=timeouts)

    def error_classifier(self, classifier: Tm1ErrorClassifier) -> AsyncTm1SessionBuilder:
        return self._set(error_classifier=classifier)

    def http_client(self, client: httpx.AsyncClient) -> AsyncTm1SessionBuilder:
        """The HTTP client to send through (Java's ``webClient`` and ``connector``)."""
        return self._set(client=client)

    def verify(self, verify: bool | str) -> AsyncTm1SessionBuilder:
        """Whether to check the server's certificate, or a CA bundle to check it with."""
        return self._set(verify=verify)

    def check_capabilities(self, check: bool) -> AsyncTm1SessionBuilder:
        return self._set(check_capabilities=check)

    def build(self) -> AsyncTm1Session:
        return AsyncTm1Session(**self._settings)
