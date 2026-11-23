# SPDX-License-Identifier: Apache-2.0

"""How a session logs in: the ``Authorization`` header of its first request.

TM1 answers a login with a ``TM1SessionId`` cookie; later requests send only the cookie.
"""

from __future__ import annotations

import base64
import inspect
from collections.abc import Callable
from dataclasses import dataclass, field
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
    show.

    A session pool keeps one session for each set of a user's own credentials, and tells them
    apart with ``==``. The authenticators here that hold their credentials (:func:`basic`,
    :func:`cam_namespace`, :func:`cam_passport` given a passport, :func:`cognos_logon`) are
    equal when the credentials are. One that asks a function (:func:`cam_passport` given one,
    :func:`cognos_id_token`) is only equal to itself, so keep it and pass the same one each time;
    so is your own, unless it defines ``__eq__``."""

    def authorization(self) -> str:
        """The ``Authorization`` header to log in with; called at each login. An asyncio session
        awaits it no longer than its login timeout (``Tm1Timeouts.login``); a sync session waits
        until it returns, so give anything it asks a timeout of its own."""
        ...

    def describe(self) -> str:
        """Who logs in, without secrets, for logs and errors."""
        ...


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


@dataclass(frozen=True, repr=False)
class _Fixed:
    """Credentials it holds. Equal to another when the credentials are, whatever the description."""

    value: str
    description: str = field(compare=False)

    def authorization(self) -> str:
        return self.value

    def describe(self) -> str:
        return self.description

    def __repr__(self) -> str:
        return f"Authenticator[{self.description}]"


class _Passport:
    """``CAMPassport <passport>``, the passport asked for at each login: from a function, or a
    coroutine function in the asyncio flavor. Equal only to itself, unless it says what it was
    made from (the ones that ask Cognos do): then to another made from the same."""

    def __init__(self, passport: Callable[[], Any], description: str = "CAMPassport", made_from: tuple[Any, ...] | None = None):
        self.passport = passport
        self.description = description
        self.made_from = made_from

    def __eq__(self, other: object) -> bool:
        return self is other or (isinstance(other, _Passport) and self.made_from is not None and self.made_from == other.made_from)

    def __hash__(self) -> int:
        return object.__hash__(self) if self.made_from is None else hash(self.made_from)

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


def cam_passport(passport: str | Callable[[], Any]) -> Authenticator:
    """CAM security with a passport: ``CAMPassport <passport>``. Java's two ``camPassport``
    overloads, told apart by what's given.

    Given a function, the passport is fetched from it at each login (a passport expires), so a
    session that logs in again uses a fresh one. Such an authenticator is equal only to itself.

    Given the passport itself, one the application already holds, such as the one a signed-in
    user's request carries, it isn't renewed: once it expires, a login with it is refused. Two of
    these are equal when their passports are, so a session pool gives a user's renewed passport a
    session of its own."""
    if passport is None:
        raise TypeError("passport")
    if isinstance(passport, str):
        return _Fixed("CAMPassport " + passport, "CAMPassport")
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
