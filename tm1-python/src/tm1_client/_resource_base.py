"""What the sync and asyncio resources share: a path, a session, and what's refused."""

from __future__ import annotations

from typing import Any, ClassVar, Mapping, Sequence

from .base import Tm1Model
from .errors import Tm1UnsupportedOperationError
from .odata import ODataPath

REFERENCE = "it refers to entities kept elsewhere; change them through their own collection"


class _Base:
    """What the sync and asyncio resources share: a path, a session, and what's refused."""

    _forbidden: Mapping[str, str]

    def __init__(self, session: Any, path: ODataPath, forbid: Mapping[str, str] | None = None):
        self._session = session
        self._path = path
        self._forbidden = dict(forbid or {})

    @property
    def path(self) -> ODataPath:
        return self._path

    @property
    def session(self) -> Any:
        return self._session

    def __repr__(self) -> str:
        return f"{type(self).__name__}({str(self._path) or '/'})"

    def _check(self, request: str) -> None:
        reason = self._forbidden.get(request)
        if reason is not None:
            raise Tm1UnsupportedOperationError(f"{request} isn't offered for {self._path}: {reason}")


class _CollectionBase(_Base):
    model: ClassVar[type[Tm1Model]] = Tm1Model
    entity: ClassVar[type[Any]] = object
    key_names: ClassVar[Sequence[str]] = ()

    def __init__(self, session: Any, path: ODataPath, forbid: Mapping[str, str] | None = None, *, contained: bool = True):
        refused = dict(forbid or {})
        if not contained:
            refused.setdefault("create", REFERENCE)
            refused.setdefault("delete", REFERENCE)
        super().__init__(session, path, refused)

    def __getitem__(self, key: Any) -> Any:
        """The entity with ``key``: a value, or for a compound key a tuple or mapping."""
        if isinstance(key, tuple):
            if len(key) != len(self.key_names):
                raise ValueError(f"{self.model.__name__} has a key of {len(self.key_names)} parts: {self.key_names}")
            key = dict(zip(self.key_names, key))
        elif len(self.key_names) > 1 and not isinstance(key, Mapping):
            raise ValueError(f"{self.model.__name__} has a compound key: {self.key_names}")
        entity_forbid = {k: v for k, v in self._forbidden.items() if k in ("delete", "update")}
        return self.entity(self._session, self._path.key(key), entity_forbid)


