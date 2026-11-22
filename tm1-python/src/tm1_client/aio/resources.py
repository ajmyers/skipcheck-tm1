"""The asyncio resources: :mod:`tm1_client.resources` with awaitable requests. Navigation
stays a plain property, since it sends nothing; everything that sends is ``async``."""

from __future__ import annotations

from typing import Any, AsyncIterator, ClassVar, Generic, Mapping, TypeVar

from .. import _calls as c
from .._calls import UNSET, Returns, Unset, endpoint, query_of
from ..base import Tm1Model
from ..odata import ODataQuery
from ..resources import _Base, _CollectionBase
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
    model: ClassVar[type[Tm1Model]] = Tm1Model

    async def get(self, query: ODataQuery | None = None, **options: Any) -> M:
        return await self._session.execute(c.get_entity(self._path, query_of(query, options), self.model))

    async def update(self, changes: M | Mapping[str, Any], *, if_match: str | None = None) -> None:
        self._check("update")
        return await self._session.execute(c.update(self._path, changes, if_match))

    async def delete(self, *, if_match: str | None = None) -> None:
        self._check("delete")
        return await self._session.execute(c.delete(self._path, if_match))

    async def _read_stream(self, prop: str | None) -> bytes:
        return await self._session.execute(c.read_stream(self._path, prop))

    async def _write_stream(self, prop: str | None, data: bytes, content_type: str) -> None:
        self._check("update")
        return await self._session.execute(c.write_stream(self._path, prop, data, content_type))


class CollectionResource(_CollectionBase, Resource, Generic[M, E]):
    entity: ClassVar[type[Any]] = EntityResource

    def __getitem__(self, key: Any) -> E:
        return super().__getitem__(key)

    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[M]:
        return await self._session.collect(c.page(self._path, query_of(query, options), self.model))

    def iter(self, query: ODataQuery | None = None, **options: Any) -> AsyncIterator[M]:
        """The entities one by one: ``async for cube in tm1.cubes.iter(): …``."""
        return self._session.iterate(c.page(self._path, query_of(query, options), self.model))

    async def count(self, filter: str | None = None) -> int:
        return await self._session.execute(c.count(self._path, filter))

    async def create(self, entity: M | Mapping[str, Any]) -> M:
        self._check("create")
        return await self._session.execute(c.create(self._path, entity, self.model))
