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

The requests themselves are built in :mod:`tm1_client._calls`, shared with the asyncio
resources (:mod:`tm1_client.aio`); these classes only send them.
"""

from __future__ import annotations

from typing import Any, ClassVar, Generic, Iterator, Mapping, Sequence, TypeVar

from . import _calls as c
from ._calls import UNSET, Returns, Unset, endpoint, implemented_endpoints, query_of, register_endpoint
from .base import Tm1Model
from .errors import Tm1UnsupportedOperationError
from .odata import ODataPath, ODataQuery
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

REFERENCE = "it refers to entities kept elsewhere; change them through their own collection"


class _Base:
    """What the sync and asyncio resources share: a path, a session, and what's refused."""

    _forbidden: Mapping[str, str]

    def __init__(self, session: Any, path: ODataPath, forbid: Mapping[str, str] | None = None):
        self._session = session
        self._path = path
        self._forbidden = dict(forbid or {})

    @property
    def path(self) -> ODataPath:
        return self._path

    @property
    def session(self) -> Any:
        return self._session

    def __repr__(self) -> str:
        return f"{type(self).__name__}({str(self._path) or '/'})"

    def _check(self, request: str) -> None:
        reason = self._forbidden.get(request)
        if reason is not None:
            raise Tm1UnsupportedOperationError(f"{request} isn't offered for {self._path}: {reason}")


class _CollectionBase(_Base):
    model: ClassVar[type[Tm1Model]] = Tm1Model
    entity: ClassVar[type[Any]] = object
    key_names: ClassVar[Sequence[str]] = ()

    def __init__(self, session: Any, path: ODataPath, forbid: Mapping[str, str] | None = None, *, contained: bool = True):
        refused = dict(forbid or {})
        if not contained:
            refused.setdefault("create", REFERENCE)
            refused.setdefault("delete", REFERENCE)
        super().__init__(session, path, refused)

    def __getitem__(self, key: Any) -> Any:
        """The entity with ``key``: a value, or for a compound key a tuple or mapping."""
        if isinstance(key, tuple):
            if len(key) != len(self.key_names):
                raise ValueError(f"{self.model.__name__} has a key of {len(self.key_names)} parts: {self.key_names}")
            key = dict(zip(self.key_names, key))
        elif len(self.key_names) > 1 and not isinstance(key, Mapping):
            raise ValueError(f"{self.model.__name__} has a compound key: {self.key_names}")
        entity_forbid = {k: v for k, v in self._forbidden.items() if k in ("delete", "update")}
        return self.entity(self._session, self._path.key(key), entity_forbid)


class Resource(_Base):
    """A path of the API, with the session to send through."""

    _session: Tm1Session

    def _action(
        self,
        name: str,
        parameters: Mapping[str, Any],
        returns: Returns,
        query: ODataQuery | None = None,
        operation: str | None = None,
    ) -> Any:
        return self._session.execute(c.action(self._path, name, parameters, returns, query, operation))

    def _function(
        self,
        name: str,
        parameters: Mapping[str, Any],
        returns: Returns,
        query: ODataQuery | None = None,
        operation: str | None = None,
    ) -> Any:
        call = c.function(self._path, name, parameters, returns, query, operation)
        return self._session.collect(call) if returns.pages else self._session.execute(call)


M = TypeVar("M", bound=Tm1Model)
E = TypeVar("E", bound="EntityResource[Any]")


class EntityResource(Resource, Generic[M]):
    """One entity, or a singleton: read, update, delete, and the operations bound to it."""

    model: ClassVar[type[Tm1Model]] = Tm1Model

    def get(self, query: ODataQuery | None = None, **options: Any) -> M:
        """``GET``: the entity, with what ``$select`` and ``$expand`` ask for."""
        return self._session.execute(c.get_entity(self._path, query_of(query, options), self.model))

    def update(self, changes: M | Mapping[str, Any], *, if_match: str | None = None) -> None:
        """``PATCH``: changes the properties ``changes`` sets (an explicit ``None`` clears one).
        With ``if_match`` (the entity's ``etag``), only if it hasn't changed since."""
        self._check("update")
        return self._session.execute(c.update(self._path, changes, if_match))

    def delete(self, *, if_match: str | None = None) -> None:
        """``DELETE``."""
        self._check("delete")
        return self._session.execute(c.delete(self._path, if_match))

    def _read_stream(self, prop: str | None) -> bytes:
        return self._session.execute(c.read_stream(self._path, prop))

    def _write_stream(self, prop: str | None, data: bytes, content_type: str) -> None:
        self._check("update")
        return self._session.execute(c.write_stream(self._path, prop, data, content_type))


class CollectionResource(_CollectionBase, Resource, Generic[M, E]):
    """A collection of entities: list, count, create, and each entity by key."""

    entity: ClassVar[type[Any]] = EntityResource

    def __getitem__(self, key: Any) -> E:
        return super().__getitem__(key)

    def list(self, query: ODataQuery | None = None, **options: Any) -> list[M]:
        """``GET``: every entity, across pages, with what the query asks for."""
        return self._session.collect(c.page(self._path, query_of(query, options), self.model))

    def iter(self, query: ODataQuery | None = None, **options: Any) -> Iterator[M]:
        """``GET``: the entities one by one, fetching pages as they're needed."""
        return self._session.iterate(c.page(self._path, query_of(query, options), self.model))

    def count(self, filter: str | None = None) -> int:
        """``GET …/$count``."""
        return self._session.execute(c.count(self._path, filter))

    def create(self, entity: M | Mapping[str, Any]) -> M:
        """``POST``: creates an entity (with nested ones, a deep insert, and ``Bind`` references
        to existing ones), and returns it as TM1 made it."""
        self._check("create")
        return self._session.execute(c.create(self._path, entity, self.model))
