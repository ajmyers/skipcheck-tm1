"""How a session logs in: the ``Authorization`` header of its first request.

TM1 answers a login with a ``TM1SessionId`` cookie; later requests send only the cookie.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import Callable, Protocol

__all__ = ["Authenticator", "basic", "cam_namespace", "cam_passport"]


class Authenticator(Protocol):
    def authorization(self) -> str:
        """The ``Authorization`` header to log in with; called at each login."""
        ...

    def describe(self) -> str:
        """Who logs in, without secrets, for logs and errors."""
        ...


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


@dataclass(frozen=True, repr=False)
class _Fixed:
    value: str
    description: str

    def authorization(self) -> str:
        return self.value

    def describe(self) -> str:
        return self.description

    def __repr__(self) -> str:
        return f"Authenticator[{self.description}]"


@dataclass(frozen=True, repr=False)
class _Passport:
    passport: Callable[[], str]

    def authorization(self) -> str:
        return "CAMPassport " + self.passport()

    def describe(self) -> str:
        return "CAMPassport"

    def __repr__(self) -> str:
        return "Authenticator[CAMPassport]"


def basic(user: str, password: str | None = None) -> Authenticator:
    """TM1's own security (mode 1 or 2): ``Basic`` with ``user:password``."""
    return _Fixed("Basic " + _b64(f"{user}:{password or ''}"), f"Basic {user}")


def cam_namespace(user: str, password: str, namespace: str) -> Authenticator:
    """CAM security (mode 4 or 5) with a user in a namespace."""
    return _Fixed("CAMNamespace " + _b64(f"{user}:{password}:{namespace}"), f"CAMNamespace {namespace}/{user}")


def cam_passport(passport: Callable[[], str]) -> Authenticator:
    """CAM security with a passport, fetched at each login (a passport expires)."""
    return _Passport(passport)
