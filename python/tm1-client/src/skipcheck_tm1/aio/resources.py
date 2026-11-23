# SPDX-License-Identifier: Apache-2.0

"""Resources: typed handles on paths of the REST API, which the generated API is made of.

A resource sends nothing until a method is called: ``tm1.api.cubes["Sales"]`` is a path;
``.get()`` sends ``GET Cubes('Sales')``. Navigation is a plain property, since it sends
nothing; every method that sends a request is a coroutine in the asyncio flavor.

What a resource offers follows OData's rules, made safe:

* An entity set, or a navigation that *contains* its targets (``Cube/Views``), can create,
  update and delete through its path.
* A navigation that only *refers* to entities kept elsewhere (``Cube/Dimensions``) reads:
  changing a dimension goes through ``Dimensions``, its canonical path. On TM1, deleting
  through such a path deletes the target itself (``Users('u')/Groups('g')`` deletes the
  group), so it's refused here.
* The overlay (``spec/tm1-overlay.json``) can forbid more.

The requests are built in :mod:`skipcheck_tm1._calls`; these classes only send them.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from typing import Any, ClassVar, Generic, TypeVar

from .. import _calls as c
from .._calls import UNSET, Returns, Unset, endpoint, query_of
from .._resource_base import _Base, _CollectionBase
from ..base import Tm1Model
from ..odata import ODataQuery
from .session import AsyncTm1Session

__all__ = ["UNSET", "CollectionResource", "EntityResource", "Resource", "Returns", "Unset", "endpoint", "query_of"]


class Resource(_Base):
    """A path of the API, with the asyncio session to send through."""

    _session: AsyncTm1Session

    async def _action(
        self,
        name: str,
        parameters: Mapping[str, Any],
        returns: Returns,
        query: ODataQuery | None = None,
        operation: str | None = None,
    ) -> Any:
        return await self._session.execute(c.action(self._path, name, parameters, returns, query, operation))

    async def _function(
        self,
        name: str,
        parameters: Mapping[str, Any],
        returns: Returns,
        query: ODataQuery | None = None,
        operation: str | None = None,
    ) -> Any:
        call = c.function(self._path, name, parameters, returns, query, operation)
        return await (self._session.collect(call) if returns.pages else self._session.execute(call))


M = TypeVar("M", bound=Tm1Model)
E = TypeVar("E", bound="EntityResource[Any]")


class EntityResource(Resource, Generic[M]):
    """One entity of the generated API, at its path, such as ``tm1.api.cubes['Sales']``: read,
    changed and deleted here, with its navigation properties and bound operations in the generated
    subclasses."""

    model: ClassVar[type[Tm1Model]] = Tm1Model

    async def get(self, query: ODataQuery | None = None, **options: Any) -> M:
        """``GET`` of the entity, read as its model. Query options come as a query or as keywords
        (``select=``, ``expand=``)."""
        return await self._session.execute(c.get_entity(self._path, query_of(query, options), self.model))

    async def update(self, changes: M | Mapping[str, Any], *, if_match: str | None = None) -> None:
        """``PATCH``: changes the properties ``changes`` sets, leaving the rest as they are. With
        ``if_match``, an ETag, TM1 refuses the change with 412 if the entity has changed since."""
        self._check("update")
        return await self._session.execute(c.update(self._path, changes, if_match))

    async def delete(self, *, if_match: str | None = None) -> None:
        """``DELETE`` of the entity. With ``if_match``, an ETag, TM1 refuses with 412 if the entity
        has changed since."""
        self._check("delete")
        return await self._session.execute(c.delete(self._path, if_match))

    async def _read_stream(self, prop: str | None) -> bytes:
        return await self._session.execute(c.read_stream(self._path, prop))

    async def _write_stream(self, prop: str | None, data: bytes, content_type: str) -> None:
        self._check("update")
        return await self._session.execute(c.write_stream(self._path, prop, data, content_type))


class CollectionResource(_CollectionBase, Resource, Generic[M, E]):
    """A collection of the generated API, at its path, such as ``tm1.api.cubes``: listed, counted
    and added to here; ``[key]`` gives one entity's resource without sending anything."""

    entity: ClassVar[type[Any]] = EntityResource

    def __getitem__(self, key: Any) -> E:
        return super().__getitem__(key)

    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[M]:
        """``GET`` of the whole collection, every page of it, each item read as its model. Query
        options come as a query or as keywords (``select=``, ``filter=``, ``top=``)."""
        return await self._session.collect(c.page(self._path, query_of(query, options), self.model))

    def iter(self, query: ODataQuery | None = None, **options: Any) -> AsyncIterator[M]:
        """The entities one by one: ``async for cube in tm1.cubes.iter(): …``."""
        return self._session.iterate(c.page(self._path, query_of(query, options), self.model))

    async def count(self, filter: str | None = None) -> int:
        """``GET`` of ``$count``: how many entities the collection has, or how many match
        ``filter``."""
        return await self._session.execute(c.count(self._path, filter))

    async def create(self, entity: M | Mapping[str, Any]) -> M:
        """``POST``: adds an entity to the collection, and returns it as TM1 created it."""
        self._check("create")
        return await self._session.execute(c.create(self._path, entity, self.model))
