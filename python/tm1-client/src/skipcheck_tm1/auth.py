"""How a session logs in: the ``Authorization`` header of its first request.

TM1 answers a login with a ``TM1SessionId`` cookie; later requests send only the cookie.
"""

from __future__ import annotations

import base64
import inspect
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Protocol

if TYPE_CHECKING:
    # The sync flavor's, generated at build time: __getattr__ below imports it when it's asked for
    from ._sync.cognos import CognosPassports

__all__ = ["Authenticator", "CognosPassports", "basic", "cam_namespace", "cam_passport", "cognos_id_token", "cognos_logon"]


class Authenticator(Protocol):
    """Supplies the credentials for logging in to TM1: the value of the ``Authorization`` header of
    a login request. It's asked once per login, not once per session: a session logs in again when
    TM1 reports it expired, and credentials that expire themselves (a CAM passport) can be fresh
    each time. Only the login carries credentials; every other request carries the session cookie.
    Implementations must not log or otherwise reveal the credentials; :meth:`describe` is what logs
    show."""

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


class _Passport:
    """``CAMPassport <passport>``, the passport asked for at each login: from a function, or a
    coroutine function in the asyncio flavor."""

    def __init__(self, passport: Callable[[], Any], description: str = "CAMPassport"):
        self.passport = passport
        self.description = description

    def authorization(self) -> Any:
        value = self.passport()
        if inspect.isawaitable(value):

            async def prefixed() -> str:
                return "CAMPassport " + await value

            return prefixed()
        return "CAMPassport " + value

    def describe(self) -> str:
        return self.description

    def __repr__(self) -> str:
        return f"Authenticator[{self.description}]"


def basic(user: str, password: str | None = None) -> Authenticator:
    """TM1's own security (mode 1 or 2): ``Basic`` with ``user:password``."""
    return _Fixed("Basic " + _b64(f"{user}:{password or ''}"), f"Basic {user}")


def cam_namespace(user: str, password: str, namespace: str) -> Authenticator:
    """CAM security (mode 4 or 5) with a user in a namespace."""
    return _Fixed("CAMNamespace " + _b64(f"{user}:{password}:{namespace}"), f"CAMNamespace {namespace}/{user}")


def cam_passport(passport: Callable[[], Any]) -> Authenticator:
    """CAM security with a passport, fetched at each login (a passport expires)."""
    return _Passport(passport)


def cognos_logon(dispatcher: str, namespace: str, user: str, password: str, **options: Any) -> Authenticator:
    """CAM security with a passport the client gets from Cognos, by user name and password: a
    fresh one for each login; TM1 never sees the password. For the asyncio flavor, see
    :func:`skipcheck_tm1.aio.cognos.cognos_logon`."""
    from ._sync.cognos import cognos_logon as logon

    return logon(dispatcher, namespace, user, password, **options)


def cognos_id_token(dispatcher: str, namespace: str, id_token: Callable[[], str], **options: Any) -> Authenticator:
    """CAM security with a passport the client gets from Cognos for an OpenID Connect ID token."""
    from ._sync.cognos import cognos_id_token as exchange

    return exchange(dispatcher, namespace, id_token, **options)


def __getattr__(name: str) -> Any:
    # CognosPassports is the sync flavor's, generated from skipcheck_tm1.aio.cognos
    if name == "CognosPassports":
        from ._sync.cognos import CognosPassports

        return CognosPassports
    raise AttributeError(name)
