# SPDX-License-Identifier: Apache-2.0

"""What the generated model is built on: :class:`Tm1Model`, :class:`Tm1Enum` and the
conversion of values to TM1's JSON.

Reading is lenient, as the Java client's is: every property is optional (``$select`` can
leave any out), unknown properties are ignored, an enum member a newer server added reads
as the enum's fallback, and a subtype is chosen by ``@odata.type``.

Writing sends only the properties that were set, an explicit ``None`` included, so one
model class serves for reading and for ``PATCH``/``POST``::

    Cube(rules="SKIPCHECK;")       # {"Rules": "SKIPCHECK;"}
    Cube(drillthrough_rules=None)  # {"DrillthroughRules": null}

Don't send a model read from TM1 back as a write: it carries every property TM1 sent,
server-computed ones included.
"""

from __future__ import annotations

import enum
import math
import uuid
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from typing import TYPE_CHECKING, Annotated, Any, ClassVar

from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    TypeAdapter,
    ValidationError,
    ValidationInfo,
    field_serializer,
    field_validator,
    model_validator,
)

from .odata import Bind, ODataPath, duration, string_literal

if TYPE_CHECKING:
    pass

__all__ = ["LenientDateTime", "Tm1Enum", "Tm1Model", "Tm1Patch", "iso_datetime", "to_odata"]


class Tm1Enum(enum.StrEnum):
    """A TM1 enum type: members are TM1's names; a value this client doesn't know reads as
    the fallback member (``UNKNOWN``), which is never sent."""

    __odata_prefix__: ClassVar[str] = ""
    __odata_fallback__: ClassVar[str] = "UNKNOWN"

    @classmethod
    def _missing_(cls, value: object) -> Any:
        return cls.__members__.get(cls.__odata_fallback__)

    @property
    def known(self) -> bool:
        """Whether this is one of TM1's members, rather than the fallback a value this client
        doesn't know reads as."""
        return self.name != self.__odata_fallback__

    def odata_literal(self) -> str:
        """The member as an OData literal, for filters: ``tm1.AttributeType'Alias'``."""
        if not self.known:
            raise ValueError(f"{type(self).__name__}.{self.name} stands for a value this client doesn't know")
        return self.__odata_prefix__ + string_literal(self.value)


_DATETIME = TypeAdapter(datetime)


def iso_datetime(value: datetime) -> str:
    """A date-time as the Java client writes it (``ISO_OFFSET_DATE_TIME``): the fraction of a
    second without trailing zeros, and ``Z`` for UTC: ``2026-01-02T03:04:00.12+05:30``."""
    text = value.replace(microsecond=0).isoformat()
    offset = ""
    if value.tzinfo is not None:
        text, offset = text[:19], text[19:]
        offset = "Z" if offset in ("+00:00", "-00:00") else offset
    fraction = f".{value.microsecond:06d}".rstrip("0") if value.microsecond else ""
    return text + fraction + offset


def _lenient_datetime(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return _DATETIME.validate_python(value)
        except ValidationError:
            return None
    return value


LenientDateTime = Annotated[datetime | None, BeforeValidator(_lenient_datetime)]
"""A date-time TM1 sometimes sends something else in (``0000-00-00T00:00Z``): read as None."""


class Tm1Model(BaseModel):
    """A TM1 entity or complex type."""

    model_config = ConfigDict(
        populate_by_name=True,
        extra="ignore",
        arbitrary_types_allowed=True,
        protected_namespaces=(),
        ser_json_timedelta="iso8601",
    )

    odata_type: ClassVar[str | None] = None
    """``@odata.type`` of a derived type, sent with it: ``#ibm.tm1.api.v1.NativeView``."""

    odata_name: ClassVar[str] = ""
    """The type's qualified name in ``$metadata``."""

    odata_subtypes: ClassVar[dict[str, type[Tm1Model]]] = {}
    """On the root of a hierarchy: its derived types, by ``@odata.type`` without ``#``."""

    @model_validator(mode="wrap")
    @classmethod
    def _choose_subtype(cls, data: Any, handler: Any) -> Any:
        subtypes = cls.__dict__.get("odata_subtypes")
        if subtypes and isinstance(data, dict):
            name = data.get("@odata.type")
            if isinstance(name, str):
                sub = subtypes.get(name.lstrip("#"))
                if sub is not None and sub is not cls:
                    return sub.model_validate(data)
        return handler(data)

    @field_validator("*", mode="wrap")
    @classmethod
    def _in_utc(cls, value: Any, handler: Any, info: ValidationInfo) -> Any:
        # The Java client reads every date-time in TM1's JSON as its instant in UTC (Jackson's
        # ADJUST_DATES_TO_CONTEXT_TIME_ZONE), but keeps one it's given as it was given: so does
        # this one. A ``{"as_given": True}`` context reads text as given too.
        read = handler(value)
        given = not isinstance(value, str) or bool(info.context and info.context.get("as_given"))
        if isinstance(read, datetime) and read.tzinfo is not None and not given:
            return read.astimezone(UTC)
        return read

    @field_serializer("*", mode="wrap", when_used="json")
    def _as_java(self, value: Any, handler: Any) -> Any:
        if isinstance(value, datetime):
            return iso_datetime(value)
        if isinstance(value, timedelta):
            return duration(value)
        return handler(value)

    def to_odata(self) -> dict[str, Any]:
        """The properties set, as TM1's JSON: ``@odata.bind`` for :class:`Bind` values, and
        ``@odata.type`` first for a derived type."""
        out: dict[str, Any] = {}
        cls = type(self)
        if cls.odata_type is not None:
            out["@odata.type"] = cls.odata_type
        fields_set = self.model_fields_set
        for name, info in cls.model_fields.items():
            if name not in fields_set or name == "etag":
                continue
            key = info.alias or name
            value = getattr(self, name)
            if isinstance(value, Bind):
                out[key + "@odata.bind"] = str(value)
            elif isinstance(value, (list, tuple)) and value and all(isinstance(v, Bind) for v in value):
                out[key + "@odata.bind"] = [str(v) for v in value]
            else:
                out[key] = to_odata(value)
        if self.model_extra:
            for key, value in self.model_extra.items():
                out[key] = to_odata(value)
        return out


class Tm1Patch:
    """A write (the generated ``CubePatch`` and the rest): the properties set, explicit
    ``None`` included, and ``@odata.bind`` references, in the order they were set. A subtype's
    patch sends its ``@odata.type`` first."""

    odata_type: str | None = None

    def __init__(self, values: dict[str, Any]):
        self._values = dict(values)

    def json(self) -> dict[str, Any]:
        """The properties set, by TM1's names."""
        return {k: to_odata(v) for k, v in self._values.items()}

    def __eq__(self, other: object) -> bool:
        return type(other) is type(self) and other._values == self._values  # type: ignore[attr-defined]

    def __hash__(self) -> int:
        return hash(tuple(sorted(self._values)))

    def __repr__(self) -> str:
        return f"{type(self).__name__}{self._values}"

    class Builder:
        """Builds a patch property by property: only what's set is sent, a property set to None as
        an explicit ``null``."""

        def __init__(self, patch: type[Tm1Patch]):
            self._patch = patch
            self._values: dict[str, Any] = {}
            if patch.odata_type is not None:
                self._values["@odata.type"] = patch.odata_type

        def _set(self, name: str, value: Any) -> Any:
            self._values[name] = list(value) if isinstance(value, (list, tuple)) else value
            return self

        def _bind(self, name: str, target: Any) -> Any:
            self._values[name + "@odata.bind"] = str(target)
            return self

        def _bind_all(self, name: str, targets: Any) -> Any:
            self._values[name + "@odata.bind"] = [str(t) for t in targets]
            return self

        def _copy(
            self,
            read: Any,
            names: tuple[str | tuple[str, Any], ...],
            declared: tuple[str, ...] | None,
            bindings: tuple[tuple[str, str, str, str], ...] = (),
        ) -> Any:
            """What ``read``, a model read from TM1, holds of ``names``, nulls left out: each a name, or
            ``(name, write type)`` for a structured value, converted with that type's ``builder(read)``.
            Then the navigations ``bindings`` names, ``(name, how, collection, key)``, bound where their
            paths can be made (:func:`read_binding`); and, for an open type (``declared`` given), its
            undeclared properties. Nothing when ``read`` is None."""
            if read is None:
                return self
            data = read.to_odata()
            for entry in names:
                name, write = entry if isinstance(entry, tuple) else (entry, None)
                if data.get(name) is None:
                    continue
                if write is None:
                    self._set(name, data[name])
                else:
                    value = _field(read, name)
                    self._set(name, [write.builder(v).build() for v in value] if isinstance(value, list) else write.builder(value).build())
            for name, how, collection, key in bindings:
                if data.get(name) is not None:
                    targets = read_binding(how, collection, data[name], key)
                    if targets is not None:
                        self._values[name + "@odata.bind"] = targets
            if declared is not None:
                for name, value in data.items():
                    if name not in declared and not name.startswith("@") and value is not None:
                        self._values[name] = value
            return self

        def build(self) -> Any:
            """The patch, with the properties set so far."""
            return self._patch(self._values)


def _field(read: Any, name: str) -> Any:
    """A model's field by its name in TM1's JSON."""
    for field, info in type(read).model_fields.items():
        if (info.alias or field) == name:
            return getattr(read, field)
    return None


def read_binding(how: str, collection: str, value: Any, key: str) -> str | list[str] | None:
    """The path, or paths, a write's ``builder(read)`` binds a navigation the read holds to (``value``,
    as TM1's JSON): None where one can't be made, and for a collection where any can't. ``how`` is
    ``by_key`` (or ``by_keys``), an entity in the set ``collection`` by its ``key`` property:
    ``Dimensions('Region')``; or ``in_hierarchy`` (or ``all_in_hierarchy``), an element or member by
    its ``UniqueName``, in ``collection`` (``Elements``, ``Members``) of its hierarchy. As the Java
    model's ``ReadBindings``."""

    def one(v: Any) -> str | None:
        k = v.get(key) if isinstance(v, dict) else None
        if how in ("by_key", "by_keys"):
            return None if k is None else str(ODataPath.of(collection).key(k))
        path = in_hierarchy(collection, k) if isinstance(k, str) else None
        return None if path is None else str(path)

    if how in ("by_key", "in_hierarchy"):
        return one(value)
    paths = [one(v) for v in value]
    return None if any(p is None for p in paths) else [p for p in paths if p is not None]


def in_hierarchy(collection: str, unique_name: str) -> ODataPath | None:
    """An element or member by its ``UniqueName``: ``[d].[h].[e]``, or ``[d].[e]`` in the dimension's
    own hierarchy, with ``]]`` for ``]``. None for any other shape."""
    parts: list[str] = []
    i = 0
    while i < len(unique_name):
        if unique_name[i] != "[":
            return None
        part: list[str] = []
        j = i + 1
        while True:
            if j >= len(unique_name):
                return None
            c = unique_name[j]
            if c == "]":
                if j + 1 < len(unique_name) and unique_name[j + 1] == "]":
                    part.append("]")
                    j += 2
                    continue
                break
            part.append(c)
            j += 1
        parts.append("".join(part))
        i = j + 1
        if i < len(unique_name):
            if unique_name[i] != ".":
                return None
            i += 1
            if i == len(unique_name):
                return None
    if len(parts) == 2:
        parts.insert(1, parts[0])
    if len(parts) != 3:
        return None
    return ODataPath.of("Dimensions").key(parts[0]).nav("Hierarchies").key(parts[1]).nav(collection).key(parts[2])


def to_odata(value: Any) -> Any:
    """A value as TM1's JSON: models by :meth:`Tm1Model.to_odata`, enums by name, dates and
    times in ISO 8601."""
    if isinstance(value, Tm1Enum):
        if not value.known:
            raise ValueError(f"{type(value).__name__}.{value.name} stands for a value this client doesn't know")
        return value.value
    if value is None or isinstance(value, (bool, str, int)):
        return value
    if isinstance(value, Tm1Model):
        return value.to_odata()
    if isinstance(value, Tm1Patch):
        return value.json()
    if isinstance(value, enum.Enum):
        return to_odata(value.value)
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return "NaN" if math.isnan(value) else ("INF" if value > 0 else "-INF")
        return value
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, datetime):
        return iso_datetime(value)
    if isinstance(value, (date, time)):
        return value.isoformat()
    if isinstance(value, timedelta):
        return duration(value)
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, Bind):
        raise TypeError("A Bind is a property value of a model, sent as Name@odata.bind; not a value of its own")
    if isinstance(value, dict):
        return {str(k): to_odata(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_odata(v) for v in value]
    raise TypeError(f"Can't send a {type(value).__name__} to TM1")
