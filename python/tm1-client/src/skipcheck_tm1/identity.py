# SPDX-License-Identifier: Apache-2.0

"""Who a session acts as, as the Java client's ``Tm1Identity``: the service account it logs in
with, a user it impersonates, or a user with credentials of their own. The session pool keeps
one session per identity."""

from __future__ import annotations

import enum
from typing import Any

from .auth import Authenticator

__all__ = ["Tm1Identity", "check_impersonated", "comparable_user"]


def check_impersonated(user: str) -> str:
    """``user``, if it can be impersonated: not blank, which TM1 would take as no impersonation and
    run as the logged-in user, and without control characters, which can't go in a header. Any
    other character is fine: TM1 (11.8) reads ``TM1-Impersonate`` as UTF-8, and the session sends
    it so."""
    if user is None:
        raise TypeError("user")
    if not user.strip():
        raise ValueError("The user to impersonate is blank, which TM1 would take as no impersonation at all")
    for c in user:
        # Java's Character.isISOControl: C0, DEL and C1
        if ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F:
            raise ValueError("The user to impersonate has a control character in its name, which can't be sent in a header")
    return user


def comparable_user(user: str) -> str:
    """A user name as TM1 compares them: ignoring case and spaces."""
    return user.replace(" ", "").lower()


class Tm1Identity:
    """Who a session acts as. Two identities are the same when they're of the same kind for the
    same user, as TM1 compares user names (ignoring case and spaces), so ``impersonating("Alice")``
    and ``impersonating("alice")`` share a session.

    For a user's own credentials, the credentials must be equal too. A name is only what the
    caller says, and it's TM1 that checks credentials, at login: if the name alone found the
    session, anyone giving a logged-in user's name would get that user's session without TM1 ever
    seeing what they offered, and a corrected password or a renewed passport would never be used.
    So different credentials for the same name get a session of their own, and TM1 accepts or
    refuses them. See :class:`~skipcheck_tm1.auth.Authenticator` for which authenticators are
    equal to one another."""

    class Kind(enum.Enum):
        """The kinds of identity."""

        SERVICE_ACCOUNT = "service-account"
        IMPERSONATING = "impersonating"
        OWN_CREDENTIALS = "own-credentials"

    __slots__ = ("_kind", "_name", "_comparable", "_authenticator")

    def __init__(self, kind: Tm1Identity.Kind, name: str, authenticator: Authenticator | None):
        self._kind = kind
        self._name = name
        self._comparable = comparable_user(name)
        self._authenticator = authenticator

    @staticmethod
    def service_account() -> Tm1Identity:
        """The account the session settings log in with."""
        return _SERVICE_ACCOUNT

    @staticmethod
    def impersonating(user: str) -> Tm1Identity:
        """The service account, acting as ``user`` (``TM1-Impersonate``). Refuses a blank name and
        one with a control character."""
        return Tm1Identity(Tm1Identity.Kind.IMPERSONATING, check_impersonated(user), None)

    @staticmethod
    def with_credentials(name: str, authenticator: Authenticator) -> Tm1Identity:
        """A user who logs in with credentials of their own. ``name`` is who they belong to, for
        the pool's key and for logs. Pass an authenticator that is equal each time, or the same
        object: one the pool hasn't seen for this name gets a new session."""
        if name is None or authenticator is None:
            raise TypeError("name and authenticator are required")
        if not name.strip():
            raise ValueError("An identity's name must not be blank")
        return Tm1Identity(Tm1Identity.Kind.OWN_CREDENTIALS, name, authenticator)

    @property
    def kind(self) -> Tm1Identity.Kind:
        """What kind of identity this is."""
        return self._kind

    @property
    def name(self) -> str:
        """The user's name; empty for the service account."""
        return self._name

    @property
    def authenticator(self) -> Authenticator | None:
        """The identity's own credentials, if it has them."""
        return self._authenticator

    def apply_to(self, settings: dict[str, Any]) -> dict[str, Any]:
        """Session settings for this identity, from the pool's (Java's ``applyTo``)."""
        out = dict(settings)
        if self._kind is Tm1Identity.Kind.IMPERSONATING:
            out["impersonate"] = self._name
        elif self._kind is Tm1Identity.Kind.OWN_CREDENTIALS:
            out.pop("impersonate", None)
            out["authenticator"] = self._authenticator
        return out

    def __eq__(self, other: object) -> bool:
        return self is other or (
            isinstance(other, Tm1Identity)
            and self._kind is other._kind
            and self._comparable == other._comparable
            and self._authenticator == other._authenticator
        )

    def __hash__(self) -> int:
        try:
            return hash((self._kind, self._comparable, self._authenticator))
        except TypeError:
            # An authenticator of the caller's own that defines == and no hash: equal ones still
            # hash alike by kind and name, which is all a hash has to do
            return hash((self._kind, self._comparable))

    def __repr__(self) -> str:
        if self._kind is Tm1Identity.Kind.SERVICE_ACCOUNT:
            return "Tm1Identity[service account]"
        if self._kind is Tm1Identity.Kind.IMPERSONATING:
            return f"Tm1Identity[impersonating {self._name}]"
        return f"Tm1Identity[{self._name}]"


_SERVICE_ACCOUNT = Tm1Identity(Tm1Identity.Kind.SERVICE_ACCOUNT, "", None)
