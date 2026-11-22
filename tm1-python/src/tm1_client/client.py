"""The client: the generated API, complete, plus hand-written conveniences on top.

The generated layer (:class:`~tm1_client._generated.api.Tm1Api`) has a member for every
endpoint of the REST API, named after it. What's here is for what TM1 makes awkward; each
method says which endpoints it uses, so the coverage gate counts them too.
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import timedelta
from typing import Any, Iterator

from . import auth
from ._generated.api import Tm1Api
from .odata import ODataQuery
from .resources import endpoint
from .session import Tm1Batch, Tm1Session

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

    # Views: clients over a view of the session, sharing its login

    def with_sandbox(self, name: str) -> Tm1Client:
        """A client whose requests run in sandbox ``name``."""
        return type(self)(self._session.with_sandbox(name))

    def without_sandbox(self) -> Tm1Client:
        return type(self)(self._session.without_sandbox())

    def with_change_set(self, change_set: str) -> Tm1Client:
        """A client whose writes are recorded in change set ``change_set``; see :meth:`change_set`."""
        return type(self)(self._session.with_change_set(change_set))

    def with_async(self, every: float | timedelta = 1.0) -> Tm1Client:
        """A client whose requests TM1 runs asynchronously, polled every ``every`` seconds: for a
        long process or chore, so no request is held open for its whole run."""
        return type(self)(self._session.with_async(every))

    def batch(self) -> Tm1Batch:
        """A JSON ``$batch``: calls through ``batch.client`` are queued and sent together; see
        :class:`~tm1_client.session.Tm1Batch`."""
        return self._session.batch()

    @contextmanager
    @endpoint("ActionImport BeginChangeSet", "ActionImport EndChangeSet")
    def change_set(self) -> Iterator[Tm1Client]:
        """Begins a change set, gives the block a client that records its writes in it, and
        ends it however the block finishes. Its ID (``client.session.change_set``) is for a
        later ``undo_change_set``::

            with tm1.change_set() as recorded:
                recorded.cubes["Sales"].update_cells(...)
                change_set = recorded.session.change_set
            tm1.undo_change_set(change_set_id=change_set)
        """
        change_set_id = self.begin_change_set()
        try:
            yield self.with_change_set(str(change_set_id))
        finally:
            self.end_change_set(change_set_id=str(change_set_id))

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
