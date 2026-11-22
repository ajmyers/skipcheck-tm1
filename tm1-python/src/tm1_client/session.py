"""The session: one logged-in connection to a TM1 server, which every resource sends through.

Like the Java client's ``Tm1Session``: it logs in lazily with ``GET ActiveSession`` and the
authenticator's header, then sends only the ``TM1SessionId`` cookie; when TM1 answers 401
(the session expired or was closed) it logs in again once and resends. ``close()`` ends the
session on the server (``ActiveSession/tm1.Close``).
"""

from __future__ import annotations

import json
import logging
import threading
from typing import Any, Iterator, Mapping

import httpx

from .auth import Authenticator
from .errors import Tm1ProtocolError, Tm1TransportError, api_error
from .odata import ODataPath, ODataQuery, query_value

__all__ = ["Tm1Session"]

log = logging.getLogger("tm1_client")

COOKIE = "TM1SessionId"
_LOGIN = "ActiveSession?$select=ID"
_CLOSE = "ActiveSession/tm1.Close"
_JSON = "application/json;odata.metadata=minimal"


class Tm1Session:
    """A session with the TM1 REST API at ``base_url`` (``https://host:port/api/v1/``).

    Use it as a context manager, or call :meth:`close`::

        with Tm1Session("https://tm1:8010/api/v1", auth.basic("admin", "apple")) as session:
            client = Tm1Client(session)

    :param session_context: sent as ``TM1-SessionContext``, which TM1 shows for the session
    :param sandbox: run every request in this sandbox (``!sandbox``)
    :param client: an ``httpx.Client`` to send through (for TLS, proxies, transports, tests)
    """

    def __init__(
        self,
        base_url: str,
        authenticator: Authenticator,
        *,
        session_context: str | None = "tm1-client (Python)",
        sandbox: str | None = None,
        verify: bool | str = True,
        timeout: float | httpx.Timeout = 60.0,
        client: httpx.Client | None = None,
    ):
        self.base_url = base_url if base_url.endswith("/") else base_url + "/"
        self.authenticator = authenticator
        self.session_context = session_context
        self.sandbox = sandbox
        self._http = client or httpx.Client(verify=verify, timeout=timeout)
        self._owns_http = client is None
        self._cookie: str | None = None
        self._lock = threading.Lock()

    # Lifecycle

    def __enter__(self) -> Tm1Session:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    @property
    def logged_in(self) -> bool:
        return self._cookie is not None

    @property
    def session_id(self) -> str | None:
        """The ``TM1SessionId`` cookie, once logged in."""
        return self._cookie

    def login(self) -> None:
        """Logs in now, rather than with the first request."""
        with self._lock:
            self._login()

    def close(self) -> None:
        """Ends the session on the server, if there's one, and releases the connection."""
        cookie, self._cookie = self._cookie, None
        try:
            if cookie is not None:
                try:
                    self._http.post(self.base_url + _CLOSE, headers=self._headers(extra={"Cookie": f"{COOKIE}={cookie}"}))
                except httpx.HTTPError as e:
                    log.debug("Closing the TM1 session failed: %s", e)
        finally:
            if self._owns_http:
                self._http.close()

    # Requests

    def request(
        self,
        method: str,
        path: ODataPath | str,
        *,
        query: ODataQuery | None = None,
        json_body: Any = None,
        content: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        accept: str = _JSON,
    ) -> httpx.Response:
        """Sends a request and returns TM1's response, raising a :class:`Tm1ApiError` for a refusal."""
        url = self._url(str(path), query)
        return self._send(method, url, json_body=json_body, content=content, headers=headers, accept=accept)

    def get_json(self, path: ODataPath | str, query: ODataQuery | None = None) -> Any:
        return _json(self.request("GET", path, query=query))

    def iterate(self, path: ODataPath | str, query: ODataQuery | None = None) -> Iterator[Any]:
        """The items of a collection, following ``@odata.nextLink`` across pages."""
        url: str | None = self._url(str(path), query)
        while url is not None:
            doc = _json(self._send("GET", url))
            if not isinstance(doc, dict) or "value" not in doc:
                raise Tm1ProtocolError(f"GET {url}: expected a collection, got {str(doc)[:200]}")
            yield from doc["value"]
            link = doc.get("@odata.nextLink")
            url = None if link is None else self._resolve(link)

    # Internals

    def _url(self, path: str, query: ODataQuery | None) -> str:
        params = [] if query is None else [query.query_string()]
        if self.sandbox is not None:
            params.append("!sandbox=" + query_value(self.sandbox))
        params = [p for p in params if p]
        sep = "&" if "?" in path else "?"
        return self.base_url + path + (sep + "&".join(params) if params else "")

    def _resolve(self, link: str) -> str:
        return str(httpx.URL(self.base_url).join(link))

    def _headers(self, accept: str = _JSON, extra: Mapping[str, str] | None = None) -> dict[str, str]:
        headers = {"OData-Version": "4.0", "OData-MaxVersion": "4.0", "Accept": accept}
        if self.session_context:
            headers["TM1-SessionContext"] = self.session_context
        if extra:
            headers.update(extra)
        return headers

    def _login(self, expired: str | None = None) -> str:
        """Logs in, unless another thread already replaced ``expired``; returns the cookie."""
        if self._cookie is not None and self._cookie != expired:
            return self._cookie
        url = self.base_url + _LOGIN
        headers = self._headers(extra={"Authorization": self.authenticator.authorization()})
        try:
            response = self._http.get(url, headers=headers)
        except httpx.HTTPError as e:
            raise Tm1TransportError(f"Logging in to {self.base_url} as {self.authenticator.describe()} failed: {e}") from e
        if response.status_code >= 400:
            raise api_error("GET", url, response.status_code, response.content)
        cookie = _session_cookie(response)
        self._http.cookies.clear()
        if not cookie:
            raise Tm1ProtocolError(f"TM1 at {self.base_url} accepted the login but set no {COOKIE} cookie")
        log.debug("Logged in to %s as %s", self.base_url, self.authenticator.describe())
        self._cookie = cookie
        return cookie

    def _send(
        self,
        method: str,
        url: str,
        *,
        json_body: Any = None,
        content: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        accept: str = _JSON,
    ) -> httpx.Response:
        body: bytes | None = content
        extra = dict(headers or {})
        if json_body is not None:
            body = json.dumps(json_body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            extra.setdefault("Content-Type", "application/json; charset=utf-8")
        with self._lock:
            cookie = self._login()
        for attempt in (1, 2):
            try:
                response = self._http.request(
                    method, url, content=body, headers=self._headers(accept, {**extra, "Cookie": f"{COOKIE}={cookie}"})
                )
            except httpx.HTTPError as e:
                raise Tm1TransportError(f"{method} {url} failed: {e}") from e
            # The session cookie is this session's to send, not the HTTP client's jar
            self._http.cookies.clear()
            if response.status_code == 401 and attempt == 1:
                log.debug("TM1 session expired; logging in again")
                with self._lock:
                    cookie = self._login(expired=cookie)
                continue
            if response.status_code >= 400:
                raise api_error(method, url, response.status_code, response.content)
            return response
        raise AssertionError("unreachable")


def _session_cookie(response: httpx.Response) -> str | None:
    """The ``TM1SessionId`` a response sets, unless it expires it (``Max-Age=0``)."""
    for header in response.headers.get_list("set-cookie"):
        parts = [p.strip() for p in header.split(";")]
        name, _, value = parts[0].partition("=")
        if name.strip() != COOKIE:
            continue
        if any(p.lower().replace(" ", "") == "max-age=0" for p in parts[1:]):
            continue
        return value.strip().strip('"')
    return None


def _json(response: httpx.Response) -> Any:
    if response.status_code == 204 or not response.content:
        return None
    try:
        return response.json()
    except ValueError as e:
        raise Tm1ProtocolError(f"{response.request.method} {response.request.url}: not JSON: {e}") from e
