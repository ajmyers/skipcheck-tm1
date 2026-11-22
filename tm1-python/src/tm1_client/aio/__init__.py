"""The asyncio flavor of the client. It's the source of truth: the sync flavor
(``tm1_client``) is generated from it, so the two have the same API, the asyncio one with
``await`` on everything that sends a request::

    from tm1_client.aio import AsyncTm1Client

    async with AsyncTm1Client.builder().base_url("https://tm1:8010/api/v1").basic_auth("admin", "apple").build() as tm1:
        print(await tm1.server.product_version())
"""

from .client import AsyncTm1Batch, AsyncTm1Client, AsyncTm1ClientBuilder
from .session import AsyncTm1Session, AsyncTm1SessionBatch, AsyncTm1SessionBuilder

__all__ = [
    "AsyncTm1Batch",
    "AsyncTm1Client",
    "AsyncTm1ClientBuilder",
    "AsyncTm1Session",
    "AsyncTm1SessionBatch",
    "AsyncTm1SessionBuilder",
]
