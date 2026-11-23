# SPDX-License-Identifier: Apache-2.0

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
import time
from collections.abc import AsyncIterator, Callable
from datetime import timedelta
from typing import TYPE_CHECKING, Any

import httpx

from .. import _protocol as p
from .._compat import AsyncIO as runtime
from .._compat import Expired
from .._views import CAPABILITIES, Queue, as_call, capability_of, poll_interval, settle
from ..auth import Authenticator, basic
from ..errors import (
    ODataError,
    Tm1ApiError,
    Tm1ErrorClassifier,
    Tm1ErrorKind,
    Tm1ProtocolError,
    Tm1SessionClosedError,
    Tm1TransportError,
    api_error,
)
from ..identity import check_impersonated
from ..odata import ODataPath, ODataQuery
from ..request import Tm1Request
from ..timeouts import Tm1Timeouts
from ..version import Tm1Capabilities, Tm1Dialect, Tm1Version

if TYPE_CHECKING:
    # For the checker only (pydantic brings it): a builder's methods return the builder they're called on
    from typing_extensions import Self

__all__ = ["AsyncTm1Session", "AsyncTm1SessionBatch", "AsyncTm1SessionBuilder"]

log = logging.getLogger("skipcheck_tm1")


_IDEMPOTENT = ("GET", "PUT", "DELETE", "HEAD")


def _check_session_context(session_context: str | None) -> str | None:
    if session_context is not None and len(session_context) > 250:
        raise ValueError(f"TM1 keeps at most 250 characters of session context, got {len(session_context)}")
    return session_context


def _refusal(error: BaseException) -> bool:
    """A login TM1 refused (wrong credentials, an unknown or disabled user, impersonation not
    allowed), as opposed to one that failed for another reason, such as TM1 being unreachable."""
    return isinstance(error, Tm1ApiError) and error.status in (400, 401, 403)


def _transport(error: BaseException, method: str, url: str) -> Tm1TransportError:
    """A failure to send or to get a whole answer, by phase: before sending (couldn't connect,
    TLS, no connection free) or after (a timeout, a dropped connection)."""
    phase = (
        Tm1TransportError.Phase.CONNECT
        if isinstance(error, (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout, httpx.UnsupportedProtocol))
        else Tm1TransportError.Phase.RESPONSE
    )
    return Tm1TransportError(phase, method.upper() in _IDEMPOTENT, method, url, error)


class _AsyncConnection:
    """The login, cookie and HTTP connections every view of a session shares, as the Java
    client's ``Connection``.

    * One login at a time, shared: concurrent first requests wait for it.
    * A 401 on a request means the session expired or was killed: it logs in again, once for
      every request that saw the same expiry, sending the cookie TM1 set with the 401 (TM1
      opened a session along with it, so that one is logged in rather than another opened),
      and the request is replayed once, POSTs included (TM1 refuses a request on a dead session
      before running it). A 401 on the replay is final.
    * A login TM1 refused (400, 401, 403) is remembered for ``timeouts.refused_login``, so
      requests meanwhile fail at once with the same error: each login attempt leaves a session
      behind on TM1, and retrying a refusal at once would pile them up. Any other failure is
      forgotten, so the next request tries again.
    * After :meth:`close`, it sends nothing more.
    """

    def __init__(
        self,
        base_url: str,
        authenticator: Authenticator,
        session_context: str | None,
        impersonate: str | None,
        timeouts: Tm1Timeouts,
        classifier: Tm1ErrorClassifier,
        http: httpx.AsyncClient,
        owns_http: bool,
        check_capabilities: bool,
        clock: Callable[[], float],
    ):
        self.base_url = base_url if base_url.endswith("/") else base_url + "/"
        self.authenticator = authenticator
        self.session_context = session_context
        self.impersonate = impersonate
        self.timeouts = timeouts
        self.classifier = classifier
        self.http = http
        self.owns_http = owns_http
        self.check_capabilities = check_capabilities
        self.clock = clock
        self.cookie: str | None = None
        self.session_id: int | None = None
        self.generation = 0
        self.refused: tuple[BaseException, float] | None = None
        self.closed = False
        self.lock = runtime.Lock()
        self.version: Tm1Version | None = None
        self.version_for = -1

    def url(self, relative: str) -> str:
        return str(httpx.URL(self.base_url).join(relative))

    def check(self, method: str, url: str, reply: p.Reply) -> p.Reply:
        """The reply, or the error for a refusal, classified by this session's classifier."""
        if reply.status >= 400:
            raise api_error(method, url, reply.status, reply.content, self.classifier)
        return reply

    def _timeout(self, seconds: float | None) -> httpx.Timeout:
        return httpx.Timeout(self.timeouts.request if seconds is None else seconds, connect=self.timeouts.connect)

    def _require_open(self) -> None:
        if self.closed:
            raise Tm1SessionClosedError(self.base_url)

    def _refused_recently(self) -> bool:
        refused = self.refused
        return refused is not None and self.clock() - refused[1] < self.timeouts.refused_login

    async def logged_in(self, expired: str | None = None, seed: str | None = None) -> str:
        """The session's cookie, logging in first if there's none (or only ``expired``)."""
        self._require_open()
        cookie = self.cookie
        if cookie is not None and cookie != expired:
            return cookie
        if self._refused_recently():
            assert self.refused is not None
            raise self.refused[0]
        # Shielded: a caller giving up doesn't abandon a login TM1 may already have made, so
        # close() can still end it
        return await runtime.shield(self._login_locked(expired, seed))

    async def _login_locked(self, expired: str | None, seed: str | None) -> str:
        async with self.lock:
            self._require_open()
            if self.cookie is not None and self.cookie != expired:
                return self.cookie
            if self._refused_recently():
                assert self.refused is not None
                raise self.refused[0]
            self.refused = None
            reason = "initial" if expired is None else "relogin"
            try:
                cookie, session_id = await self._login(seed)
            except Exception as e:
                if _refusal(e) and self.timeouts.refused_login > 0:
                    self.refused = (e, self.clock())
                    log.warning(
                        "TM1 at %s refused the login as %s%s; not trying again for %ss",
                        self.base_url,
                        self.authenticator.describe(),
                        "" if self.impersonate is None else " impersonating a user",
                        self.timeouts.refused_login,
                    )
                raise
            self.cookie, self.session_id = cookie, session_id
            self.generation += 1
            log.info("Logged in to TM1 at %s as %s (session %s, %s)", self.base_url, self.authenticator.describe(), session_id, reason)
            return cookie

    async def _login(self, seed: str | None) -> tuple[str, int]:
        url = self.base_url + p.LOGIN
        # The authenticator's own failures (say, a passport it couldn't get) reach the caller unchanged.
        # It gets the login's timeout too: it may ask another server (Cognos, for a passport), and one
        # that never answers would otherwise hold every request, and close(), for ever.
        try:
            authorization = await runtime.within(self.timeouts.login, self.authenticator.authorization)
        except Expired:
            waited = TimeoutError(f"The authenticator {self.authenticator.describe()} supplied no credentials within {self.timeouts.login} s")
            # Nothing has been sent to TM1, so it's safe to try again
            raise Tm1TransportError(Tm1TransportError.Phase.CONNECT, True, "GET", url, waited) from None
        if not authorization:
            raise RuntimeError(f"The authenticator {self.authenticator.describe()} supplied no credentials")
        extra = {"Authorization": authorization}
        if self.impersonate is not None:
            # At login only: TM1 decides the identity then and ignores the header afterwards
            extra[p.IMPERSONATE] = self.impersonate
        if seed is not None:
            # Log in the session TM1 opened for a 401, rather than opening another
            extra["Cookie"] = f"{p.COOKIE}={seed}"
        try:
            # As UTF-8, which is how TM1 reads the user to impersonate and the session's label
            headers = httpx.Headers(p.headers(self.session_context, p.JSON, extra), encoding=p.HEADER_ENCODING)
            response = await self.http.get(url, headers=headers, timeout=self._timeout(self.timeouts.login))
        except httpx.HTTPError as e:
            raise _transport(e, "GET", url) from e
        self.http.cookies.clear()
        status = response.status_code
        if status == 401:
            # TM1 answers a disabled impersonated user exactly like wrong credentials
            why = (
                None
                if self.impersonate is None
                else ODataError("", f"The credentials were refused, or the user being impersonated ({self.impersonate}) is disabled")
            )
            raise Tm1ApiError.of_kind(401, Tm1ErrorKind.CREDENTIAL_REJECTED, why, "GET", url)
        if status >= 400:
            raise self._impersonation_error(api_error("GET", url, status, response.content, self.classifier), url)
        cookie = p.session_cookie(response.headers.get_list("set-cookie")) or seed
        if cookie is None:
            raise Tm1ProtocolError(f"TM1 accepted the login but set no {p.COOKIE} cookie", None, "GET", url)
        try:
            doc = response.json()
        except ValueError as e:
            raise Tm1ProtocolError("TM1 accepted the login but its response isn't JSON", None, "GET", url) from e
        session_id = doc.get("ID") if isinstance(doc, dict) else None
        if isinstance(session_id, str) and session_id.isdigit():
            session_id = int(session_id)
        if not isinstance(session_id, int) or isinstance(session_id, bool):
            raise Tm1ProtocolError("TM1 accepted the login but didn't return the session ID", None, "GET", url)
        return cookie, session_id

    def _impersonation_error(self, error: Tm1ApiError, url: str) -> Tm1ApiError:
        """A login that impersonates and gets a 400 or 403 was refused for the impersonation:
        the user doesn't exist (400), or may not be impersonated (403), however TM1 words it."""
        if self.impersonate is None:
            return error
        if error.status == 400 and error.kind is not Tm1ErrorKind.UNKNOWN_USER:
            return Tm1ApiError.of_kind(400, Tm1ErrorKind.UNKNOWN_USER, error.error, "GET", url)
        if error.status == 403 and error.kind is not Tm1ErrorKind.IMPERSONATION_REFUSED:
            return Tm1ApiError.of_kind(403, Tm1ErrorKind.IMPERSONATION_REFUSED, error.error, "GET", url)
        return error

    async def _send_once(
        self,
        method: str,
        url: str,
        content: bytes | None,
        headers: dict[str, str],
        accept: str,
        timeout: float | None,
        cookie: str,
    ) -> httpx.Response:
        try:
            response = await self.http.request(
                method,
                url,
                content=content,
                headers=p.headers(None, accept, {**headers, "Cookie": f"{p.COOKIE}={cookie}"}),
                timeout=self._timeout(timeout),
            )
        except httpx.HTTPError as e:
            raise _transport(e, method, url) from e
        # The session cookie is this session's to send, not the HTTP client's jar
        self.http.cookies.clear()
        return response

    async def send(
        self,
        method: str,
        url: str,
        content: bytes | None,
        headers: dict[str, str],
        accept: str,
        timeout: float | None = None,
    ) -> p.Reply:
        """Sends a request on the logged-in session, logging in again and replaying it once on a 401."""
        cookie = await self.logged_in()
        response = await self._send_once(method, url, content, headers, accept, timeout, cookie)
        if response.status_code == 401:
            seed = p.session_cookie(response.headers.get_list("set-cookie"))
            log.info("TM1 session at %s expired or was closed on the server; logging in again", self.base_url)
            cookie = await self.logged_in(expired=cookie, seed=seed)
            response = await self._send_once(method, url, content, headers, accept, timeout, cookie)
            if response.status_code == 401:
                raise Tm1ApiError.of_kind(401, Tm1ErrorKind.UNAUTHENTICATED, None, method, url)
        return p.Reply(response.status_code, {k.lower(): v for k, v in response.headers.items()}, response.content)

    async def product_version(self) -> Tm1Version:
        """The server's version, read from ``Server`` inside the session (an anonymous read
        would open a session of its own), once per login: a server that restarted, perhaps
        upgraded, makes the session log in again."""
        if self.version is not None and self.version_for == self.generation:
            return self.version
        url = self.base_url + "Server?$select=ProductVersion"
        doc = self.check("GET", url, await self.send("GET", url, None, {}, p.JSON)).json()
        text = doc.get("ProductVersion") if isinstance(doc, dict) else None
        if not isinstance(text, str):
            raise Tm1ProtocolError("TM1 didn't report its ProductVersion", None, "GET", url)
        self.version = Tm1Version.parse(text)
        self.version_for = self.generation
        return self.version

    async def capabilities(self) -> Tm1Capabilities:
        """What the server's ``$metadata`` defines: read once per server and version, for
        every session in the process (a server that's upgraded reports a new version). A
        failed read isn't kept."""
        version = await self.product_version()
        key = (self.base_url, str(version))
        cached = CAPABILITIES.get(key)
        if cached is None:
            url = self.base_url + p.METADATA
            reply = self.check("GET", url, await self.send("GET", url, None, {}, "application/xml"))
            cached = Tm1Capabilities.parse(reply.content, version)
            CAPABILITIES[key] = cached
        return cached

    async def close(self) -> None:
        """Waits for a login in progress, then sends ``tm1.Close``; nothing if no login ever
        succeeded. Afterwards the connection refuses requests. A 401 means the session was
        already gone, which is what close wants."""
        if self.closed:
            return
        self.closed = True
        try:
            async with self.lock:
                cookie, self.cookie = self.cookie, None
            if cookie is None:
                return
            url = self.base_url + p.CLOSE
            headers = p.headers(
                None,
                p.JSON,
                {
                    "Cookie": f"{p.COOKIE}={cookie}",
                    "Content-Type": "application/json",
                    # TM1 sometimes drops the connection that carried a close: don't reuse it
                    "Connection": "close",
                },
            )
            try:
                response = await self.http.post(url, content=b"{}", headers=headers, timeout=self._timeout(self.timeouts.close))
            except httpx.HTTPError as e:
                log.warning("Couldn't close TM1 session %s at %s: %s", self.session_id, self.base_url, e)
                raise _transport(e, "POST", url) from e
            if response.status_code >= 400 and response.status_code != 401:
                raise api_error("POST", url, response.status_code, response.content, self.classifier)
            log.info("Closed TM1 session %s at %s", self.session_id, self.base_url)
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
        session_context: str | None = None,
        sandbox: str | None = None,
        timeouts: Tm1Timeouts | None = None,
        error_classifier: Tm1ErrorClassifier | None = None,
        check_capabilities: bool = True,
        verify: bool | str = True,
        client: httpx.AsyncClient | None = None,
        clock: Callable[[], float] | None = None,
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
        _check_session_context(session_context)
        timeouts = timeouts or Tm1Timeouts.DEFAULTS
        http = client or runtime.HttpClient(verify=verify, timeout=httpx.Timeout(timeouts.request, connect=timeouts.connect))
        self._connection = _AsyncConnection(
            base_url,
            authenticator,
            session_context,
            None if impersonate is None else check_impersonated(impersonate),
            timeouts,
            error_classifier or Tm1ErrorClassifier.defaults(),
            http,
            client is None,
            check_capabilities,
            clock or time.monotonic,
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
        """A view of this session whose requests run in sandbox ``name``: each gets TM1's
        ``!sandbox`` query option, unless the request sets its own. The view shares this session's
        login, cookie and connections, so it costs nothing; closing either closes both. Derived
        rather than set on the session, so one caller's sandbox can't leak into another caller's
        requests. TM1 evaluates ``NON EMPTY`` and filters in the request's sandbox, so every request
        of an operation needs it, cellset reads and deletes included; a service given this view gets
        that for free. A ValueError if ``name`` is blank."""
        if not name or not name.strip():
            raise ValueError("sandbox name is blank")
        return self._view(p.Context(name, self._context.change_set), self._async_every, self._queue)

    def without_sandbox(self) -> AsyncTm1Session:
        """A view of this session whose requests run outside any sandbox, in base data."""
        return self._view(p.Context(None, self._context.change_set), self._async_every, self._queue)

    def with_change_set(self, change_set: str) -> AsyncTm1Session:
        """A view of this session whose writes are recorded in change set ``change_set`` (its ID,
        from ``BeginChangeSet``): each request gets TM1's ``!ChangeSet`` query option, unless it
        sets its own, so ``UndoChangeSet`` can revert them together. Like :meth:`with_sandbox`, the
        view shares this session's login and connections; it keeps the sandbox, if any. A ValueError
        if the ID is blank."""
        if not change_set or not change_set.strip():
            raise ValueError("change set ID is blank")
        return self._view(p.Context(self._context.sandbox, change_set), self._async_every, self._queue)

    def without_change_set(self) -> AsyncTm1Session:
        """A view of this session whose writes go into no change set."""
        return self._view(p.Context(self._context.sandbox, None), self._async_every, self._queue)

    def with_async(self, every: float | timedelta = 1.0) -> AsyncTm1Session:
        """A view of this session whose requests TM1 runs asynchronously (OData's ``Prefer:
        respond-async``): each returns at once with a monitor, which the client polls every
        ``every`` seconds until the result is there, so a long process or chore never holds a
        request open for its whole run. Methods return what they always do. Cancelling the call
        before the result deletes the monitor, which stops the work on TM1: a process run ends as
        ``TM1UserException: Cancel``. Raw byte requests (document content) aren't sent async. TM1
        (11.8) sends no ``Retry-After``, so ``every`` is the only pace. A ValueError if ``every``
        isn't positive."""
        return self._view(self._context, poll_interval(every), self._queue)

    def without_async(self) -> AsyncTm1Session:
        """A view of this session whose requests run synchronously, as usual."""
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
        """The sandbox this view's requests run in, or None for base data."""
        return self._context.sandbox

    @property
    def change_set(self) -> str | None:
        """The ID of the change set this view's writes are recorded in, or None."""
        return self._context.change_set

    @property
    def base_url(self) -> str:
        """The service root, such as ``http://localhost:8010/api/v1/``, always ending in ``/``."""
        return self._connection.base_url

    @property
    def logged_in(self) -> bool:
        """Whether it holds a login now (it logs in on the first request)."""
        return self._connection.cookie is not None

    @property
    def is_closed(self) -> bool:
        """Whether :meth:`close` was called: it sends nothing more."""
        return self._connection.closed

    @property
    def impersonating(self) -> str | None:
        """The user this session impersonates, if any."""
        return self._connection.impersonate

    @property
    def timeouts(self) -> Tm1Timeouts:
        """The session's timeouts, by kind of work."""
        return self._connection.timeouts

    @property
    def error_classifier(self) -> Tm1ErrorClassifier:
        """How the session classifies error responses."""
        return self._connection.classifier

    async def __aenter__(self) -> AsyncTm1Session:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def connect(self) -> AsyncTm1Session:
        """Logs in now, rather than on the first request, and returns this session."""
        await self._connection.logged_in()
        return self

    async def login(self) -> None:
        """Logs in now (see :meth:`connect`)."""
        await self._connection.logged_in()

    async def session_id(self) -> int:
        """The TM1 session's ID (``ActiveSession``'s), logging in first if needed."""
        await self._connection.logged_in()
        assert self._connection.session_id is not None
        return self._connection.session_id

    async def close(self) -> None:
        """Ends the TM1 session (``ActiveSession/tm1.Close``), for every view: waits for a
        login in progress, and sends nothing if none ever succeeded. Afterwards the session
        refuses requests with ``Tm1SessionClosedError``."""
        await self._connection.close()

    def __repr__(self) -> str:
        sandbox = "" if self._context.sandbox is None else f", sandbox={self._context.sandbox}"
        change_set = "" if self._context.change_set is None else f", changeSet={self._context.change_set}"
        return f"{type(self).__name__}[{self._connection.base_url}{sandbox}{change_set}]"

    async def version(self) -> Tm1Version:
        """The server's product version, such as ``11.8.03500.6``."""
        return await self._connection.product_version()

    async def capabilities(self) -> Tm1Capabilities:
        """What the server's own ``$metadata`` defines, and its versions."""
        return await self._connection.capabilities()

    @property
    def service_root(self) -> str:
        """The service root, such as ``http://localhost:8010/api/v1/``, always ending in ``/``:
        Java's name for :attr:`base_url`."""
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
        if self._queue is not None:
            # What the call requires is checked when the batch runs: the check may read the
            # server's $metadata, and queueing sends nothing
            return self._queue.add(call, self._context)
        await self._check(call)
        url = self._connection.url(p.target(call, self._context))
        content, headers = call.body()
        if self._async_every is not None and not call.raw:
            reply = await self._send_async(call.method, url, content, headers, call.accept)
        else:
            reply = await self._connection.send(call.method, url, content, headers, call.accept, call.timeout)
        return call.read(self._connection.check(call.method, url, reply))

    def stream(self, request: Tm1Request[Any] | p.Call) -> AsyncIterator[Any]:
        """The items of a collection, fetching pages as they're needed (Java's ``stream``). A page
        is asked for only once the items before it have been taken, and each response is read whole
        and closed before its items are given, so a stream that is stopped early, or fails, leaves
        no response open."""
        if isinstance(request, Tm1Request) and request.response is not Tm1Request.Response.COLLECTION:
            raise ValueError(f"Only collection requests are streamed: use execute() for {request}")
        return self.iterate(as_call(request))

    async def collect(self, request: Tm1Request[Any] | p.Call) -> Any:
        """Every item of a collection, as a list, following TM1's next links. In a batch, a
        ``Deferred`` of that list: the first page comes in the batch's answer, and the pages after
        it, if TM1 sent a next link, are fetched with ordinary requests on the same session when
        the batch runs. TM1 (11.8) doesn't page, so that is untested against a server."""
        call = as_call(request)
        if self._queue is not None:
            return self._queue.add(call, self._context, paged=True)
        return [item async for item in self.iterate(call)]

    async def iterate(self, call: p.Call) -> AsyncIterator[Any]:
        """The items of a collection, page by page, following TM1's next links: what :meth:`stream`
        iterates, for a call rather than a request. On an async view, the first page may come as
        an async result, and each page after it is asked for the same way. Can't join a batch."""
        if self._queue is not None:
            raise RuntimeError("A collection can't be iterated in a batch: use collect(), whose result is every item once the batch has run")
        page = await self.execute(call)
        while True:
            for item in page.items:
                yield item
            if page.next_link is None:
                return
            page = await self.execute(p.Call("GET", page.next_link, read=call.read, contextual=False))

    async def _send_async(self, method: str, url: str, content: bytes | None, headers: dict[str, str], accept: str) -> p.Reply:
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
                polled = connection.check("GET", monitor, await connection.send("GET", monitor, None, {}, "*/*"))
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
        """Sends one request the services have no method for, and returns the raw reply: its status,
        headers and content. ``path`` is relative to the service root; ``json_body`` is written as
        JSON, ``content`` sent as it is."""
        return await self.execute(p.Call(method, str(path), query, json_body, content, headers or {}, accept, read=lambda r: r))

    async def get_json(self, path: ODataPath | str, query: ODataQuery | None = None) -> Any:
        """``GET`` of ``path``, with its answer as parsed JSON."""
        return (await self.request("GET", path, query=query)).json()


class AsyncTm1SessionBatch:
    """A JSON ``$batch``: calls through :attr:`client` (or :attr:`session`) are queued, return
    :class:`~skipcheck_tm1._protocol.Deferred` results, and :meth:`run` sends them together; as a
    context manager it runs on leaving the block::

        async with tm1.batch() as batch:
            a = await batch.client.cubes.get("A")
        print(a.result().name)

    TM1 (11.8) runs a batch's requests in order (a later one sees an earlier one's writes), and
    carries on past failed ones: each result holds its own value or error. A collection's result
    is every item: if TM1 answers with a next link, :meth:`run` fetches the pages after the first
    with ordinary requests, outside the batch. Multipart batches,
    ``atomicityGroup`` and ``dependsOn`` are refused, so none are used; 1000 requests in one
    batch work, and :meth:`run` splits a bigger queue.

    A body that can't be written as JSON fails its call with a ``ValueError`` when the call is
    made, and the call never joins the batch; the others still run. The body is written then, so a
    change made to the object afterwards isn't sent. If TM1 answers a ``$batch`` with no body, or
    with something that isn't a batch response, every call of that request fails with
    :class:`~skipcheck_tm1.errors.Tm1ProtocolError`.

    A request that requires something the server doesn't define (``Tm1Request.requires``, or an
    operation of the generated API) is checked when the batch runs, not when the call is made, so
    that making a call sends nothing: its call fails with
    :class:`~skipcheck_tm1.errors.Tm1UnsupportedOperationError` and it isn't sent, and the others
    still are. If the server's capabilities can't be read at all, each call that needs them fails
    with that error.
    """

    DEFAULT_PER_REQUEST = 500

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
        """Sends the queued requests, at most ``per_request`` to each ``$batch``; each
        request's result (or error) is in its ``Deferred``. Runs once."""
        if per_request < 1:
            raise ValueError(f"A batch needs room for at least one request: {per_request}")
        sendable = []
        for entry in self._queue.take():
            if await self._supported(entry[0], entry[2]):
                sendable.append(entry)
        paged = {id(deferred) for deferred in self._queue.paged}
        for start in range(0, len(sendable), per_request):
            chunk = sendable[start : start + per_request]
            body = p.batch_body([(call, context) for call, context, _ in chunk])
            try:
                reply = await self._sender.execute(p.Call("POST", "$batch", json_body=body, read=lambda r: r, contextual=False))
                settle(chunk, reply, self._sender._connection.url, self._sender._connection.classifier)
            except Exception as e:  # noqa: BLE001
                # Every call is told, whatever went wrong: none is left without a result. That includes
                # a response with no body, or one that isn't a batch response.
                for _, _, deferred in chunk:
                    deferred._fail(e)
            for call, _, deferred in chunk:
                if id(deferred) in paged:
                    await self._rest(call, deferred)

    async def _rest(self, call: p.Call, deferred: p.Deferred[Any]) -> None:
        """Makes a collection's result its every item. Its first page came in the batch; a next
        link is followed with ordinary requests, as ``stream()`` does: the rest of the collection
        isn't part of the batch. A page that fails fails the call."""
        if deferred._error is not None:
            return
        page = deferred._value
        items = list(page.items)
        try:
            while page.next_link is not None:
                page = await self._sender.execute(p.Call("GET", page.next_link, read=call.read, contextual=False))
                items.extend(page.items)
        except Exception as e:  # noqa: BLE001
            deferred._fail(e)
            return
        deferred._value = items

    async def _supported(self, call: p.Call, deferred: p.Deferred[Any]) -> bool:
        """Whether the server defines what the call requires. If it doesn't (or that can't be found
        out), the call fails and isn't sent, as outside a batch."""
        try:
            await self._sender._check(call)
        except Exception as e:  # noqa: BLE001
            deferred._fail(e)
            return False
        return True


class AsyncTm1SessionBuilder:
    """A session's settings one by one, as the Java client's ``Tm1Session.Builder``; each
    method returns the builder, and :meth:`build` makes the session. The pool copies one per
    identity."""

    def __init__(self, settings: dict[str, Any] | None = None):
        self._settings: dict[str, Any] = dict(settings or {})

    def copy(self) -> Self:
        """A copy of these settings, for the pool to build one session per identity."""
        return type(self)(self._settings)

    def settings(self) -> dict[str, Any]:
        """The settings so far, by name, as a new dict."""
        return dict(self._settings)

    def _set(self, **values: Any) -> Self:
        self._settings.update(values)
        return self

    def base_url(self, base_url: str) -> Self:
        """The REST API's root, such as ``http://tm1:8010/api/v1/``."""
        self._settings.pop("address", None)
        return self._set(base_url=base_url)

    def address(self, address: str) -> Self:
        """The server's address, read by the dialect: ``http://tm1:8010``."""
        self._settings.pop("base_url", None)
        return self._set(address=address)

    def dialect(self, dialect: Tm1Dialect) -> Self:
        """The kind of deployment, for :meth:`address`. :attr:`Tm1Dialect.V11` unless set."""
        return self._set(dialect=dialect)

    def basic_auth(self, user: str, password: str | None) -> Self:
        """Basic authentication, for TM1 security modes 1 and 2: shorthand for
        ``authenticator(auth.basic(user, password))``. A password of None counts as empty."""
        return self._set(authenticator=basic(user, password))

    def authenticator(self, authenticator: Authenticator) -> Self:
        """How to log in: TM1's own security, CAM, or credentials of your own. Asked for credentials
        on every login, and given the login timeout to supply them (see :class:`Tm1Timeouts`)."""
        return self._set(authenticator=authenticator)

    def impersonate(self, user: str) -> Self:
        """Logs in as the service account but acts as ``user`` (``TM1-Impersonate``, sent at
        login only). A name with characters outside ASCII goes out as UTF-8, which is how TM1
        (11.8) reads the header. Refuses a blank name, which TM1 would take as no impersonation
        at all, and one with a control character."""
        return self._set(impersonate=check_impersonated(user))

    def without_impersonation(self) -> Self:
        """Logs in as the credentials' own user: no impersonation."""
        self._settings.pop("impersonate", None)
        return self

    def session_context(self, session_context: str) -> Self:
        """A label for the TM1 session, shown as its ``Context`` in ``Sessions`` and
        ``Threads``, such as the application's name; sent with the login. It's a setting of the
        session, not of a view: TM1 keeps one label per session. At most 250 characters; sent as
        UTF-8, as TM1 reads it."""
        return self._set(session_context=_check_session_context(session_context))

    def timeouts(self, timeouts: Tm1Timeouts) -> Self:
        """Timeouts by kind of work; :attr:`Tm1Timeouts.DEFAULTS` unless set."""
        return self._set(timeouts=timeouts)

    def error_classifier(self, classifier: Tm1ErrorClassifier) -> Self:
        """How error responses are classified; :meth:`Tm1ErrorClassifier.defaults` unless set."""
        return self._set(error_classifier=classifier)

    def http_client(self, client: httpx.AsyncClient) -> Self:
        """The HTTP client to send through (Java's ``webClient`` and ``connector``)."""
        return self._set(client=client)

    def verify(self, verify: bool | str) -> Self:
        """Whether to check the server's certificate, or a CA bundle to check it with."""
        return self._set(verify=verify)

    def check_capabilities(self, check: bool) -> Self:
        """Whether a request is checked against the server's ``$metadata`` before it's sent, and
        refused with a :class:`~skipcheck_tm1.errors.Tm1UnsupportedOperationError` if the server
        doesn't define what it needs. On unless set."""
        return self._set(check_capabilities=check)

    def clock(self, clock: Callable[[], float]) -> Self:
        """The clock refused logins are timed by, in seconds (Java's ``nanoTime``): tests pass
        one they move."""
        return self._set(clock=clock)

    def base_url_or_unset(self) -> str:
        """The server, for messages: the base URL or address set, or a placeholder."""
        return str(self._settings.get("base_url") or self._settings.get("address") or "(no server set)")

    def build(self) -> AsyncTm1Session:
        """The session. Nothing is sent to TM1 until the first request."""
        return AsyncTm1Session(**self._settings)
