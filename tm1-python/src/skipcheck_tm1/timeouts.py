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
    than trying the same credentials again; 0 to try every time)."""

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
        return Tm1Timeouts.Builder(Tm1Timeouts.DEFAULTS)

    def to_builder(self) -> Tm1Timeouts.Builder:
        return Tm1Timeouts.Builder(self)

    class Builder:
        """Java's ``Tm1Timeouts.Builder``: each method sets one, ``build()`` checks them."""

        def __init__(self, start: Tm1Timeouts):
            self._values = {f: getattr(start, f) for f in ("connect", "login", "request", "close", "refused_login")}

        def connect(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            self._values["connect"] = value
            return self

        def login(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            self._values["login"] = value
            return self

        def request(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            self._values["request"] = value
            return self

        def close(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            self._values["close"] = value
            return self

        def refused_login(self, value: float | timedelta) -> Tm1Timeouts.Builder:
            self._values["refused_login"] = value
            return self

        def build(self) -> Tm1Timeouts:
            return Tm1Timeouts(**self._values)

    def with_request(self, seconds: float | timedelta) -> Tm1Timeouts:
        return replace(self, request=seconds)


Tm1Timeouts.DEFAULTS = Tm1Timeouts()
