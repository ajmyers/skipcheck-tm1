"""The asyncio client: the generated asyncio API, plus the same conveniences as
:class:`~tm1_client.Tm1Client`."""

from __future__ import annotations

from typing import Any

from .. import auth
from .._calls import endpoint
from .._generated.aio_api import Tm1Api
from ..odata import ODataQuery
from .session import AsyncTm1Batch, AsyncTm1Session

__all__ = ["AsyncTm1Client"]


class AsyncTm1Client(Tm1Api):
    """Everything the REST API offers, for asyncio::

        async with AsyncTm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
            cubes = await tm1.cubes.list(select="Name")
            async for cube in tm1.cubes.iter():
                ...
    """

    _session: AsyncTm1Session

    def __init__(self, session: AsyncTm1Session):
        super().__init__(session)

    @classmethod
    def connect(
        cls,
        base_url: str,
        *,
        user: str,
        password: str | None = None,
        namespace: str | None = None,
        **session_options: Any,
    ) -> AsyncTm1Client:
        authenticator = (
            auth.basic(user, password) if namespace is None else auth.cam_namespace(user, password or "", namespace)
        )
        return cls(AsyncTm1Session(base_url, authenticator, **session_options))

    async def __aenter__(self) -> AsyncTm1Client:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._session.close()

    def with_sandbox(self, name: str) -> AsyncTm1Client:
        return type(self)(self._session.with_sandbox(name))

    def with_change_set(self, change_set: str) -> AsyncTm1Client:
        return type(self)(self._session.with_change_set(change_set))

    def with_async(self, every: float = 1.0) -> AsyncTm1Client:
        return type(self)(self._session.with_async(every))

    def batch(self) -> AsyncTm1Batch:
        return self._session.batch()

    @endpoint("Singleton Configuration")
    async def product_version(self) -> str | None:
        return (await self.configuration.get(select="ProductVersion")).product_version

    @endpoint("ActionImport ExecuteMDX")
    async def mdx_values(self, mdx: str) -> list[Any]:
        cellset = await self.execute_mdx(mdx=mdx, query=ODataQuery(select="ID", expand="Cells($select=Ordinal,Value)"))
        try:
            cells = sorted(cellset.cells or [], key=lambda c: c.ordinal or 0)
            return [c.value for c in cells]
        finally:
            if cellset.id:
                await self.cellsets[cellset.id].delete()
