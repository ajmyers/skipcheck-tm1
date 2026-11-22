"""The client: the generated API, complete, plus hand-written conveniences on top.

The generated layer (:class:`~tm1_client._generated.api.Tm1Api`) has a member for every
endpoint of the REST API, named after it. What's here is for what TM1 makes awkward; each
method says which endpoints it uses, so the coverage gate counts them too.
"""

from __future__ import annotations

from typing import Any

from . import auth
from ._generated.api import Tm1Api
from .odata import ODataQuery
from .resources import endpoint
from .session import Tm1Session

__all__ = ["Tm1Client"]


class Tm1Client(Tm1Api):
    """Everything the REST API offers, through one session::

        with Tm1Client.connect("https://tm1:8010/api/v1", user="admin", password="apple") as tm1:
            for cube in tm1.cubes.list(select=["Name"]):
                print(cube.name)
            tm1.cubes["Sales"].views["Default"].get()
            tm1.execute_mdx(mdx="SELECT ... ON 0 FROM [Sales]", expand="Cells($select=Value)")
    """

    def __init__(self, session: Tm1Session):
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
    ) -> Tm1Client:
        """A client with a session of its own: TM1 security, or CAM with ``namespace``."""
        authenticator = (
            auth.basic(user, password) if namespace is None else auth.cam_namespace(user, password or "", namespace)
        )
        return cls(Tm1Session(base_url, authenticator, **session_options))

    def __enter__(self) -> Tm1Client:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        """Ends the session."""
        self._session.close()

    @endpoint("Singleton Configuration")
    def product_version(self) -> str | None:
        """The server's version, such as ``11.8.03500.1``."""
        return self.configuration.get(select="ProductVersion").product_version

    @endpoint("ActionImport ExecuteMDX")
    def mdx_values(self, mdx: str) -> list[Any]:
        """The values of the cells an MDX ``SELECT`` returns, in ordinal order: TM1 makes a
        cellset, whose cells are read in the same request (``$expand=Cells``), then deletes it."""
        cellset = self.execute_mdx(mdx=mdx, query=ODataQuery(select="ID", expand="Cells($select=Ordinal,Value)"))
        try:
            cells = sorted(cellset.cells or [], key=lambda c: c.ordinal or 0)
            return [c.value for c in cells]
        finally:
            if cellset.id:
                self.cellsets[cellset.id].delete()
