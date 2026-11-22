"""What every service is: a set of methods over one session, as the Java client's services."""

from __future__ import annotations

from typing import Any

from ..session import AsyncTm1Session

__all__ = ["Service"]


class Service:
    """A service sending its requests through ``session``. Services hold nothing else, so a
    client over a view of the session (a sandbox, a batch) makes services over that view."""

    def __init__(self, session: AsyncTm1Session):
        if session is None:
            raise TypeError("session")
        self._session = session

    @property
    def session(self) -> AsyncTm1Session:
        return self._session

    def __repr__(self) -> str:
        return f"{type(self).__name__}({self._session.service_root})"


def _require(value: Any, name: str) -> Any:
    """Java's ``Objects.requireNonNull``: a clear error for a missing argument."""
    if value is None:
        raise TypeError(f"{name} is required")
    return value
