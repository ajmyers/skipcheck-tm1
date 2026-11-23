"""The requests resources make, as :class:`~tm1_client._protocol.Call` values: built once,
sent by the sync session, the asyncio one, a batch, or as a TM1 async operation."""

from __future__ import annotations

import functools
import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, ClassVar

from pydantic import TypeAdapter

from ._protocol import TEXT, Call, Reply
from .base import Tm1Model, to_odata
from .errors import Tm1ProtocolError
from .odata import Alias, Bind, ODataPath, ODataQuery

__all__ = [
    "UNSET",
    "Page",
    "Returns",
    "Unset",
    "endpoint",
    "implemented_endpoints",
    "query_of",
    "register_endpoint",
]


# Coverage: which code implements which endpoint of the spec (spec/tm1-api.json)

_IMPLEMENTED: dict[str, list[str]] = {}


def register_endpoint(endpoint_id: str, where: str) -> None:
    """Records that ``where`` implements ``endpoint_id`` (a coverage-gate ID)."""
    _IMPLEMENTED.setdefault(endpoint_id, []).append(where)


def endpoint(*ids: str) -> Any:
    """Marks a method as implementing endpoints, for the coverage gate (as ``@Tm1Endpoint`` does
    in the Java client). Generated and hand-written code use it alike."""

    def mark(f: Any) -> Any:
        for endpoint_id in ids:
            register_endpoint(endpoint_id, f"{f.__module__}.{f.__qualname__}")
        f.__tm1_endpoints__ = ids
        return f

    return mark


FLAVORS = {
    "sync": ("tm1_client._generated.api.", "tm1_client._generated.model.", "tm1_client._sync."),
    "async": ("tm1_client._generated.aio_api.", "tm1_client._generated.model.", "tm1_client.aio."),
}
"""Where each flavor's implementations live: the model (complex types' navigation, read
through ``$expand``) is shared."""


def implemented_endpoints(flavor: str | None = None) -> dict[str, list[str]]:
    """Every endpoint some code implements, with where; only the ``sync`` or ``async``
    flavor's if given."""
    # Importing them registers what they implement
    from . import aio, client  # noqa: F401
    from ._generated import aio_api, api, model  # noqa: F401
    from ._sync import client as _sync_client  # noqa: F401

    prefixes = None if flavor is None else FLAVORS[flavor]
    out: dict[str, list[str]] = {}
    for k, where in _IMPLEMENTED.items():
        kept = [w for w in where if prefixes is None or w.startswith(prefixes)]
        if kept:
            out[k] = kept
    return out


class Unset:
    """The value of a parameter that wasn't passed: it's left out of the request, where
    ``None`` sends an explicit ``null``."""

    _instance: ClassVar[Unset | None] = None

    def __new__(cls) -> Unset:
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __repr__(self) -> str:
        return "UNSET"

    def __bool__(self) -> bool:
        return False


UNSET: Any = Unset()


def query_of(query: ODataQuery | None, options: Mapping[str, Any]) -> ODataQuery | None:
    """A query from an :class:`ODataQuery`, keyword options (``select=``, ``filter=``, ``top=``…), or both."""
    if not options:
        return query
    return ODataQuery.of(query, **options)


@functools.cache
def _adapter(tp: Any) -> TypeAdapter[Any]:
    return TypeAdapter(tp)


@dataclass(frozen=True)
class Page:
    """One page of a collection, and the link to the next, if any."""

    items: list[Any]
    next_link: str | None


def page_of(tp: Any, reply: Reply) -> Page:
    doc = reply.json()
    if not isinstance(doc, dict) or "value" not in doc:
        raise Tm1ProtocolError(f"Expected a collection, got {str(doc)[:200]}")
    return Page(_adapter(list[tp]).validate_python(doc["value"]), doc.get("@odata.nextLink"))


class Returns:
    """What an operation returns, and how it's read: a model, a primitive wrapped as
    ``{"value": …}``, a collection as ``{"value": […]}``, or nothing."""

    __slots__ = ("type", "collection", "structured")

    def __init__(self, tp: Any = None, *, collection: bool = False, structured: bool = False):
        self.type = tp
        self.collection = collection
        self.structured = structured

    @property
    def pages(self) -> bool:
        """Whether the result is a collection of entities TM1 may send in pages."""
        return self.collection and self.structured

    def read(self, reply: Reply) -> Any:
        if self.type is None:
            return None
        if self.pages:
            return page_of(self.type, reply)
        doc = reply.json()
        if doc is None:
            return [] if self.collection else None
        if self.collection:
            if not isinstance(doc, dict) or "value" not in doc:
                raise Tm1ProtocolError(f"Expected a collection, got {str(doc)[:200]}")
            return _adapter(list[self.type]).validate_python(doc["value"])
        if self.structured:
            return _adapter(self.type).validate_python(doc)
        # TM1 wraps a primitive result as {"value": …}
        value = doc.get("value") if isinstance(doc, dict) else doc
        return _adapter(self.type | None).validate_python(value)


def _body(parameters: Mapping[str, Any]) -> dict[str, Any]:
    body: dict[str, Any] = {}
    for name, value in parameters.items():
        if value is UNSET:
            continue
        if isinstance(value, Bind):
            body[name + "@odata.bind"] = str(value)
        elif isinstance(value, (list, tuple)) and value and all(isinstance(v, Bind) for v in value):
            body[name + "@odata.bind"] = [str(v) for v in value]
        else:
            body[name] = to_odata(value)
    return body


def _structured(value: Any) -> bool:
    return isinstance(value, (Tm1Model, Mapping)) or (isinstance(value, (list, tuple)) and any(isinstance(v, (Tm1Model, Mapping)) for v in value))


# The calls


def _write(value: Any) -> Any:
    """A write's body: a model by what was set, a patch (``CubePatch.builder()…build()``), or a
    mapping (``Bind`` values become ``@odata.bind``)."""
    from .request import body_of

    return body_of(value)


def action(
    path: ODataPath,
    name: str,
    parameters: Mapping[str, Any],
    returns: Returns,
    query: ODataQuery | None,
    operation: str | None,
) -> Call:
    target = path.action(name) if path.segments else ODataPath.of(name)
    return Call("POST", str(target), query, json_body=_body(parameters), read=returns.read, operation=operation)


def function(
    path: ODataPath,
    name: str,
    parameters: Mapping[str, Any],
    returns: Returns,
    query: ODataQuery | None,
    operation: str | None,
) -> Call:
    arguments = {k: v for k, v in parameters.items() if v is not UNSET}
    # A structured value can't be a literal: it's passed as JSON through an alias
    aliases: dict[str, str] = {}
    for parameter, value in list(arguments.items()):
        if _structured(value):
            aliases["@" + parameter] = json.dumps(to_odata(value), separators=(",", ":"), ensure_ascii=False)
            arguments[parameter] = Alias("@" + parameter)
    for alias, json_value in aliases.items():
        query = (query or ODataQuery.none()).alias(alias, json_value)
    target = path.function(name, arguments) if path.segments else ODataPath.function_import(name, arguments)
    return Call("GET", str(target), query, read=returns.read, operation=operation)


def get_entity(path: ODataPath, query: ODataQuery | None, model: type[Tm1Model]) -> Call:
    return Call("GET", str(path), query, read=lambda r: model.model_validate(r.json()))


def update(path: ODataPath, changes: Any, if_match: str | None) -> Call:
    body = _write(changes)
    return Call("PATCH", str(path), json_body=body, headers={"If-Match": if_match} if if_match else {})


def delete(path: ODataPath, if_match: str | None) -> Call:
    return Call("DELETE", str(path), headers={"If-Match": if_match} if if_match else {})


def page(path: ODataPath, query: ODataQuery | None, model: type[Tm1Model]) -> Call:
    return Call("GET", str(path), query, read=lambda r: page_of(model, r))


def count(path: ODataPath, filter: str | None) -> Call:
    query = None if filter is None else ODataQuery(filter=filter)

    def read(reply: Reply) -> int:
        return int(reply.text.strip().lstrip("﻿"))

    return Call("GET", str(path.count()), query, accept=TEXT, read=read)


def create(path: ODataPath, entity: Any, model: type[Tm1Model]) -> Call:
    body = _write(entity)

    def read(reply: Reply) -> Any:
        doc = reply.json()
        return None if doc is None else model.model_validate(doc)

    return Call("POST", str(path), json_body=body, read=read)


def read_stream(path: ODataPath, prop: str | None) -> Call:
    target = path.value() if prop is None else path.nav(prop).value()
    return Call("GET", str(target), accept="*/*", read=lambda r: r.content, raw=True)


def write_stream(path: ODataPath, prop: str | None, data: bytes, content_type: str) -> Call:
    target = path.value() if prop is None else path.nav(prop).value()
    return Call("PUT", str(target), content=data, headers={"Content-Type": content_type}, raw=True)
