"""OData v4 encoding: literals, path segments, query options and ``@odata.bind``.

The rules match the Java client's ``net.ajmyers.tm1.client.odata`` package, so both send
the same requests for the same calls; ``tests/test_odata.py`` pins the cases they share.
"""

from __future__ import annotations

import enum
import math
import re
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Any, Iterable, Mapping, Sequence

__all__ = [
    "Alias",
    "Bind",
    "ODataPath",
    "ODataQuery",
    "Raw",
    "duration",
    "literal",
    "path_value",
    "query_value",
    "string_literal",
]

_UNRESERVED = frozenset(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
_PATH_SAFE = _UNRESERVED | frozenset(b"'")
_LINK_SAFE = _UNRESERVED | frozenset(b"'!()")
_QUERY_SAFE = _UNRESERVED | frozenset(b"$(),;':@/!*")

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
_QUALIFIED = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*\Z")


def _encode(text: str, safe: frozenset[int]) -> str:
    return "".join(chr(b) if b in safe else f"%{b:02X}" for b in text.encode("utf-8"))


def path_value(text: str) -> str:
    """Percent-encodes ``text`` for a path segment: only unreserved characters and ``'`` stay."""
    return _encode(text, _PATH_SAFE)


def query_value(text: str) -> str:
    """Percent-encodes ``text`` for a query option's value, keeping OData's own punctuation."""
    return _encode(text, _QUERY_SAFE)


def string_literal(value: str) -> str:
    """``O'Neil`` → ``'O''Neil'``."""
    return "'" + value.replace("'", "''") + "'"


@dataclass(frozen=True)
class Raw:
    """A preformed OData literal, sent as is: ``Raw("tm1.AttributeType'Alias'")``."""

    literal: str


def duration(value: timedelta) -> str:
    """A ``timedelta`` as ISO 8601 (``P1D``, ``PT1H30M``, ``PT0S``), which TM1 accepts."""
    micros = value // timedelta(microseconds=1)
    sign = "-" if micros < 0 else ""
    micros = abs(micros)
    days, micros = divmod(micros, 86_400_000_000)
    hours, micros = divmod(micros, 3_600_000_000)
    minutes, micros = divmod(micros, 60_000_000)
    seconds, micros = divmod(micros, 1_000_000)
    time_part = ""
    if hours:
        time_part += f"{hours}H"
    if minutes:
        time_part += f"{minutes}M"
    if seconds or micros:
        time_part += (f"{seconds}.{micros:06d}".rstrip("0") if micros else str(seconds)) + "S"
    if not days and not time_part:
        return "PT0S"
    return sign + "P" + (f"{days}D" if days else "") + ("T" + time_part if time_part else "")


def literal(value: Any) -> str:
    """A Python value as an OData literal, for keys, function parameters and filters.

    ``None`` → ``null``, ``str`` → quoted, ``bool`` → ``true``/``false``, numbers as plain
    decimals (``NaN``, ``INF``), enums as ``tm1.Type'Member'``, dates and times in ISO 8601,
    ``timedelta`` as ``duration'P…'``.
    """
    if value is None:
        return "null"
    if isinstance(value, Raw):
        return value.literal
    odata = getattr(value, "odata_literal", None)
    if callable(odata):
        return odata()
    if isinstance(value, enum.Enum):
        return literal(value.value)
    if isinstance(value, str):
        return string_literal(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, float):
        if math.isnan(value):
            return "NaN"
        if math.isinf(value):
            return "INF" if value > 0 else "-INF"
        return _plain(value)
    if isinstance(value, datetime):
        return value.isoformat().replace("+00:00", "Z")
    if isinstance(value, (date, time)):
        return value.isoformat()
    if isinstance(value, timedelta):
        return "duration'" + duration(value) + "'"
    if isinstance(value, uuid.UUID):
        return str(value)
    raise TypeError(f"No OData literal for {type(value).__name__}; pass odata.Raw for a preformed literal")


def _plain(value: float) -> str:
    text = format(Decimal(repr(value)).normalize(), "f")
    return text


def _identifier(name: str) -> str:
    if not isinstance(name, str) or not _IDENTIFIER.match(name):
        raise ValueError(f"Not an OData identifier: {name!r}. Key values go in key(), not in a segment name.")
    return name


def _qualified(name: str) -> str:
    if not isinstance(name, str) or not _QUALIFIED.match(name):
        raise ValueError(f"Not a qualified OData name: {name!r}")
    return name


def _key_value(value: Any) -> str:
    if isinstance(value, str):
        return "'" + path_value(value.replace("'", "''")) + "'"
    return path_value(literal(value))


@dataclass(frozen=True)
class ODataPath:
    """A resource path relative to the service root: ``Cubes('Sales')/Views('Default')``.

    Immutable; each method returns a longer path. Names are checked, key values encoded.
    """

    segments: tuple[str, ...] = ()

    @staticmethod
    def of(name: str) -> ODataPath:
        return ODataPath((_identifier(name),))

    @staticmethod
    def link(link: str) -> ODataPath:
        """A path TM1 sent (an ``@odata.id`` or a ``Location``), re-encoded segment by segment."""
        if not link or link.startswith("/") or "://" in link or "?" in link:
            raise ValueError(f"Not a relative path without a query: {link}")
        return ODataPath(tuple(_encode(s, _LINK_SAFE) for s in link.split("/")))

    @staticmethod
    def function_import(name: str, parameters: Mapping[str, Any] | None = None) -> ODataPath:
        return ODataPath((_call(_identifier(name), parameters or {}),))

    def key(self, value: Any) -> ODataPath:
        """``Cubes`` → ``Cubes('Sales')``; a mapping makes a compound key ``(A='x',B='y')``."""
        if isinstance(value, Mapping):
            if not value:
                raise ValueError("A compound key needs at least one part")
            inner = ",".join(f"{_identifier(k)}={_key_value(v)}" for k, v in value.items())
            return self._append_to_last(f"({inner})")
        return self._append_to_last(f"({_key_value(value)})")

    def nav(self, prop: str) -> ODataPath:
        return self._append(_identifier(prop))

    def cast(self, qualified_type: str) -> ODataPath:
        return self._append(_qualified(qualified_type))

    def action(self, qualified_name: str) -> ODataPath:
        return self._append(_qualified(qualified_name))

    def function(self, qualified_name: str, parameters: Mapping[str, Any] | None = None) -> ODataPath:
        return self._append(_call(_qualified(qualified_name), parameters or {}))

    def value(self) -> ODataPath:
        return self._append("$value")

    def count(self) -> ODataPath:
        return self._append("$count")

    def ref(self) -> ODataPath:
        return self._append("$ref")

    def __str__(self) -> str:
        return "/".join(self.segments)

    def __truediv__(self, prop: str) -> ODataPath:
        return self.nav(prop)

    def _append(self, segment: str) -> ODataPath:
        return ODataPath(self.segments + (segment,))

    def _append_to_last(self, suffix: str) -> ODataPath:
        if not self.segments:
            raise ValueError("A key follows a collection")
        last = self.segments[-1]
        if last.endswith(")") or last.startswith("$"):
            raise ValueError(f"Can't add a key to {last!r}: a key follows a collection, once")
        return ODataPath(self.segments[:-1] + (last + suffix,))


@dataclass(frozen=True)
class Alias:
    """A parameter alias, ``@p``, whose value goes in the query string (``?@p=…``): how a
    function takes a structured value, as JSON."""

    name: str

    def __post_init__(self) -> None:
        if not self.name.startswith("@") or not _IDENTIFIER.match(self.name[1:]):
            raise ValueError(f"A parameter alias is @ followed by an identifier: {self.name!r}")


def _call(function: str, parameters: Mapping[str, Any]) -> str:
    args = ",".join(
        f"{_identifier(k)}={v.name if isinstance(v, Alias) else path_value(literal(v))}" for k, v in parameters.items()
    )
    return f"{function}({args})"


class Bind:
    """A reference to an existing entity, sent as ``Name@odata.bind``.

    ``Cube(name="c", dimensions=[Bind("Dimensions('Region')"), Bind("Dimensions('Year')")])``

    Not a dataclass on purpose: models accept it by type only, so a payload TM1 sends can
    never be read as one.
    """

    __slots__ = ("path",)

    def __init__(self, path: str | ODataPath):
        self.path = path

    def __str__(self) -> str:
        return str(self.path)

    def __repr__(self) -> str:
        return f"Bind({str(self.path)!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Bind) and str(other) == str(self)

    def __hash__(self) -> int:
        return hash(str(self))


def _names(items: str | Iterable[str] | None) -> list[str]:
    if items is None:
        return []
    if isinstance(items, str):
        return [items]
    return list(items)


@dataclass(frozen=True)
class ODataQuery:
    """System query options: ``$select``, ``$expand``, ``$filter``, ``$orderby``, ``$top``,
    ``$skip``, ``$count`` and ``$search``, plus custom options (TM1's ``!sandbox``).

    ``ODataQuery(select=["Name"], filter="startswith(Name,'Sales')", top=10)``
    """

    select: Sequence[str] | str | None = None
    expand: Sequence[str] | str | None = None
    filter: str | None = None
    orderby: Sequence[str] | str | None = None
    top: int | None = None
    skip: int | None = None
    count: bool | None = None
    search: str | None = None
    custom: Mapping[str, str] = field(default_factory=dict)

    def params(self) -> list[tuple[str, str]]:
        """The options as ``(name, value)`` pairs, in OData's order."""
        out: list[tuple[str, str]] = []
        if self.select is not None:
            out.append(("$select", ",".join(_names(self.select))))
        if self.expand is not None:
            out.append(("$expand", ",".join(_names(self.expand))))
        if self.filter is not None:
            out.append(("$filter", self.filter))
        if self.orderby is not None:
            out.append(("$orderby", ",".join(_names(self.orderby))))
        if self.top is not None:
            out.append(("$top", str(self.top)))
        if self.skip is not None:
            out.append(("$skip", str(self.skip)))
        if self.count is not None:
            out.append(("$count", "true" if self.count else "false"))
        if self.search is not None:
            out.append(("$search", self.search))
        out.extend(self.custom.items())
        return out

    def query_string(self) -> str:
        """The encoded query string, without ``?``."""
        return "&".join(f"{name}={query_value(value)}" for name, value in self.params())


EMPTY = ODataQuery()
