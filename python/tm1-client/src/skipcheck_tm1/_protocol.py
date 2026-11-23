# SPDX-License-Identifier: Apache-2.0

"""What talking to TM1 means, without any I/O: requests as :class:`Call` values, answers as
:class:`Reply` values, and the formats around them (URLs with TM1's query options, the session
cookie, JSON ``$batch``, async results, ``$metadata`` capabilities).

The sync session (:mod:`skipcheck_tm1.session`) and the asyncio one (:mod:`skipcheck_tm1.aio`) are
thin loops over this, so they behave the same.
"""

from __future__ import annotations

import gzip
import json
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field, replace
from typing import Any, Generic, TypeVar

from .errors import Tm1ProtocolError, api_error
from .odata import ODataQuery, query_value

COOKIE = "TM1SessionId"
LOGIN = "ActiveSession?$select=ID"
CLOSE = "ActiveSession/tm1.Close"
METADATA = "$metadata"
JSON = "application/json"
TEXT = "text/plain, application/json"
IMPERSONATE = "TM1-Impersonate"
SESSION_CONTEXT = "TM1-SessionContext"

T = TypeVar("T")


@dataclass(frozen=True)
class Reply:
    """An answer: a status, headers (lower-case names) and a body."""

    status: int
    headers: Mapping[str, str]
    content: bytes

    def json(self) -> Any:
        if self.status == 204 or not self.content:
            return None
        try:
            try:
                return json.loads(self.content)
            except UnicodeDecodeError:
                # TM1 (11.8) writes some values as the bytes it holds: the transaction log gives a
                # password's hash that isn't UTF-8. As the Java client's Utf8Repair, such bytes read
                # as U+FFFD rather than failing the response.
                return json.loads(self.content.decode("utf-8", "replace"))
        except ValueError as e:
            raise Tm1ProtocolError(f"TM1 answered {self.status} with something that isn't JSON: {e}") from e

    @property
    def text(self) -> str:
        return self.content.decode("utf-8", "replace")


def _ignore(reply: Reply) -> None:
    return None


@dataclass(frozen=True)
class Call:
    """A request, and how to read its answer.

    :param path: relative to the service root, encoded, maybe with a query string
    :param read: turns the answer into the call's result (an answer of 400 or more has
        already been raised as a :class:`~skipcheck_tm1.errors.Tm1ApiError`)
    :param operation: the endpoint ID of the operation it calls, for the capability check
    :param raw: whether the body or the answer is bytes rather than JSON, which can't go in a
        JSON ``$batch`` and isn't sent async
    """

    method: str
    path: str
    query: ODataQuery | None = None
    json_body: Any = None
    content: bytes | None = None
    headers: Mapping[str, str] = field(default_factory=dict)
    accept: str = JSON
    read: Callable[[Reply], Any] = _ignore
    operation: str | None = None
    raw: bool = False
    contextual: bool = True
    """Whether the view's ``!sandbox`` and ``!ChangeSet`` are added: not to a link TM1 sent."""
    requires: Any = None
    """A :class:`~skipcheck_tm1.version.Tm1Capability` the server must have, checked first."""
    timeout: float | None = None
    """Seconds to wait for the answer, instead of the session's request timeout."""

    def body(self) -> tuple[bytes | None, dict[str, str]]:
        """The body to send, and the headers that go with it."""
        headers = dict(self.headers)
        if self.json_body is not None:
            headers.setdefault("Content-Type", "application/json")
            return json.dumps(self.json_body, separators=(",", ":"), ensure_ascii=False).encode("utf-8"), headers
        return self.content, headers

    def with_header(self, name: str, value: str) -> Call:
        return replace(self, headers={**self.headers, name: value})


@dataclass(frozen=True)
class Context:
    """What a session view adds to each request: TM1's ``!sandbox`` and ``!ChangeSet``."""

    sandbox: str | None = None
    change_set: str | None = None

    def options(self) -> list[tuple[str, str]]:
        out = []
        if self.sandbox is not None:
            out.append(("!sandbox", self.sandbox))
        if self.change_set is not None:
            out.append(("!ChangeSet", self.change_set))
        return out


def relative_url(path: str, query: ODataQuery | None, context: Context) -> str:
    """The URL relative to the service root: the path, the query's options, and the view's
    ``!sandbox`` and ``!ChangeSet`` unless the query sets its own."""
    own = {} if query is None else query.custom
    parts = [] if query is None else [query.to_query_string()]
    for name, value in context.options():
        if name not in own:
            parts.append(f"{name}={query_value(value)}")
    parts = [p for p in parts if p]
    if not parts:
        return path
    return path + ("&" if "?" in path else "?") + "&".join(parts)


HEADER_ENCODING = "utf-8"
"""How the text of a login's headers is encoded. TM1 (11.8) reads a user name in
``TM1-Impersonate`` and a label in ``TM1-SessionContext`` as UTF-8 and nothing else: a name sent
as ISO 8859-1 bytes, percent-encoded or as an RFC 2047 encoded word isn't found. httpx refuses
a header value outside ASCII unless its ``Headers`` are given the encoding."""


def headers(session_context: str | None, accept: str = JSON, extra: Mapping[str, str] | None = None) -> dict[str, str]:
    out = {"OData-Version": "4.0", "OData-MaxVersion": "4.0", "Accept": accept}
    if session_context:
        out[SESSION_CONTEXT] = session_context
    if extra:
        out.update(extra)
    return out


def session_cookie(set_cookies: Iterable[str]) -> str | None:
    """The ``TM1SessionId`` a response sets, unless it expires it (``Max-Age=0``)."""
    for header in set_cookies:
        parts = [p.strip() for p in header.split(";")]
        name, _, value = parts[0].partition("=")
        if name.strip() != COOKIE:
            continue
        if any(p.lower().replace(" ", "") == "max-age=0" for p in parts[1:]):
            continue
        return value.strip().strip('"')
    return None


def check(method: str, url: str, reply: Reply, classifier: Any = None) -> Reply:
    """The reply, or the :class:`~skipcheck_tm1.errors.Tm1ApiError` for a refusal."""
    if reply.status >= 400:
        raise api_error(method, url, reply.status, reply.content, classifier)
    return reply


# OData async (Prefer: respond-async)


def async_result(message: bytes) -> Reply:
    """The response an async monitor holds once done: an ``application/http`` message, whose
    body TM1 gzips when the request accepted gzip."""
    head, sep, body = message.partition(b"\r\n\r\n")
    lines = head.decode("iso-8859-1").split("\r\n")
    parts = lines[0].split(" ", 2)
    if len(parts) < 2 or not parts[0].startswith("HTTP/"):
        raise Tm1ProtocolError(f"TM1's async result isn't an HTTP message: {lines[0][:100]!r}")
    try:
        status = int(parts[1])
    except ValueError as e:
        raise Tm1ProtocolError(f"TM1's async result has no status: {lines[0][:100]!r}") from e
    found: dict[str, str] = {}
    for line in lines[1:]:
        name, _, value = line.partition(":")
        if name:
            found[name.strip().lower()] = value.strip()
    if not sep:
        body = b""
    if found.get("content-encoding", "").lower() == "gzip" and body:
        try:
            body = gzip.decompress(body)
        except OSError as e:
            raise Tm1ProtocolError("TM1's async result says gzip but isn't") from e
    return Reply(status, found, body)


# JSON $batch


def target(call: Call, context: Context) -> str:
    """A call's URL relative to the service root, with the view's options if it takes them."""
    return relative_url(call.path, call.query, context if call.contextual else Context())


def batch_body(calls: list[tuple[Call, Context]]) -> dict[str, Any]:
    requests = []
    for i, (call, context) in enumerate(calls, 1):
        if call.raw:
            raise ValueError(f"Bytes can't go in a JSON batch: {call.method} {call.path}")
        item: dict[str, Any] = {
            "id": str(i),
            "method": call.method,
            "url": target(call, context),
            # TM1 (11.8) gives a request of a batch the batch's own Accept unless it has one, and
            # refuses plain text ($count, $value) under the batch's application/json. It reads a
            # header's name as written, and only knows this one in lower case.
            "headers": {"accept": call.accept, **dict(call.headers)},
        }
        if call.json_body is not None:
            item["headers"]["content-type"] = "application/json"
            item["body"] = call.json_body
        requests.append(item)
    return {"requests": requests}


def batch_replies(doc: Any, count: int) -> list[Reply | None]:
    """The answers of a ``$batch`` response, in the order of its requests."""
    by_id: dict[str, Any] = {}
    for item in (doc or {}).get("responses", []):
        by_id[str(item.get("id"))] = item
    out: list[Reply | None] = []
    for i in range(1, count + 1):
        item = by_id.get(str(i))
        if item is None:
            out.append(None)
            continue
        body = item.get("body")
        content = b"" if body is None else (body.encode("utf-8") if isinstance(body, str) else json.dumps(body).encode("utf-8"))
        hdrs = {str(k).lower(): str(v) for k, v in (item.get("headers") or {}).items()}
        out.append(Reply(int(item.get("status", 0)), hdrs, content))
    return out


class Deferred(Generic[T]):
    """The result of a call queued in a batch, there once the batch has run."""

    __slots__ = ("_call", "_done", "_value", "_error")

    def __init__(self, call: Call):
        self._call = call
        self._done = False
        self._value: Any = None
        self._error: BaseException | None = None

    @property
    def done(self) -> bool:
        return self._done

    def result(self) -> T:
        """The result, or the error TM1 answered with; raises if the batch hasn't run."""
        if not self._done:
            raise RuntimeError(f"The batch hasn't run yet: {self._call.method} {self._call.path}")
        if self._error is not None:
            raise self._error
        return self._value

    def _settle(self, url: str, reply: Reply | None, classifier: Any = None) -> None:
        self._done = True
        try:
            if reply is None:
                raise Tm1ProtocolError("TM1's batch response has no answer for this request", None, self._call.method, url)
            self._value = self._call.read(check(self._call.method, url, reply, classifier))
        except BaseException as e:  # noqa: BLE001 (kept for result())
            self._error = e

    def _fail(self, error: BaseException) -> None:
        self._done = True
        self._error = error

    def __repr__(self) -> str:
        state = "pending" if not self._done else ("failed" if self._error else "done")
        return f"Deferred[{self._call.method} {self._call.path}, {state}]"
