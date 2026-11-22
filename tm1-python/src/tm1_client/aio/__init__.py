"""The asyncio client: the same API as :mod:`tm1_client`, generated from the same spec, with
``await`` on everything that sends a request::

    from tm1_client.aio import AsyncTm1Client

    async with AsyncTm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
        print(await tm1.product_version())
"""

from .client import AsyncTm1Client
from .session import AsyncTm1Batch, AsyncTm1Session

__all__ = ["AsyncTm1Batch", "AsyncTm1Client", "AsyncTm1Session"]
