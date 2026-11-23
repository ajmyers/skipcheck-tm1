"""OData v4 encoding: literals, path segments, query options and ``@odata.bind``.

The rules match the Java client's ``io.skipcheck.tm1.client.odata`` package, so both send
the same requests for the same calls; ``tests/test_odata.py`` pins the cases they share.
"""

from __future__ import annotations

import enum
import math
import re
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Any

__all__ = [
    "Alias",
    "Bind",
    "Filter",
    "ODataEncoding",
    "ODataJson",
    "ODataLiterals",
    "ODataPath",
    "ODataProperty",
    "ODataQuery",
    "Raw",
    "element",
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
    """A ``timedelta`` in ISO 8601 as the Java client writes it (``Duration.toString``), which TM1
    accepts: hours, minutes and seconds, never days (``PT26H3.5S``, ``PT1H30M``, ``PT0S``)."""
    micros = value // timedelta(microseconds=1)
    if micros == 0:
        return "PT0S"
    # As Java's Duration: whole seconds (rounded down) and a positive fraction
    seconds, fraction = divmod(micros, 1_000_000)
    total = seconds + 1 if seconds < 0 and fraction > 0 else seconds
    hours = int(total / 3600)
    minutes = int((total - hours * 3600) / 60)
    secs = total - hours * 3600 - minutes * 60
    out = "PT"
    if hours:
        out += f"{hours}H"
    if minutes:
        out += f"{minutes}M"
    if secs == 0 and fraction == 0 and len(out) > 2:
        return out
    out += ("-0" if secs == 0 else str(secs)) if seconds < 0 and fraction > 0 else str(secs)
    if fraction > 0:
        digits = f"{(2_000_000 - fraction) if seconds < 0 else (fraction + 1_000_000)}"[1:].rstrip("0")
        out += "." + digits
    return out + "S"


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
    def metadata() -> ODataPath:
        return ODataPath(("$metadata",))

    @staticmethod
    def batch() -> ODataPath:
        return ODataPath(("$batch",))

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
    args = ",".join(f"{_identifier(k)}={v.name if isinstance(v, Alias) else path_value(literal(v))}" for k, v in parameters.items())
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


def element(dimension: str, element: str, hierarchy: str | None = None) -> Bind:
    """A reference to an element, for a cell's ``Tuple`` or an operation's element parameter:
    ``Dimensions('d')/Hierarchies('h')/Elements('e')``, the hierarchy defaulting to the
    dimension's own."""
    path = ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(hierarchy or dimension)
    return Bind(path.nav("Elements").key(element))


def _names(items: Any) -> list[str]:
    if items is None:
        return []
    if isinstance(items, (str, ODataProperty)):
        return [str(items)]
    return [str(i) for i in items]


class ODataProperty:
    """A property path, for queries and filters: the generated models have one per property,
    ``Cube.NAME`` (as the Java client's ``ODataProperty``). Comparisons make filters::

        Cube.NAME.eq("Sales")     # (Name eq 'Sales')
    """

    __slots__ = ("_path",)

    def __init__(self, path: str):
        if not isinstance(path, str):
            raise TypeError("A property path is a string")
        self._path = path

    @staticmethod
    def of(path: str) -> ODataProperty:
        return ODataProperty(path)

    def path(self) -> str:
        return self._path

    def __str__(self) -> str:
        return self._path

    def __repr__(self) -> str:
        return f"ODataProperty({self._path!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, ODataProperty) and other._path == self._path

    def __hash__(self) -> int:
        return hash(self._path)

    def eq(self, value: Any) -> Filter:
        return Filter._compare(self, "eq", value)

    def ne(self, value: Any) -> Filter:
        return Filter._compare(self, "ne", value)

    def gt(self, value: Any) -> Filter:
        return Filter._compare(self, "gt", value)

    def ge(self, value: Any) -> Filter:
        return Filter._compare(self, "ge", value)

    def lt(self, value: Any) -> Filter:
        return Filter._compare(self, "lt", value)

    def le(self, value: Any) -> Filter:
        return Filter._compare(self, "le", value)

    def is_null(self) -> Filter:
        return Filter._compare(self, "eq", None)


def _property(p: str | ODataProperty) -> str:
    return p.path() if isinstance(p, ODataProperty) else p


class Filter:
    """A ``$filter`` expression, built from :class:`ODataProperty` comparisons and the
    canonical functions TM1 supports, as the Java client's ``Filter``::

        Filter.and_(Filter.starts_with(Element.NAME, "Q"), Element.TYPE.eq(ElementType.NUMERIC))
            # (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
        Filter.any("Hierarchies", "h", lambda h: h.nav("Name").eq("Region"))
            # Hierarchies/any(h:(h/Name eq 'Region'))

    Every binary expression is parenthesized, so precedence never depends on how a filter was
    composed. ``and``, ``or`` and ``not`` are Python keywords, so they're ``and_``, ``or_``
    and ``not_`` (and ``&``, ``|``, ``~``). TM1 compares names in ``$filter`` case- and
    space-sensitively, while keyed access ignores both: to find one object by name, use its key.
    """

    __slots__ = ("_expression",)

    def __init__(self, expression: str):
        self._expression = expression

    @staticmethod
    def raw(expression: str) -> Filter:
        """An expression written by hand, used as is."""
        if not isinstance(expression, str):
            raise TypeError("expression")
        return Filter(expression)

    @staticmethod
    def and_(*filters: Filter) -> Filter:
        return Filter._join("and", filters)

    @staticmethod
    def or_(*filters: Filter) -> Filter:
        return Filter._join("or", filters)

    @staticmethod
    def not_(filter: Filter) -> Filter:
        return Filter(f"not({filter._expression})")

    @staticmethod
    def starts_with(prop: str | ODataProperty, prefix: str) -> Filter:
        return Filter._function("startswith", prop, prefix)

    @staticmethod
    def ends_with(prop: str | ODataProperty, suffix: str) -> Filter:
        return Filter._function("endswith", prop, suffix)

    @staticmethod
    def contains(prop: str | ODataProperty, text: str) -> Filter:
        return Filter._function("contains", prop, text)

    @staticmethod
    def any(collection_path: str, variable: str, condition: Callable[[Variable], Filter]) -> Filter:
        return Filter._lambda("any", collection_path, variable, condition)

    @staticmethod
    def all(collection_path: str, variable: str, condition: Callable[[Variable], Filter]) -> Filter:
        return Filter._lambda("all", collection_path, variable, condition)

    @staticmethod
    def in_(prop: str | ODataProperty, *values: Any) -> Filter:
        if not values:
            raise ValueError("in_() needs at least one value: an empty set matches nothing")
        return Filter(f"({_property(prop)} in ({','.join(literal(v) for v in values)}))")

    def __and__(self, other: Filter) -> Filter:
        return Filter.and_(self, other)

    def __or__(self, other: Filter) -> Filter:
        return Filter.or_(self, other)

    def __invert__(self) -> Filter:
        return Filter.not_(self)

    def __str__(self) -> str:
        return self._expression

    def __repr__(self) -> str:
        return f"Filter({self._expression!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Filter) and other._expression == self._expression

    def __hash__(self) -> int:
        return hash(self._expression)

    class Variable:
        """The range variable of ``any`` and ``all``."""

        __slots__ = ("_name",)

        def __init__(self, name: str):
            self._name = name

        def nav(self, path: str) -> ODataProperty:
            return ODataProperty(f"{self._name}/{path}")

        def self_(self) -> ODataProperty:
            """The variable itself (Java's ``self()``)."""
            return ODataProperty(self._name)

    @staticmethod
    def _compare(prop: str | ODataProperty, operator: str, value: Any) -> Filter:
        return Filter(f"({_property(prop)} {operator} {literal(value)})")

    @staticmethod
    def _join(operator: str, filters: tuple[Filter, ...]) -> Filter:
        if not filters:
            raise ValueError(f"{operator}_() needs at least one filter")
        if len(filters) == 1:
            return filters[0]
        return Filter("(" + f" {operator} ".join(f._expression for f in filters) + ")")

    @staticmethod
    def _function(name: str, prop: str | ODataProperty, argument: str) -> Filter:
        return Filter(f"{name}({_property(prop)},{string_literal(argument)})")

    @staticmethod
    def _lambda(kind: str, collection_path: str, variable: str, condition: Callable[[Filter.Variable], Filter]) -> Filter:
        if not _IDENTIFIER.match(variable):
            raise ValueError(f"Not a valid range variable: {variable}")
        return Filter(f"{collection_path}/{kind}({variable}:{condition(Filter.Variable(variable))._expression})")


Variable = Filter.Variable

_CUSTOM = re.compile(r"[A-Za-z!][A-Za-z0-9_\-.!]*\Z")
_MISSING: Any = object()


class ODataQuery:
    """OData query options: ``$select``, ``$expand`` (with nested options), ``$filter``,
    ``$orderby``, ``$top``, ``$skip``, ``$count``, custom options such as TM1's ``!sandbox``,
    and parameter aliases. Immutable: each method returns a new query, as the Java client's::

        ODataQuery.none().select("Name", "Type") \\
            .expand("Hierarchies", lambda h: h.select("Name").top(5)) \\
            .filter(Filter.starts_with(ODataProperty.of("Name"), "Q"))
        # $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q')

    Keyword arguments are a shortcut for the same: ``ODataQuery(select=["Name"], top=5)``,
    and so are the ``**options`` every service method takes.

    Options are written in a fixed order, and each value is percent-encoded once by
    :meth:`to_query_string`. Nested options inside ``$expand`` are separated by ``;``.
    """

    __slots__ = ("_select", "_expand", "_filter", "_orderby", "_top", "_skip", "_count", "_search", "_custom", "_aliases")

    def __init__(
        self,
        select: Any = None,
        expand: Any = None,
        filter: Filter | str | None = None,
        orderby: Any = None,
        top: int | None = None,
        skip: int | None = None,
        count: bool | None = None,
        search: str | None = None,
        custom: Mapping[str, str] | None = None,
        aliases: Mapping[str, str] | None = None,
    ):
        self._select: tuple[str, ...] = tuple(_names(select))
        self._expand: dict[str, ODataQuery] = {}
        for nav in _names(expand):
            self._expand[nav] = None  # type: ignore[assignment]  (no nested options)
        self._filter: str | None = None if filter is None else str(filter)
        self._orderby: tuple[str, ...] = tuple(_names(orderby))
        self._top = _non_negative("$top", top)
        self._skip = _non_negative("$skip", skip)
        self._count = count
        self._search = search
        self._custom: dict[str, str] = dict(custom or {})
        self._aliases: dict[str, str] = dict(aliases or {})

    @staticmethod
    def none() -> ODataQuery:
        """No options: the server's default response."""
        return EMPTY

    @staticmethod
    def of(query: ODataQuery | None = None, **options: Any) -> ODataQuery:
        """``query`` (or none), with the keyword options added: what service methods do with
        their ``query`` and ``**options``."""
        base = EMPTY if query is None else query
        if not options:
            return base
        out = base._copy()
        for name, value in options.items():
            if value is None:
                continue
            if name == "select":
                out._select += tuple(_names(value))
            elif name == "expand":
                for nav in _names(value):
                    out._expand.setdefault(nav, None)  # type: ignore[arg-type]
            elif name == "filter":
                out._filter = str(value)
            elif name == "orderby":
                out._orderby += tuple(_names(value))
            elif name in ("top", "skip"):
                setattr(out, "_" + name, _non_negative("$" + name, value))
            elif name == "count":
                out._count = bool(value)
            elif name == "search":
                out._search = value
            elif name == "custom":
                out._custom.update(value)
            elif name == "aliases":
                out._aliases.update(value)
            else:
                raise TypeError(f"Not a query option: {name}")
        return out

    # The builder, as in Java

    def select(self, *properties: str | ODataProperty) -> ODataQuery:
        out = self._copy()
        out._select += tuple(_property(p) for p in properties)
        return out

    def expand(
        self,
        navigation: str | ODataProperty,
        options: Callable[[ODataQuery], ODataQuery] | ODataQuery | None = None,
    ) -> ODataQuery:
        """Expands a navigation property, with nested options: a query, or a function of one
        (``lambda h: h.select("Name")``), applied to what's there for it already."""
        nav = _property(navigation)
        out = self._copy()
        current = out._expand.get(nav) or EMPTY
        if callable(options):
            current = options(current)
        elif isinstance(options, ODataQuery):
            current = options
        out._expand[nav] = current
        return out

    def filter(self, filter: Filter | str) -> ODataQuery:
        out = self._copy()
        out._filter = str(filter)
        return out

    def order_by(self, prop: str | ODataProperty) -> ODataQuery:
        out = self._copy()
        out._orderby += (_property(prop),)
        return out

    def order_by_descending(self, prop: str | ODataProperty) -> ODataQuery:
        out = self._copy()
        out._orderby += (_property(prop) + " desc",)
        return out

    def top(self, n: int) -> ODataQuery:
        out = self._copy()
        out._top = _non_negative("$top", n)
        return out

    def skip(self, n: int) -> ODataQuery:
        out = self._copy()
        out._skip = _non_negative("$skip", n)
        return out

    def with_count(self) -> ODataQuery:
        out = self._copy()
        out._count = True
        return out

    def option(self, name: str, value: str = _MISSING) -> Any:
        """With a value, a query with a custom option (``!sandbox``); without, that option's
        value, or None (as Java's two ``option`` methods)."""
        if value is _MISSING:
            return self._custom.get(name)
        if not isinstance(name, str) or not _CUSTOM.match(name):
            raise ValueError(f"Not a valid custom query option name: {name}")
        out = self._copy()
        out._custom[name] = value
        return out

    def alias(self, alias: Alias | str, literal_or_json: str) -> ODataQuery:
        """A parameter alias's value: ``Fn(p=@p)`` with ``?@p=…``."""
        name = alias.name if isinstance(alias, Alias) else Alias(alias).name
        out = self._copy()
        out._aliases[name] = literal_or_json
        return out

    def is_empty(self) -> bool:
        return not self._system() and not self._custom and not self._aliases

    @property
    def custom(self) -> dict[str, str]:
        return dict(self._custom)

    @property
    def aliases(self) -> dict[str, str]:
        return dict(self._aliases)

    def params(self) -> list[tuple[str, str]]:
        """Every option as ``(name, value)``, unencoded, in order."""
        return list(self._system().items()) + list(self._custom.items()) + list(self._aliases.items())

    def to_query_string(self) -> str:
        """The encoded query string, without ``?``."""
        return "&".join(f"{name}={query_value(value)}" for name, value in self.params())

    def __str__(self) -> str:
        return "&".join(f"{name}={value}" for name, value in self.params())

    def __repr__(self) -> str:
        return f"ODataQuery({str(self)!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, ODataQuery) and other.to_query_string() == self.to_query_string()

    def __hash__(self) -> int:
        return hash(self.to_query_string())

    def _copy(self) -> ODataQuery:
        out = object.__new__(ODataQuery)
        for slot in ODataQuery.__slots__:
            value = getattr(self, slot)
            setattr(out, slot, dict(value) if isinstance(value, dict) else value)
        return out

    def _system(self) -> dict[str, str]:
        out: dict[str, str] = {}
        if self._select:
            out["$select"] = ",".join(self._select)
        if self._expand:
            items = []
            for nav, nested in self._expand.items():
                inner = nested._system() if nested is not None else {}
                items.append(nav if not inner else nav + "(" + ";".join(f"{k}={v}" for k, v in inner.items()) + ")")
            out["$expand"] = ",".join(items)
        if self._filter is not None:
            out["$filter"] = self._filter
        if self._orderby:
            out["$orderby"] = ",".join(self._orderby)
        if self._top is not None:
            out["$top"] = str(self._top)
        if self._skip is not None:
            out["$skip"] = str(self._skip)
        if self._count is not None:
            out["$count"] = "true" if self._count else "false"
        if self._search is not None:
            out["$search"] = self._search
        return out


def _non_negative(name: str, n: int | None) -> int | None:
    if n is not None and n < 0:
        raise ValueError(f"{name} can't be negative: {n}")
    return n


EMPTY = ODataQuery()


ODataPath.Alias = Alias  # type: ignore[attr-defined]  (as Java's ODataPath.Alias)


class ODataLiterals:
    """OData literals, as the Java client's ``ODataLiterals``."""

    Raw = Raw

    @staticmethod
    def string(value: str) -> str:
        return string_literal(value)

    @staticmethod
    def enum_member(qualified_type: str, member: str) -> str:
        return qualified_type + string_literal(member)

    @staticmethod
    def of(value: Any) -> str:
        return literal(value)


class ODataEncoding:
    """Percent-encoding, as the Java client's ``ODataEncoding``."""

    path_value = staticmethod(path_value)
    query_value = staticmethod(query_value)


class ODataJson:
    """OData's JSON control information, as the Java client's ``ODataJson``."""

    CONTEXT = "@odata.context"
    ETAG = "@odata.etag"
    TYPE = "@odata.type"
    ID = "@odata.id"
    NEXT_LINK = "@odata.nextLink"
    DELTA_LINK = "@odata.deltaLink"
    COUNT = "@odata.count"
    BIND_SUFFIX = "@odata.bind"

    @staticmethod
    def bind(navigation: str, target: ODataPath | Sequence[ODataPath]) -> dict[str, Any]:
        """``{"Dimensions@odata.bind": ["Dimensions('a')", …]}`` for a POST or PATCH body."""
        if isinstance(target, ODataPath):
            return {navigation + ODataJson.BIND_SUFFIX: str(target)}
        return {navigation + ODataJson.BIND_SUFFIX: [str(t) for t in target]}
