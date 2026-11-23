# SPDX-License-Identifier: Apache-2.0

"""The client for one TM1 server: one session, and every service built on it, as the Java
client's ``Tm1Client``::

    tm1 = AsyncTm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "apple").build()
    async with tm1:
        for cube in await tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME)):
            print(cube.name)

Run a long process asynchronously, so no request stays open for its whole run::

    result = await tm1.with_async(2).processes.execute_with_return("Rebuild Forecast")

Every endpoint of the REST API also has a generated member under :attr:`api`
(``tm1.api.cubes["Sales"].views.list()``), and :attr:`session` sends any request.

This module is the source of truth for both flavors: the sync ``Tm1Client`` is generated from
it (``tm1_pygen.unasync``).
"""

from __future__ import annotations

from datetime import timedelta

from .._generated.aio_api import Tm1Api
from .services import (
    AnnotationService,
    AttributeService,
    CellService,
    CellsetService,
    ChangeSetService,
    ChoreService,
    ContentService,
    CubeService,
    DimensionService,
    DrillthroughService,
    EdgeService,
    ElementService,
    EncryptionService,
    GitService,
    GroupService,
    HierarchyService,
    LevelService,
    LocalizedAttributeService,
    LogService,
    MemberService,
    MemberSetService,
    OidcKeyService,
    ProcessDebugService,
    ProcessService,
    SandboxService,
    ServerService,
    SessionService,
    SqlDataSourceService,
    SubsetService,
    ThreadService,
    UserService,
    ViewService,
)
from .session import AsyncTm1Session, AsyncTm1SessionBatch, AsyncTm1SessionBuilder

__all__ = ["AsyncTm1Batch", "AsyncTm1Client", "AsyncTm1ClientBuilder"]


class AsyncTm1Client:
    """One session, and every service on it. Services are properties (Java's ``cubes()`` is
    ``cubes``); a view of the client (``with_sandbox``, ``with_async``, ``batch``) is a client
    whose services send through that view."""

    def __init__(self, session: AsyncTm1Session):
        if session is None:
            raise TypeError("session")
        self._session = session
        self._dimensions = DimensionService(session)
        self._hierarchies = HierarchyService(session)
        self._elements = ElementService(session)
        self._edges = EdgeService(session)
        self._levels = LevelService(session)
        self._members = MemberService(session)
        self._subsets = SubsetService(session)
        self._attributes = AttributeService(session)
        self._localized_attributes = LocalizedAttributeService(session)
        self._cubes = CubeService(session)
        self._views = ViewService(session)
        self._cellsets = CellsetService(session)
        self._cells = CellService(session)
        self._member_sets = MemberSetService(session)
        self._sandboxes = SandboxService(session)
        self._change_sets = ChangeSetService(session)
        self._drillthrough = DrillthroughService(session)
        self._processes = ProcessService(session)
        self._process_debugging = ProcessDebugService(session)
        self._chores = ChoreService(session)
        self._users = UserService(session)
        self._groups = GroupService(session)
        self._sessions = SessionService(session)
        self._threads = ThreadService(session)
        self._server = ServerService(session)
        self._logs = LogService(session)
        self._contents = ContentService(session)
        self._annotations = AnnotationService(session)
        self._git = GitService(session)
        self._encryption = EncryptionService(session)
        self._oidc_keys = OidcKeyService(session)
        self._sql_data_sources = SqlDataSourceService(session)
        self._api: Tm1Api | None = None

    @staticmethod
    def builder() -> AsyncTm1ClientBuilder:
        """A builder for a client of one TM1 server."""
        return AsyncTm1ClientBuilder()

    @staticmethod
    def of(session: AsyncTm1Session) -> AsyncTm1Client:
        """A client over ``session``, which it shares."""
        return AsyncTm1Client(session)

    @property
    def session(self) -> AsyncTm1Session:
        """The session, which sends any request (``Tm1Request``)."""
        return self._session

    @property
    def api(self) -> Tm1Api:
        """The generated API: a member for every endpoint, over the same session."""
        if self._api is None:
            self._api = Tm1Api(self._session)
        return self._api

    # The services, as in Java

    @property
    def dimensions(self) -> DimensionService:
        """Dimensions: the dimension service, on this client's session."""
        return self._dimensions

    @property
    def hierarchies(self) -> HierarchyService:
        """Hierarchies: the hierarchy service, on this client's session."""
        return self._hierarchies

    @property
    def elements(self) -> ElementService:
        """Elements: the element service, on this client's session."""
        return self._elements

    @property
    def edges(self) -> EdgeService:
        """Edges: the edge service, on this client's session."""
        return self._edges

    @property
    def levels(self) -> LevelService:
        """Levels: the level service, on this client's session."""
        return self._levels

    @property
    def members(self) -> MemberService:
        """Members: the member service, on this client's session."""
        return self._members

    @property
    def subsets(self) -> SubsetService:
        """Subsets: the subset service, on this client's session."""
        return self._subsets

    @property
    def attributes(self) -> AttributeService:
        """Attribute definitions: the attribute service, on this client's session."""
        return self._attributes

    @property
    def localized_attributes(self) -> LocalizedAttributeService:
        """Translated attribute values: the localized attribute service, on this client's session."""
        return self._localized_attributes

    @property
    def cubes(self) -> CubeService:
        """Cubes: the cube service, on this client's session."""
        return self._cubes

    @property
    def views(self) -> ViewService:
        """Views: the view service, on this client's session."""
        return self._views

    @property
    def cellsets(self) -> CellsetService:
        """Cellsets: the cellset service, on this client's session."""
        return self._cellsets

    @property
    def cells(self) -> CellService:
        """Cell writes: the cell service, on this client's session."""
        return self._cells

    @property
    def member_sets(self) -> MemberSetService:
        """Member sets: the member set service, on this client's session."""
        return self._member_sets

    @property
    def sandboxes(self) -> SandboxService:
        """Sandboxes: the sandbox service, on this client's session."""
        return self._sandboxes

    @property
    def change_sets(self) -> ChangeSetService:
        """Change sets: the change set service, on this client's session."""
        return self._change_sets

    @property
    def drillthrough(self) -> DrillthroughService:
        """Drillthrough: the drillthrough service, on this client's session."""
        return self._drillthrough

    @property
    def processes(self) -> ProcessService:
        """Processes: the process service, on this client's session."""
        return self._processes

    @property
    def process_debugging(self) -> ProcessDebugService:
        """Process debugging: the process debug service, on this client's session."""
        return self._process_debugging

    @property
    def chores(self) -> ChoreService:
        """Chores: the chore service, on this client's session."""
        return self._chores

    @property
    def users(self) -> UserService:
        """Users: the user service, on this client's session."""
        return self._users

    @property
    def groups(self) -> GroupService:
        """Groups: the group service, on this client's session."""
        return self._groups

    @property
    def sessions(self) -> SessionService:
        """Server sessions: the session service, on this client's session."""
        return self._sessions

    @property
    def threads(self) -> ThreadService:
        """Server threads: the thread service, on this client's session."""
        return self._threads

    @property
    def server(self) -> ServerService:
        """The server: the server service, on this client's session."""
        return self._server

    @property
    def logs(self) -> LogService:
        """The server's logs: the log service, on this client's session."""
        return self._logs

    @property
    def contents(self) -> ContentService:
        """The contents tree: the content service, on this client's session."""
        return self._contents

    @property
    def annotations(self) -> AnnotationService:
        """Annotations on cube cells: the annotation service, on this client's session."""
        return self._annotations

    @property
    def git(self) -> GitService:
        """TM1 Git: the Git service, on this client's session."""
        return self._git

    @property
    def encryption(self) -> EncryptionService:
        """Encryption at rest: the encryption service, on this client's session."""
        return self._encryption

    @property
    def oidc_keys(self) -> OidcKeyService:
        """The cached OpenID Connect keys: the OIDC key service, on this client's session."""
        return self._oidc_keys

    @property
    def sql_data_sources(self) -> SqlDataSourceService:
        """The ODBC data sources on TM1's host: the SQL data source service, on this client's
        session."""
        return self._sql_data_sources

    # Views of the client

    def batch(self) -> AsyncTm1Batch:
        """A JSON ``$batch``: calls through ``batch.client`` are queued, and ``run`` sends them."""
        return AsyncTm1Batch(self._session.batch())

    def with_async(self, every: float | timedelta = 1.0) -> AsyncTm1Client:
        """A client whose requests TM1 runs asynchronously, polled every ``every`` seconds."""
        return AsyncTm1Client(self._session.with_async(every))

    def without_async(self) -> AsyncTm1Client:
        """A client on a synchronous view of this client's session
        (:meth:`AsyncTm1Session.without_async`)."""
        return AsyncTm1Client(self._session.without_async())

    def with_sandbox(self, name: str) -> AsyncTm1Client:
        """A client whose requests run in sandbox ``name``."""
        return AsyncTm1Client(self._session.with_sandbox(name))

    def without_sandbox(self) -> AsyncTm1Client:
        """A client on a view of this client's session whose requests run outside any sandbox, in
        base data (:meth:`AsyncTm1Session.without_sandbox`)."""
        return AsyncTm1Client(self._session.without_sandbox())

    def with_change_set(self, change_set_id: str) -> AsyncTm1Client:
        """A client whose writes are recorded in change set ``change_set_id``."""
        return AsyncTm1Client(self._session.with_change_set(change_set_id))

    def without_change_set(self) -> AsyncTm1Client:
        """A client on a view of this client's session whose writes go into no change set
        (:meth:`AsyncTm1Session.without_change_set`)."""
        return AsyncTm1Client(self._session.without_change_set())

    # Lifecycle

    async def connect(self) -> AsyncTm1Client:
        """Logs in now, rather than on the first request; returns this client."""
        await self._session.login()
        return self

    async def close(self) -> None:
        """Ends the session on the server."""
        await self._session.close()

    async def __aenter__(self) -> AsyncTm1Client:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    def __repr__(self) -> str:
        return f"AsyncTm1Client[{self._session.service_root}]"


class AsyncTm1Batch:
    """A JSON ``$batch`` of client calls, as the Java client's ``Tm1Batch``::

        async with tm1.batch() as batch:
            a = await batch.client.cubes.get("A")
            b = await batch.client.cubes.get("B")
        print(a.result().name, b.result().name)

    Each call returns a :class:`~skipcheck_tm1._protocol.Deferred`, its result there once the
    batch has run: its own value, or the error TM1 answered it with. TM1 (11.8) runs the calls in
    the order they were made, so a later call sees an earlier one's writes
    (:class:`AsyncTm1SessionBatch`).
    """

    def __init__(self, batch: AsyncTm1SessionBatch):
        self._batch = batch
        self._client = AsyncTm1Client(batch.session)

    @property
    def client(self) -> AsyncTm1Client:
        """The client whose calls join this batch."""
        return self._client

    def size(self) -> int:
        """How many requests have joined."""
        return self._batch.size()

    def __len__(self) -> int:
        return self.size()

    async def run(self, per_request: int = AsyncTm1SessionBatch.DEFAULT_PER_REQUEST) -> None:
        """Sends the queued calls, at most ``per_request`` to each ``$batch``."""
        await self._batch.run(per_request)

    async def __aenter__(self) -> AsyncTm1Batch:
        return self

    async def __aexit__(self, exc_type: object, *exc: object) -> None:
        if exc_type is None and not self._batch._queue.ran:
            await self.run()


class AsyncTm1ClientBuilder(AsyncTm1SessionBuilder):
    """The session's settings, as the Java client's ``Tm1Client.Builder``; :meth:`build`
    makes the client."""

    def build(self) -> AsyncTm1Client:  # type: ignore[override]
        """The client. Nothing is sent to TM1 until the first request."""
        return AsyncTm1Client(AsyncTm1Session(**self._settings))

    def build_session(self) -> AsyncTm1Session:
        """The session alone, with these settings. Nothing is sent to TM1 until the first request."""
        return AsyncTm1Session(**self._settings)
