"""Services for the Applications contents tree: folders, documents, links and references (the
Java client's ``net.ajmyers.tm1.client.service.content``)."""

from __future__ import annotations

import enum
from typing import Any

from ..._calls import endpoint
from ..._generated.model import Document, Entry, Folder, LocalizedAttributes
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ._base import Service, _require

__all__ = ["ContentPath", "ContentScope", "ContentService"]


class ContentScope(enum.Enum):
    """Who sees an entry, and so which of a folder's collections holds it."""

    PUBLIC = "Contents"
    """Everyone: ``…/Contents('id')``."""
    PRIVATE = "PrivateContents"
    """Only the user who made it: ``…/PrivateContents('id')``."""

    def navigation(self) -> str:
        """The folder's navigation property holding entries of this scope, such as ``PrivateContents``."""
        return self.value


class ContentPath:
    """Where an entry is in the contents tree: a root folder, then each folder's child by ID::

        # Children by ID, which for most entries is the name plus a suffix for the kind
        notes = ContentPath.applications().child("Planning").child("notes.txt.blob")
        # Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')

    Children are found by their ``ID``, not their ``Name``, though ``$metadata`` keys entries by
    ``Name``: TM1 (11.8) gives most entries an ID of the name plus a suffix for its kind, such
    as ``notes.txt.blob`` for a document, ``ibm.extr`` for a link and ``}ClientGroups.cube``
    for a cube reference. A folder's ID is its name. The ID is in the entry TM1 returns when
    it's made. ``$metadata`` deprecates ``Entry.ID`` in schema 11.5.0, but TM1 11.8 still keys
    by it. Paths are immutable.
    """

    __slots__ = ("_steps",)

    def __init__(self, steps: tuple[tuple[str, ContentScope], ...]):
        self._steps = steps

    @staticmethod
    def root(id: str) -> ContentPath:
        """A root folder: ``Contents('id')``, such as ``Applications``."""
        return ContentPath(((_require(id, "id"), ContentScope.PUBLIC),))

    @staticmethod
    def applications() -> ContentPath:
        """The ``Applications`` folder, where applications are built."""
        return ContentPath.root("Applications")

    @staticmethod
    def blobs() -> ContentPath:
        """The ``Blobs`` folder, holding the documents behind the document references in applications."""
        return ContentPath.root("Blobs")

    def child(
        self,
        id: str | Entry | None = None,
        entry: Entry | ContentScope | None = None,
        scope: ContentScope = ContentScope.PUBLIC,
    ) -> ContentPath:
        """A child of this folder: by its ID (``child("notes.txt.blob")``), or as TM1 returned
        it (``child(entry)``, such as from ``ContentService.create``: found by its ``ID``, which
        ``$metadata`` deprecates but TM1 (11.8) still keys entries by). ``scope`` says which of
        the folder's collections holds it (``child("x", ContentScope.PRIVATE)`` works too).
        A ``ValueError`` if an entry has no ID (it was selected away)."""
        if isinstance(entry, enum.Enum):
            scope, entry = entry, None
        given = entry if entry is not None else id
        if isinstance(given, Entry):
            if given.id is None:
                raise ValueError(f"The entry has no ID to find it by: {given!r}")
            child_id = given.id
        else:
            child_id = _require(given, "id")
        return ContentPath(self._steps + ((child_id, _scope(_require(scope, "scope"))),))

    def in_blobs(self) -> bool:
        """Whether this is in the ``Blobs`` folder, where documents themselves are."""
        return self._steps[0][0] == "Blobs"

    def path(self) -> ODataPath:
        """The OData path to the entry, such as ``Contents('Applications')/Contents('Planning')``."""
        path = ODataPath.of("Contents").key(self._steps[0][0])
        for id, scope in self._steps[1:]:
            path = path.nav(scope.navigation()).key(id)
        return path

    def __str__(self) -> str:
        """The IDs from the root, such as ``Applications/Planning/notes.txt.blob``."""
        return "/".join(("(private) " if scope is ContentScope.PRIVATE else "") + id for id, scope in self._steps)

    def __repr__(self) -> str:
        return f"ContentPath({str(self)!r})"

    def __eq__(self, other: object) -> bool:
        # Either flavor's path: the sync one is generated from this code
        return type(other).__name__ == "ContentPath" and getattr(other, "_steps", None) == self._steps

    def __hash__(self) -> int:
        return hash(self._steps)


class ContentService(Service):
    """The contents tree, where Planning Analytics keeps applications: folders, documents,
    links, and references to cubes, dimensions, subsets, views, processes and chores. Entries
    are addressed by :class:`ContentPath`.

    What TM1 (11.8) does here that you might not expect:

    * Entries are found by ``ID``, not ``Name`` (see :class:`ContentPath`).
    * A document made in an application becomes a ``DocumentReference`` there, to a
      ``Document`` TM1 makes in ``Blobs`` with a timestamp in its name, such as
      ``notes_20261122125939.txt``. Its content goes through the reference (:meth:`content`,
      :meth:`upload`). A copy of the reference refers to the same document; deleting a
      reference leaves the document while another refers to it.
    * Entries can't be changed: TM1 refuses a PATCH ("PATCH not supported on this resource"),
      so there's no update. To rename one, copy it with a new name and delete the original.

    Build an application: a folder with a link and a reference to a cube::

        planning = ContentPath.applications().child("Planning")
        await tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name("Planning").build())
        await tm1.contents.create(planning, ContentScope.PUBLIC, LinkPatch.builder().name("Guide").url("https://example.com/planning-guide").build())
        await tm1.contents.create(planning, ContentScope.PUBLIC, CubeReferencePatch.builder().name("Sales").bind_cube(ODataPath.of("Cubes").key("Sales")).build())

    Upload a document: made in an application, it's a reference to one TM1 keeps in Blobs,
    and the reference, as ``create`` returns it, is where its content goes::

        reference = await tm1.contents.create(planning, ContentScope.PUBLIC, DocumentPatch.builder().name("notes.txt").build())
        await tm1.contents.upload(planning.child(reference), data)

    Download a document's content::

        data = await tm1.contents.content(ContentPath.applications().child("Planning").child("notes.txt.blob"))

    List the cubes an application refers to, by a cast expand::

        entries = await tm1.contents.contents(planning, ContentScope.PUBLIC, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))
        cubes = [e.cube.name for e in entries if isinstance(e, CubeReference) and e.cube]
    """

    @endpoint("EntitySet Contents")
    async def roots(self, query: ODataQuery | None = None, **options: Any) -> list[Entry]:
        """``GET Contents``: the root folders, ``Applications`` and ``Blobs``."""
        return await self._session.collect(Tm1Request.list(ODataPath.of("Contents"), ODataQuery.of(query, **options), Entry))

    @endpoint("EntitySet Contents")
    async def get(self, entry: ContentPath, query: ODataQuery | None = None, **options: Any) -> Entry:
        """``GET`` of one entry: of its own kind (``Folder``, ``Link``, ``CubeReference`` …), or
        a ``Tm1NotFoundError`` if there's none. A reference's object comes by a cast expand,
        such as ``expand="tm1.CubeReference/Cube"``."""
        return await self._session.execute(Tm1Request.get(_path(entry), ODataQuery.of(query, **options), Entry))

    @endpoint(
        "Navigation Folder/Contents",
        "Navigation Folder/PrivateContents",
        "Navigation ChoreReference/Chore",
        "Navigation CubeReference/Cube",
        "Navigation DimensionReference/Dimension",
        "Navigation ProcessReference/Process",
        "Navigation SubsetReference/Subset",
        "Navigation ViewReference/View",
    )
    async def contents(
        self,
        folder: ContentPath,
        scope: ContentScope = ContentScope.PUBLIC,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> list[Entry]:
        """A folder's public or private entries, each of its own kind. References' objects
        come by a cast expand, such as ``expand="tm1.CubeReference/Cube($select=Name)"``."""
        path = _path(folder).nav(_scope(_require(scope, "scope")).navigation())
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Entry))

    @endpoint("Navigation Folder/Contents", "Navigation Folder/PrivateContents")
    async def create(self, folder: ContentPath, scope: ContentScope, entry: Any) -> Entry:
        """Makes an entry in a folder: a ``FolderPatch``, ``DocumentPatch``, ``LinkPatch`` or a
        reference, such as a ``CubeReferencePatch`` with ``bind_cube(ODataPath.of("Cubes").key("Sales"))``.
        A document made outside ``Blobs`` comes back as a ``DocumentReference``, with an empty
        document behind it; :meth:`upload` gives it content. Returns the entry as made, with the
        ``ID`` to find it by."""
        path = _path(folder).nav(_scope(_require(scope, "scope")).navigation())
        return await self._session.execute(Tm1Request.post(path, _require(entry, "entry"), Entry))

    @endpoint("EntitySet Contents")
    async def delete(self, entry: ContentPath) -> None:
        """``DELETE`` of an entry, and everything in it if it's a folder."""
        await self._session.execute(Tm1Request.delete(_path(entry)))

    @endpoint("Navigation Entry/ContainedBy")
    async def contained_by(self, entry: ContentPath, query: ODataQuery | None = None, **options: Any) -> Folder:
        """``ContainedBy``: the folder an entry is in."""
        request = Tm1Request.get(_path(entry).nav("ContainedBy"), ODataQuery.of(query, **options), Folder)
        return await self._session.execute(request)

    @endpoint("Navigation Entry/LocalizedAttributes")
    async def localized_attributes(
        self, entry: ContentPath, query: ODataQuery | None = None, **options: Any
    ) -> list[LocalizedAttributes]:
        """``LocalizedAttributes``: an entry's attributes in each locale."""
        path = _path(entry).nav("LocalizedAttributes")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), LocalizedAttributes))

    @endpoint("Action Entry/Copy")
    async def copy(
        self, entry: ContentPath, target: ContentPath, scope: ContentScope, name: str | None = None
    ) -> Entry:
        """``tm1.Copy``: copies an entry into the folder ``target``, public or private, named
        ``name`` or as the entry is. A copied document reference refers to the same document."""
        return await self._relocate(Tm1Operations.ENTRY_COPY, entry, target, scope, name)

    @endpoint("Action Entry/Move")
    async def move(
        self, entry: ContentPath, target: ContentPath, scope: ContentScope, name: str | None = None
    ) -> Entry:
        """``tm1.Move``: moves an entry into the folder ``target``, public or private there,
        renamed ``name`` if given; returns the entry where it is now."""
        return await self._relocate(Tm1Operations.ENTRY_MOVE, entry, target, scope, name)

    @endpoint("Navigation DocumentReference/Document")
    async def document(self, reference: ContentPath, query: ODataQuery | None = None, **options: Any) -> Document:
        """``Document``: the document a reference refers to, in ``Blobs``, with its ``Size`` and
        ``LastUpdated``."""
        return await self._session.execute(Tm1Request.get(_document(reference), ODataQuery.of(query, **options), Document))

    @endpoint("Navigation DocumentReference/Document")
    async def content(self, document: ContentPath) -> bytes:
        """A document's ``Content``, as bytes. The path is a document reference, or a document in
        ``Blobs``."""
        return await self._session.execute(Tm1Request.bytes(_document(document).nav("Content")))

    @endpoint("Navigation DocumentReference/Document")
    async def upload(self, document: ContentPath, content: bytes) -> None:
        """Replaces a document's ``Content``. The path is a document reference, or a document in
        ``Blobs``. TM1 (11.8) refuses a chunked upload, so the content is sent whole, with its
        length."""
        path = _document(document).nav("Content")
        await self._session.execute(Tm1Request.put(path, bytes(_require(content, "content"))))

    async def _relocate(
        self, operation: Tm1Operation, entry: ContentPath, target: ContentPath, scope: ContentScope, name: str | None
    ) -> Entry:
        body: dict[str, Any] = {
            "Target@odata.bind": str(_path(target)),
            "Private": _scope(_require(scope, "scope")) is ContentScope.PRIVATE,
        }
        if name is not None:
            body["Name"] = name
        request = Tm1Request.post(_path(entry).action(operation.name), body, Entry)
        return await self._session.execute(request.requires(operation.capability()))


def _scope(scope: Any) -> ContentScope:
    """A scope of either flavor (the sync ``ContentScope`` is generated from this one), as this
    module's: compared by value."""
    return ContentScope(scope.value)


def _path(entry: ContentPath) -> ODataPath:
    return _require(entry, "entry").path()


def _document(entry: ContentPath) -> ODataPath:
    return (
        _path(entry).cast("tm1.Document")
        if entry.in_blobs()
        else _path(entry).cast("tm1.DocumentReference").nav("Document")
    )
