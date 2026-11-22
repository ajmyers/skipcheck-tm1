"""Resources: typed handles on paths of the REST API, which the generated API is made of.

A resource doesn't send anything until a method is called::

    cube = client.cubes["Sales"]          # Cubes('Sales'), nothing sent yet
    cube.get(select=["Name", "Rules"])    # GET Cubes('Sales')?$select=Name,Rules
    cube.views["Default"].get()           # GET Cubes('Sales')/Views('Default')
    cube.reserve(release_existing=True)   # POST Cubes('Sales')/tm1.Reserve

What a resource offers follows OData's rules, made safe:

* An entity set, or a navigation that *contains* its targets (``Cube/Views``), can create,
  update and delete through its path.
* A navigation that only *refers* to entities kept elsewhere (``Cube/Dimensions``) reads:
  changing a dimension goes through ``Dimensions``, its canonical path. On TM1, deleting
  through such a path deletes the target itself (``Users('u')/Groups('g')`` deletes the
  group), so it's refused here.
* The overlay (``spec/tm1-overlay.json``) can forbid more.
"""

from __future__ import annotations

import functools
import json
from typing import Any, ClassVar, Generic, Iterator, Mapping, Sequence, TypeVar

from pydantic import TypeAdapter

from .base import Tm1Model, to_odata
from .errors import Tm1ProtocolError, Tm1UnsupportedOperationError
from .odata import Alias, Bind, ODataPath, ODataQuery
from .session import Tm1Session

__all__ = [
    "UNSET",
    "CollectionResource",
    "EntityResource",
    "Resource",
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
            register_endpoint(endpoint_id, f.__qualname__)
        f.__tm1_endpoints__ = ids
        return f

    return mark


def implemented_endpoints() -> dict[str, list[str]]:
    """Every endpoint some code implements, with where."""
    from . import _generated  # noqa: F401  (importing it registers the generated API)

    return {k: list(v) for k, v in _IMPLEMENTED.items()}


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
    if query is None:
        return ODataQuery(**options)
    merged = {f: getattr(query, f) for f in ("select", "expand", "filter", "orderby", "top", "skip", "count", "search", "custom")}
    merged.update(options)
    return ODataQuery(**merged)


@functools.lru_cache(maxsize=None)
def _adapter(tp: Any) -> TypeAdapter[Any]:
    return TypeAdapter(tp)


class Returns:
    """What an operation returns, and how it's read: a model, a primitive wrapped as
    ``{"value": …}``, a collection as ``{"value": […]}``, or nothing."""

    __slots__ = ("type", "collection", "structured")

    def __init__(self, tp: Any = None, *, collection: bool = False, structured: bool = False):
        self.type = tp
        self.collection = collection
        self.structured = structured

    def read(self, doc: Any) -> Any:
        if self.type is None:
            return None
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


def _arguments(parameters: Mapping[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in parameters.items() if v is not UNSET}


class Resource:
    """A path of the API, with the session to send through."""

    #: Generic requests this resource doesn't offer, with why
    _forbidden: Mapping[str, str]

    def __init__(self, session: Tm1Session, path: ODataPath, forbid: Mapping[str, str] | None = None):
        self._session = session
        self._path = path
        self._forbidden = dict(forbid or {})

    @property
    def path(self) -> ODataPath:
        return self._path

    @property
    def session(self) -> Tm1Session:
        return self._session

    def __repr__(self) -> str:
        return f"{type(self).__name__}({str(self._path) or '/'})"

    def _check(self, request: str) -> None:
        reason = self._forbidden.get(request)
        if reason is not None:
            raise Tm1UnsupportedOperationError(f"{request} isn't offered for {self._path}: {reason}")

    # Operations, called by the generated methods

    def _action(
        self, name: str, parameters: Mapping[str, Any], returns: Returns, query: ODataQuery | None = None
    ) -> Any:
        path = self._path.action(name) if self._path.segments else ODataPath.of(name)
        response = self._session.request("POST", path, query=query, json_body=_body(parameters))
        return returns.read(_json(response))

    def _function(
        self, name: str, parameters: Mapping[str, Any], returns: Returns, query: ODataQuery | None = None
    ) -> Any:
        arguments = _arguments(parameters)
        # A structured value can't be a literal: it's passed as JSON through an alias
        aliases: dict[str, str] = {}
        for parameter, value in list(arguments.items()):
            if isinstance(value, (Tm1Model, Mapping)) or (
                isinstance(value, (list, tuple)) and any(isinstance(v, (Tm1Model, Mapping)) for v in value)
            ):
                aliases["@" + parameter] = json.dumps(to_odata(value), separators=(",", ":"), ensure_ascii=False)
                arguments[parameter] = Alias("@" + parameter)
        if aliases:
            query = query_of(query, {"custom": {**(query.custom if query else {}), **aliases}})
        path = (
            self._path.function(name, arguments) if self._path.segments else ODataPath.function_import(name, arguments)
        )
        if returns.collection and returns.structured:
            return _adapter(list[returns.type]).validate_python(list(self._session.iterate(path, query)))
        return returns.read(self._session.get_json(path, query))


def _json(response: Any) -> Any:
    if response.status_code == 204 or not response.content:
        return None
    return response.json()


M = TypeVar("M", bound=Tm1Model)
E = TypeVar("E", bound="EntityResource[Any]")


class EntityResource(Resource, Generic[M]):
    """One entity, or a singleton: read, update, delete, and the operations bound to it."""

    model: ClassVar[type[Tm1Model]] = Tm1Model

    def get(self, query: ODataQuery | None = None, **options: Any) -> M:
        """``GET``: the entity, with what ``$select`` and ``$expand`` ask for."""
        return self.model.model_validate(self._session.get_json(self._path, query_of(query, options)))  # type: ignore[return-value]

    def update(self, changes: M | Mapping[str, Any], *, if_match: str | None = None) -> None:
        """``PATCH``: changes the properties ``changes`` sets (an explicit ``None`` clears one).
        With ``if_match`` (the entity's ``etag``), only if it hasn't changed since."""
        self._check("update")
        body = changes.to_odata() if isinstance(changes, Tm1Model) else to_odata(dict(changes))
        headers = {"If-Match": if_match} if if_match else None
        self._session.request("PATCH", self._path, json_body=body, headers=headers)

    def delete(self, *, if_match: str | None = None) -> None:
        """``DELETE``."""
        self._check("delete")
        headers = {"If-Match": if_match} if if_match else None
        self._session.request("DELETE", self._path, headers=headers)

    # Media: an entity with a stream (HasStream), and stream properties

    def _read_stream(self, prop: str | None) -> bytes:
        path = self._path.value() if prop is None else self._path.nav(prop).value()
        return self._session.request("GET", path, accept="*/*").content

    def _write_stream(self, prop: str | None, data: bytes, content_type: str) -> None:
        self._check("update")
        path = self._path.value() if prop is None else self._path.nav(prop).value()
        self._session.request("PUT", path, content=data, headers={"Content-Type": content_type})


class CollectionResource(Resource, Generic[M, E]):
    """A collection of entities: list, count, create, and each entity by key."""

    model: ClassVar[type[Tm1Model]] = Tm1Model
    entity: ClassVar[type[EntityResource[Any]]] = EntityResource
    key_names: ClassVar[Sequence[str]] = ()

    def __init__(
        self,
        session: Tm1Session,
        path: ODataPath,
        forbid: Mapping[str, str] | None = None,
        *,
        contained: bool = True,
    ):
        refused = dict(forbid or {})
        if not contained:
            why = "it refers to entities kept elsewhere; change them through their own collection"
            refused.setdefault("create", why)
            refused.setdefault("delete", why)
        super().__init__(session, path, refused)

    def __getitem__(self, key: Any) -> E:
        """The entity with ``key``: a value, or for a compound key a tuple or mapping."""
        if isinstance(key, tuple):
            if len(key) != len(self.key_names):
                raise ValueError(f"{self.model.__name__} has a key of {len(self.key_names)} parts: {self.key_names}")
            key = dict(zip(self.key_names, key))
        elif len(self.key_names) > 1 and not isinstance(key, Mapping):
            raise ValueError(f"{self.model.__name__} has a compound key: {self.key_names}")
        entity_forbid = {k: v for k, v in self._forbidden.items() if k in ("delete", "update")}
        return self.entity(self._session, self._path.key(key), entity_forbid)  # type: ignore[return-value]

    def list(self, query: ODataQuery | None = None, **options: Any) -> list[M]:
        """``GET``: every entity, across pages, with what the query asks for."""
        return list(self.iter(query, **options))

    def iter(self, query: ODataQuery | None = None, **options: Any) -> Iterator[M]:
        """``GET``: the entities one by one, fetching pages as they're needed."""
        for item in self._session.iterate(self._path, query_of(query, options)):
            yield self.model.model_validate(item)  # type: ignore[misc]

    def count(self, filter: str | None = None) -> int:
        """``GET …/$count``."""
        query = None if filter is None else ODataQuery(filter=filter)
        response = self._session.request("GET", self._path.count(), query=query, accept="text/plain")
        return int(response.text.strip().lstrip("﻿"))

    def create(self, entity: M | Mapping[str, Any]) -> M:
        """``POST``: creates an entity (with nested ones, a deep insert, and ``Bind`` references
        to existing ones), and returns it as TM1 made it."""
        self._check("create")
        body = entity.to_odata() if isinstance(entity, Tm1Model) else to_odata(dict(entity))
        response = self._session.request("POST", self._path, json_body=body)
        doc = _json(response)
        return None if doc is None else self.model.model_validate(doc)  # type: ignore[return-value]
