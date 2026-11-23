# SPDX-License-Identifier: Apache-2.0

"""A request of the REST API, and how its answer is read: what services send through the
session, and what callers can send for anything without a service, as the Java client's
``Tm1Request``::

    session.execute(Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none(), Cube))
    session.collect(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none().select(Cube.NAME), Cube))

Immutable: each ``with_…`` returns a new request.
"""

from __future__ import annotations

import enum
import functools
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from typing import Any, Generic, TypeVar

from pydantic import TypeAdapter

from ._protocol import Call, Reply
from .base import Tm1Model, to_odata
from .errors import Tm1ProtocolError
from .odata import Bind, ODataPath, ODataQuery
from .version import Tm1Capability

__all__ = ["Tm1Request"]

T = TypeVar("T")


@functools.cache
def _adapter(tp: Any) -> TypeAdapter[Any]:
    return TypeAdapter(tp)


def _read(tp: Any, value: Any) -> Any:
    if tp is None or tp is Any or tp is object or tp is dict:
        return value
    return _adapter(tp).validate_python(value)


def body_of(body: Any) -> Any:
    """A request body as TM1's JSON: a model by what was set, a mapping by its values (a
    :class:`~skipcheck_tm1.odata.Bind` value becomes ``Name@odata.bind``)."""
    if body is None:
        return None
    if isinstance(body, Tm1Model):
        return body.to_odata()
    if isinstance(body, Mapping):
        out: dict[str, Any] = {}
        for key, value in body.items():
            if isinstance(value, Bind):
                out[key + "@odata.bind"] = str(value)
            elif isinstance(value, (list, tuple)) and value and all(isinstance(v, Bind) for v in value):
                out[key + "@odata.bind"] = [str(v) for v in value]
            else:
                out[key] = to_odata(value)
        return out
    return to_odata(body)


@dataclass(frozen=True)
class Tm1Request(Generic[T]):
    """A request: method, path, query, headers, body, and how its answer is read."""

    class Response(enum.Enum):
        """How the response body is read."""

        SINGLE = "single"
        """One JSON value, read as ``type``."""
        COLLECTION = "collection"
        """A collection (``{"value": […]}``, maybe paged), each item read as ``type``."""
        TEXT = "text"
        BYTES = "bytes"
        NONE = "none"

    method: str
    path: ODataPath
    query: ODataQuery = field(default_factory=ODataQuery.none)
    headers: Mapping[str, str] = field(default_factory=dict)
    body: Any = None
    response: Tm1Request.Response = Response.NONE
    type: Any = None
    timeout: float | None = None
    capability: Tm1Capability | None = None
    content_type: str | None = None

    # Constructors, as Java's

    @staticmethod
    def get(path: ODataPath, query: ODataQuery | None, type: Any) -> Tm1Request[Any]:
        """``GET`` of one entity or value, read as ``type``."""
        return Tm1Request("GET", path, query or ODataQuery.none(), response=Tm1Request.Response.SINGLE, type=type)

    @staticmethod
    def list(path: ODataPath, query: ODataQuery | None, item_type: Any) -> Tm1Request[Any]:
        """``GET`` of a collection, read item by item as ``item_type``."""
        return Tm1Request("GET", path, query or ODataQuery.none(), response=Tm1Request.Response.COLLECTION, type=item_type)

    @staticmethod
    def bytes(path: ODataPath) -> Tm1Request[bytes]:
        """``GET`` of raw bytes, such as a document's ``Content``."""
        return Tm1Request("GET", path, response=Tm1Request.Response.BYTES, type=bytes)

    @staticmethod
    def put(path: ODataPath, content: bytes, content_type: str = "application/octet-stream") -> Tm1Request[None]:
        """``PUT`` of raw bytes, as ``application/octet-stream`` unless said otherwise, such as a
        document's ``Content``. TM1 (11.8) refuses a chunked body (400, with no message), so the
        bytes are sent with their ``Content-Length``: the whole content is held in memory while it's
        sent."""
        return Tm1Request("PUT", path, body=bytes(content), content_type=content_type)

    @staticmethod
    def text(path: ODataPath) -> Tm1Request[str]:
        """``GET`` of plain text, such as ``Configuration/ProductVersion/$value``."""
        return Tm1Request("GET", path, response=Tm1Request.Response.TEXT, type=str)

    @staticmethod
    def post_list(path: ODataPath, body: Any, item_type: Any) -> Tm1Request[Any]:
        """``POST`` whose response is a collection, read item by item as ``item_type``: an action
        returning ``Collection(...)``, such as ``tm1.CheckRules``. ``body`` is the parameters,
        written as JSON."""
        if body is None:
            raise ValueError("body")
        return Tm1Request("POST", path, body=body, response=Tm1Request.Response.COLLECTION, type=item_type)

    @staticmethod
    def post(path: ODataPath, body: Any, type: Any = None) -> Tm1Request[Any]:
        """``POST``; ``type`` None means no answer is read (Java's ``Void``)."""
        response = Tm1Request.Response.NONE if type is None else Tm1Request.Response.SINGLE
        return Tm1Request("POST", path, body={} if body is None else body, response=response, type=type)

    @staticmethod
    def patch(path: ODataPath, body: Any) -> Tm1Request[None]:
        """``PATCH``: change the properties in ``body``, leaving the rest as they are."""
        if body is None:
            raise ValueError("body")
        return Tm1Request("PATCH", path, body=body)

    @staticmethod
    def delete(path: ODataPath) -> Tm1Request[None]:
        """``DELETE`` of the entity at ``path``."""
        return Tm1Request("DELETE", path)

    # Derived requests

    def with_query(self, query: ODataQuery | None) -> Tm1Request[T]:
        """A copy with these query options instead."""
        return replace(self, query=query or ODataQuery.none())

    def with_header(self, name: str, value: str) -> Tm1Request[T]:
        """A copy with a header added or replaced, such as ``If-Match``."""
        return replace(self, headers={**self.headers, name: value})

    def with_timeout(self, seconds: float) -> Tm1Request[T]:
        """A copy that may go ``seconds`` without receiving any data before it fails, instead of the
        session's request timeout. For calls TM1 only answers when the work is done, such as a long
        process run. Must be positive."""
        if seconds <= 0:
            raise ValueError(f"timeout must be positive: {seconds}")
        return replace(self, timeout=float(seconds))

    def requires(self, capability: Tm1Capability) -> Tm1Request[T]:
        """The request, checked against the server's ``$metadata`` before it's sent: an
        :class:`~skipcheck_tm1.errors.Tm1UnsupportedOperationError` instead if it lacks it."""
        return replace(self, capability=capability)

    def requirement(self) -> Tm1Capability | None:
        """What this request needs the server to define, or None."""
        return self.capability

    def if_match(self, etag: str) -> Tm1Request[T]:
        """A copy sent with ``If-Match: etag``, failing with 412 if the entity has changed since."""
        return self.with_header("If-Match", etag)

    def is_idempotent(self) -> bool:
        """Whether sending it twice has the effect of sending it once: GET, PUT, DELETE, HEAD."""
        return self.method.upper() in ("GET", "PUT", "DELETE", "HEAD")

    def relative_uri(self) -> str:
        """The encoded path and query relative to the service root, such as
        ``Cubes('Sales')?$select=Name``."""
        q = self.query.to_query_string()
        return str(self.path) + ("?" + q if q else "")

    # Sending

    def read(self, reply: Reply) -> Any:
        """Reads the answer as the request says to: nothing, bytes, text (without a byte order
        mark), one value, or a page of a collection."""
        r = Tm1Request.Response
        if self.response is r.NONE:
            return None
        if self.response is r.BYTES:
            return reply.content
        if self.response is r.TEXT:
            return reply.text.lstrip("﻿")
        doc = reply.json()
        if self.response is r.COLLECTION:
            from ._calls import Page

            if not isinstance(doc, dict) or "value" not in doc:
                raise Tm1ProtocolError(f"{self.method} {self.path}: expected a collection, got {str(doc)[:200]}")
            return Page([_read(self.type, item) for item in doc["value"]], doc.get("@odata.nextLink"))
        return _read(self.type, doc)

    def to_call(self) -> Call:
        """The request as the sessions send it."""
        r = Tm1Request.Response
        raw = self.response is r.BYTES or isinstance(self.body, (bytes, bytearray))
        if isinstance(self.body, (bytes, bytearray)):
            headers = {**self.headers, "Content-Type": self.content_type or "application/octet-stream"}
            return Call(
                self.method,
                str(self.path),
                self.query,
                None,
                bytes(self.body),
                headers,
                _accept(self.response),
                self.read,
                raw=True,
                requires=self.capability,
                timeout=self.timeout,
            )
        return Call(
            self.method,
            str(self.path),
            None if self.query.is_empty() else self.query,
            body_of(self.body),
            None,
            dict(self.headers),
            _accept(self.response),
            self.read,
            raw=raw,
            requires=self.capability,
            timeout=self.timeout,
        )

    def __str__(self) -> str:
        return f"{self.method} {self.relative_uri()}"


def _accept(response: Tm1Request.Response) -> str:
    from ._protocol import JSON, TEXT

    if response is Tm1Request.Response.BYTES:
        return "*/*"
    if response is Tm1Request.Response.TEXT:
        return TEXT
    return JSON
