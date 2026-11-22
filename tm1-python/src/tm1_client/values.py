"""Value types of the services, shared by the sync and asyncio flavors (so a ``ContentScope``
is the same type in both): the Java client's ``ViewScope``, ``SubsetScope``, ``ContentScope``,
``ContentPath``, ``Tuples``, ``AttributeOwner``, ``AttributeSet``, ``GitCredentials`` and
``GitCommitDetails``. The service modules re-export them."""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

from ._generated.model import Entry
from .odata import ODataPath

def _navigation(scope: Any) -> str:
    """A scope's navigation property: a ``SubsetScope`` or ``ViewScope``, or its name."""
    navigation = getattr(scope, "navigation", None)
    return navigation() if callable(navigation) else str(_require(scope, "scope"))

def _hierarchy(dimension: str, hierarchy: str) -> ODataPath:
    return (
        ODataPath.of("Dimensions")
        .key(_require(dimension, "dimension"))
        .nav("Hierarchies")
        .key(_require(hierarchy, "hierarchy"))
    )

def _scope(scope: Any) -> ContentScope:
    """A scope of either flavor (the sync ``ContentScope`` is generated from this one), as this
    module's: compared by value."""
    return ContentScope(scope.value)

def _put(parameters: dict[str, Any], name: str, value: Any) -> None:
    if value is not None:
        parameters[name] = value


def _require(value: Any, name: str) -> Any:
    if value is None:
        raise TypeError(f"{name} is required")
    return value


__all__ = ["AttributeOwner", "AttributeSet", "ContentPath", "ContentScope", "GitCommitDetails", "GitCredentials", "SubsetScope", "Tuples", "ViewScope"]


# From the Java client's service.dimension package
class SubsetScope(enum.Enum):
    """Who sees a subset, and so which of a hierarchy's collections holds it."""

    PUBLIC = "Subsets"
    """Everyone: ``Hierarchies('h')/Subsets``."""
    PRIVATE = "PrivateSubsets"
    """Only the user who made it: ``Hierarchies('h')/PrivateSubsets``."""
    SESSION = "SessionSubsets"
    """Only this session, gone when it ends: ``Hierarchies('h')/SessionSubsets``."""

    def navigation(self) -> str:
        """The hierarchy's navigation property holding subsets of this scope."""
        return self.value


# From the Java client's service.attribute package
class AttributeOwner:
    """An object whose attribute values can be translated: it has ``LocalizedAttributes``."""

    __slots__ = ("_path",)

    def __init__(self, path: ODataPath):
        self._path = path

    @staticmethod
    def dimension(dimension: str) -> AttributeOwner:
        """A dimension."""
        return AttributeOwner(ODataPath.of("Dimensions").key(_require(dimension, "dimension")))

    @staticmethod
    def hierarchy(dimension: str, hierarchy: str) -> AttributeOwner:
        """A hierarchy."""
        return AttributeOwner(_hierarchy(dimension, hierarchy))

    @staticmethod
    def element(dimension: str, hierarchy: str, element: str) -> AttributeOwner:
        """An element."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav("Elements").key(_require(element, "element")))

    @staticmethod
    def member(dimension: str, hierarchy: str, member: str) -> AttributeOwner:
        """A member. Its translations are its element's, and read only here."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav("Members").key(_require(member, "member")))

    @staticmethod
    def subset(dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> AttributeOwner:
        """A subset."""
        return AttributeOwner(_hierarchy(dimension, hierarchy).nav(_navigation(scope)).key(_require(subset, "subset")))

    @staticmethod
    def cube(cube: str) -> AttributeOwner:
        """A cube."""
        return AttributeOwner(ODataPath.of("Cubes").key(_require(cube, "cube")))

    @staticmethod
    def view(cube: str, scope: Any, view: str) -> AttributeOwner:
        """A view; ``scope`` is a ``ViewScope``."""
        path = ODataPath.of("Cubes").key(_require(cube, "cube")).nav(_navigation(scope)).key(_require(view, "view"))
        return AttributeOwner(path)

    @staticmethod
    def process(process: str) -> AttributeOwner:
        """A process."""
        return AttributeOwner(ODataPath.of("Processes").key(_require(process, "process")))

    @staticmethod
    def chore(chore: str) -> AttributeOwner:
        """A chore."""
        return AttributeOwner(ODataPath.of("Chores").key(_require(chore, "chore")))

    def path(self) -> ODataPath:
        return self._path

    def __eq__(self, other: object) -> bool:
        return isinstance(other, AttributeOwner) and other._path == self._path

    def __hash__(self) -> int:
        return hash(self._path)

    def __str__(self) -> str:
        return str(self._path)

    def __repr__(self) -> str:
        return f"AttributeOwner({self._path})"


# From the Java client's service.attribute package
class AttributeSet:
    """A set of attribute definitions: the attributes that one kind of object has, such as
    every element of a hierarchy."""

    __slots__ = ("_path",)

    def __init__(self, path: ODataPath):
        self._path = path

    @staticmethod
    def dimensions() -> AttributeSet:
        """Dimensions' attributes: ``/DimensionAttributes``."""
        return AttributeSet(ODataPath.of("DimensionAttributes"))

    @staticmethod
    def hierarchies(dimension: str) -> AttributeSet:
        """The attributes of a dimension's hierarchies: ``Dimensions('d')/HierarchyAttributes``."""
        return AttributeSet(ODataPath.of("Dimensions").key(_require(dimension, "dimension")).nav("HierarchyAttributes"))

    @staticmethod
    def elements(dimension: str, hierarchy: str) -> AttributeSet:
        """The attributes of a hierarchy's elements: ``…/Hierarchies('h')/ElementAttributes``."""
        return AttributeSet(_hierarchy(dimension, hierarchy).nav("ElementAttributes"))

    @staticmethod
    def subsets(dimension: str, hierarchy: str) -> AttributeSet:
        """The attributes of a hierarchy's subsets: ``…/Hierarchies('h')/SubsetAttributes``."""
        return AttributeSet(_hierarchy(dimension, hierarchy).nav("SubsetAttributes"))

    @staticmethod
    def cubes() -> AttributeSet:
        """Cubes' attributes: ``/CubeAttributes``."""
        return AttributeSet(ODataPath.of("CubeAttributes"))

    @staticmethod
    def views(cube: str) -> AttributeSet:
        """The attributes of a cube's views: ``Cubes('c')/ViewAttributes``. TM1 (11.8) refuses to
        create one (400, "no public views") until the cube has a public *native* view; an MDX
        view doesn't count."""
        return AttributeSet(ODataPath.of("Cubes").key(_require(cube, "cube")).nav("ViewAttributes"))

    @staticmethod
    def processes() -> AttributeSet:
        """Processes' attributes: ``/ProcessAttributes``."""
        return AttributeSet(ODataPath.of("ProcessAttributes"))

    @staticmethod
    def chores() -> AttributeSet:
        """Chores' attributes: ``/ChoreAttributes``."""
        return AttributeSet(ODataPath.of("ChoreAttributes"))

    def path(self) -> ODataPath:
        return self._path

    def __eq__(self, other: object) -> bool:
        return isinstance(other, AttributeSet) and other._path == self._path

    def __hash__(self) -> int:
        return hash(self._path)

    def __str__(self) -> str:
        return str(self._path)

    def __repr__(self) -> str:
        return f"AttributeSet({self._path})"


# From the Java client's service.content package
class ContentScope(enum.Enum):
    """Who sees an entry, and so which of a folder's collections holds it."""

    PUBLIC = "Contents"
    """Everyone: ``…/Contents('id')``."""
    PRIVATE = "PrivateContents"
    """Only the user who made it: ``…/PrivateContents('id')``."""

    def navigation(self) -> str:
        """The folder's navigation property holding entries of this scope, such as ``PrivateContents``."""
        return self.value


# From the Java client's service.content package
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


# From the Java client's service.cube package
class ViewScope(enum.Enum):
    """Who sees a view, and so which of a cube's collections holds it."""

    PUBLIC = "Views"
    """Everyone: ``Cubes('c')/Views``."""
    PRIVATE = "PrivateViews"
    """Only the user who made it: ``Cubes('c')/PrivateViews``."""

    def navigation(self) -> str:
        """The cube's navigation property holding views of this scope, such as ``PrivateViews``."""
        return self.value


# From the Java client's service.data package
class Tuples:
    """Cell coordinates, as TM1 binds them: one element path per dimension of the cube, in the
    cube's order::

        cell = {"Region": "North", "Measure": "Amount"}
        CubeUpdatePatch.builder().bind_tuple(*Tuples.of(cell)).value(42).build()
    """

    @staticmethod
    def of(tuple: Mapping[str, str]) -> list[ODataPath]:
        """The element paths for a cell: ``tuple`` maps dimension to element, for every dimension
        of the cube in its order; each element is in its dimension's same-named hierarchy. One
        path per element, such as ``Dimensions('Region')/Hierarchies('Region')/Elements('North')``."""
        return [Tuples.element(d, d, e) for d, e in _require(tuple, "tuple").items()]

    @staticmethod
    def element(dimension: str, hierarchy: str, element: str) -> ODataPath:
        """One element's path: ``Dimensions('d')/Hierarchies('h')/Elements('e')``."""
        return (
            ODataPath.of("Dimensions")
            .key(_require(dimension, "dimension"))
            .nav("Hierarchies")
            .key(_require(hierarchy, "hierarchy"))
            .nav("Elements")
            .key(_require(element, "element"))
        )


# From the Java client's service.git package
@dataclass(frozen=True)
class GitCommitDetails:
    """What a push's commit says, and who it's by."""

    message: str
    author: str
    email: str

    def __post_init__(self) -> None:
        for name in ("message", "author", "email"):
            _require(getattr(self, name), name)


# From the Java client's service.git package
class _Password:
    """``GitCredentials.password``: on the class, Java's factory ``password(username, password)``;
    on credentials, their password."""

    def __get__(self, obj: Any, cls: Any) -> Any:
        if obj is None:

            def password(username: str, password: str) -> GitCredentials:
                """A user name and password, or access token, for an HTTPS remote."""
                return GitCredentials(_require(username, "username"), _require(password, "password"))

            return password
        return obj._password


# From the Java client's service.git package
class GitCredentials:
    """How TM1 signs in to the Git remote: nothing (a public or ``git://`` remote), a user name
    and password or token (HTTPS), or an SSH key pair. TM1 doesn't keep them, so every call
    that reaches the remote takes them again. ``repr`` leaves the secrets out."""

    __slots__ = ("username", "_password", "public_key", "private_key", "passphrase")

    password = _Password()

    def __init__(
        self,
        username: str | None = None,
        password: str | None = None,
        public_key: str | None = None,
        private_key: str | None = None,
        passphrase: str | None = None,
    ):
        self.username = username
        self._password = password
        self.public_key = public_key
        self.private_key = private_key
        self.passphrase = passphrase

    @staticmethod
    def none() -> GitCredentials:
        """No credentials."""
        return GitCredentials()

    @staticmethod
    def ssh_key(public_key: str, private_key: str, passphrase: str | None = None) -> GitCredentials:
        """An SSH key pair, for an SSH remote, with its passphrase if any."""
        return GitCredentials(
            None, None, _require(public_key, "public_key"), _require(private_key, "private_key"), passphrase
        )

    def _add_to(self, parameters: dict[str, Any]) -> None:
        _put(parameters, "Username", self.username)
        _put(parameters, "Password", self._password)
        _put(parameters, "PublicKey", self.public_key)
        _put(parameters, "PrivateKey", self.private_key)
        _put(parameters, "Passphrase", self.passphrase)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, GitCredentials) and all(
            getattr(self, s) == getattr(other, s) for s in GitCredentials.__slots__
        )

    def __hash__(self) -> int:
        return hash(tuple(getattr(self, s) for s in GitCredentials.__slots__))

    def __repr__(self) -> str:
        """Which credentials these are, without the secrets."""
        return (
            f"GitCredentials[username={self.username}, password={'none' if self._password is None else '***'}, "
            f"sshKey={'none' if self.private_key is None else '***'}]"
        )

    __str__ = __repr__
