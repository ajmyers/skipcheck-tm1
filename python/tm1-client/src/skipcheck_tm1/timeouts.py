# SPDX-License-Identifier: Apache-2.0

"""How long a session waits, as the Java client's ``Tm1Timeouts``."""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import timedelta
from typing import ClassVar

__all__ = ["Tm1Timeouts"]


def _seconds(value: float | timedelta) -> float:
    return value.total_seconds() if isinstance(value, timedelta) else float(value)


@dataclass(frozen=True)
class Tm1Timeouts:
    """Seconds to wait: to connect, for a login, for a request's answer, for the close at the
    end, and how long a refused login is remembered (requests meanwhile fail at once rather
    than trying the same credentials again; 0 to try every time).

    ``login`` is how long to wait for a login response. The authenticator gets as long again to
    supply the credentials first, since it may ask another server, such as Cognos for a passport:
    one that doesn't in that time fails the request with a
    :class:`~skipcheck_tm1.errors.Tm1TransportError` in the ``CONNECT`` phase, which is safe to
    retry, as nothing was sent to TM1. That holds for the asyncio session and an authenticator it
    awaits. The sync session can't give up on an authenticator, which is a call that blocks, so one
    of your own must set its own timeout there; the ones that ask Cognos are bounded by their HTTP
    client's timeouts in both flavors."""

    connect: float = 10.0
    login: float = 30.0
    request: float = 600.0
    close: float = 5.0
    refused_login: float = 30.0

    DEFAULTS: ClassVar[Tm1Timeouts]

    def __post_init__(self) -> None:
        for name in ("connect", "login", "request", "close"):
            value = _seconds(getattr(self, name))
            if value <= 0:
                raise ValueError(f"{name} must be positive: {value}")
            object.__setattr__(self, name, value)
        refused = _seconds(self.refused_login)
        if refused < 0:
            raise ValueError(f"refused_login must not be negative: {refused}")
        object.__setattr__(self, "refused_login", refused)

    @staticmethod
    def builder() -> Tm1Timeouts.Builder:
        """A builder starting from the defaults."""
        return Tm1Timeouts.Builder(Tm1Timeouts.DEFAULTS)

    def to_builder(self) -> Tm1Timeouts.Builder:
        """A builder starting from these timeouts, to change some of them."""
        return Tm1Timeouts.Builder(self)

    class Builder:
        """Java's ``Tm1Timeouts.Builder``: each method sets one, ``build()`` checks them."""

        def __init__(self, start: Tm1Timeouts):
            self._values = {f: getattr(start, f) for f in ("connect", "login", "request", "close", "refused_login")}

        def connect(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            """Sets the connect timeout: how long to wait for a connection, including TLS."""
            self._values["connect"] = value
            return self

        def login(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            """Sets the login timeout: how long to wait for a login response, and, before that, for
            the authenticator to supply the credentials."""
            self._values["login"] = value
            return self

        def request(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            """Sets the request idle timeout: how long a request may go without receiving any data."""
            self._values["request"] = value
            return self

        def close(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            """Sets the close timeout: how long to wait for TM1 to close the session."""
            self._values["close"] = value
            return self

        def refused_login(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            """Sets how long a refused login is remembered: how long requests fail at once after TM1
            refused a login; zero to log in again straight away."""
            self._values["refused_login"] = value
            return self

        def build(self) -> Tm1Timeouts:
            """The timeouts, with unset values at the defaults."""
            return Tm1Timeouts(**self._values)

    def with_request(self, seconds: float | timedelta) -> Tm1Timeouts:
        """A copy with this request idle timeout instead."""
        return replace(self, request=seconds)


Tm1Timeouts.DEFAULTS = Tm1Timeouts()
