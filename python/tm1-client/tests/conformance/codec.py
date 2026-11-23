# SPDX-License-Identifier: Apache-2.0

"""The language-neutral encoding of the conformance fixtures (``spec/conformance/README.md``):
arguments decoded into this client's values, results and errors encoded to compare with what
the Java client recorded, and the request comparison every runner makes."""

from __future__ import annotations

import base64
import datetime as dt
import enum
import json
import re
from typing import Any

import pydantic

import skipcheck_tm1
from skipcheck_tm1 import Filter, ODataPath, ODataQuery
from skipcheck_tm1 import errors as e
from skipcheck_tm1._generated import model as m
from skipcheck_tm1._generated import patches as p
from skipcheck_tm1.base import Tm1Enum

SPEC_VERSION = 1

# The request headers fixtures record: as ConformanceTm1.HEADERS, plus TM1-* but the session's own
HEADERS = {"accept", "content-type", "if-match", "if-none-match", "odata-version", "odata-maxversion", "prefer"}


class Unknown(Exception):
    """A tag or type this runner can't decode: a failure, never a skip."""


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def _services() -> Any:
    from skipcheck_tm1 import services

    return services


def decode(value: Any) -> Any:
    """An argument, as this client takes it."""
    if value is None or isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, list):
        return [decode(v) for v in value]
    if not isinstance(value, dict) or not value:
        raise Unknown(f"not an encoded value: {value!r}")
    tag = next(iter(value))
    if tag == "$double":
        return float(value["$double"])
    if tag == "$query":
        return query(value["$query"])
    if tag == "$path":
        return ODataPath.link(value["$path"])
    if tag == "$map":
        return {k: decode(v) for k, v in value["$map"].items()}
    if tag == "$patch":
        cls = getattr(p, value["$patch"], None)
        if cls is None:
            raise Unknown(f"no patch {value['$patch']}")
        return cls(dict(value["json"]))
    if tag == "$model":
        cls = getattr(m, value["$model"], None)
        if cls is None:
            raise Unknown(f"no model {value['$model']}")
        # An argument Java built: its date-times as given, as a caller's are
        return cls.model_validate(value["json"], context={"as_given": True})
    if tag == "$enum":
        return _enum(value)
    if tag == "$duration":
        return pydantic.TypeAdapter(dt.timedelta).validate_python(value["$duration"])
    if tag == "$dateTime":
        return pydantic.TypeAdapter(dt.datetime).validate_python(value["$dateTime"])
    if tag == "$date":
        return dt.date.fromisoformat(value["$date"])
    if tag == "$time":
        return dt.time.fromisoformat(value["$time"])
    if tag == "$bytes":
        return base64.b64decode(value["$bytes"])
    if tag == "$handler":
        if value["$handler"] not in _HANDLERS:
            raise Unknown(f"no handler {value['$handler']}")
        return _HANDLERS[value["$handler"]]
    if tag == "$type":
        cls = getattr(_services(), value["$type"], None) or getattr(skipcheck_tm1, value["$type"], None)
        if cls is None:
            raise Unknown(f"no type {value['$type']}")
        args = [decode(a) for a in value["args"]]
        if value["factory"] == "new":
            return cls(*args)
        # A static factory, or an instance method (``ContentPath.child``) on the first argument
        return getattr(cls, snake(value["factory"]))(*args)
    raise Unknown(f"unknown tag {tag}")


def _cells(cellset: Any, cells: Any) -> Any:
    """``(cellset, cells) -> cells``: a cellset stream's cells, in either flavor."""
    if hasattr(cells, "__aiter__"):

        async def collect() -> list[Any]:
            return [c async for c in cells]

        return collect()
    return list(cells)


# The handlers every runner knows by name (Java's ConformanceHandler)
_HANDLERS = {"cells": _cells}


def _enum(value: dict[str, Any]) -> Any:
    name = value["$enum"]
    cls = getattr(m, name, None) or getattr(_services(), name, None)
    if cls is None:
        raise Unknown(f"no enum {name}")
    if issubclass(cls, enum.Enum) and value["name"] in cls.__members__:
        return cls[value["name"]]
    return cls(value["json"])


def query(parts: dict[str, Any]) -> ODataQuery:
    """A query rebuilt from its parts, with this client's own query builder."""
    q = ODataQuery.none()
    if "select" in parts:
        q = q.select(*parts["select"])
    for name, nested in parts.get("expand", {}).items():
        q = q.expand(name, query(nested)) if nested else q.expand(name)
    if "filter" in parts:
        q = q.filter(Filter.raw(parts["filter"]))
    for key in parts.get("orderBy", []):
        q = q.order_by_descending(key[: -len(" desc")]) if key.endswith(" desc") else q.order_by(key)
    if "top" in parts:
        q = q.top(parts["top"])
    if "skip" in parts:
        q = q.skip(parts["skip"])
    if parts.get("count"):
        q = q.with_count()
    for name, v in parts.get("options", {}).items():
        q = q.option(name, v)
    for name, v in parts.get("aliases", {}).items():
        q = q.alias(name, v)
    unknown = set(parts) - {"select", "expand", "filter", "orderBy", "top", "skip", "count", "options", "aliases"}
    if unknown:
        raise Unknown(f"unknown query parts {sorted(unknown)}")
    return q


def _without_types(json_: Any) -> Any:
    if isinstance(json_, dict):
        return {k: _without_types(v) for k, v in json_.items() if k != "@odata.type"}
    if isinstance(json_, list):
        return [_without_types(v) for v in json_]
    return json_


def result(value: Any) -> Any:
    """A result, encoded as the Java recorder encodes its own."""
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        return value
    if isinstance(value, (bytes, bytearray)):
        return {"$bytes": base64.b64encode(bytes(value)).decode("ascii")}
    if isinstance(value, (list, tuple)):
        return [result(v) for v in value]
    if isinstance(value, pydantic.BaseModel):
        # Without @odata.type, at any depth, as the Java recorder writes a model: its $model is its type;
        # a fallback enum member (a value this client doesn't know) as null, as Java writes its own
        return {"$model": type(value).__name__, "json": _fallbacks_as_null(_without_types(value.model_dump(by_alias=True, mode="json", exclude_none=True)))}
    if isinstance(value, Tm1Enum) and not value.known:
        return None  # Java's fallback constant, whose JSON is null
    if isinstance(value, enum.Enum):
        return value.value
    if isinstance(value, dict):
        return {k: result(v) for k, v in value.items()}
    raise Unknown(f"can't encode a result of type {type(value).__name__}")


def _without_types(value: Any) -> Any:
    """A model's JSON without ``@odata.type``, throughout: the type is ``$model``'s, as the Java
    recorder writes it."""
    if isinstance(value, dict):
        return {k: _without_types(v) for k, v in value.items() if k != "@odata.type"}
    if isinstance(value, list):
        return [_without_types(v) for v in value]
    return value


_FALLBACK = "\x00unknown"  # a fallback enum member's value: a value this client doesn't know


def _fallbacks_as_null(value: Any) -> Any:
    """A model's JSON with its fallback enum members as null, as Java writes its own."""
    if value == _FALLBACK:
        return None
    if isinstance(value, list):
        return [_fallbacks_as_null(v) for v in value]
    if isinstance(value, dict):
        return {k: _fallbacks_as_null(v) for k, v in value.items()}
    return value


def error(exc: BaseException) -> dict[str, Any]:
    """How a call failed, as the Java recorder writes it."""
    out: dict[str, Any] = {"exception": type(exc).__name__}
    if isinstance(exc, e.Tm1ApiError):
        out.update(category="api", kind=exc.kind.name, status=exc.status)
        if exc.error is not None and exc.error.code:
            out["code"] = exc.error.code
    elif isinstance(exc, e.Tm1UnsupportedOperationError):
        out["category"] = "unsupported"
    elif isinstance(exc, e.Tm1ProtocolError):
        out["category"] = "protocol"
    elif isinstance(exc, e.Tm1TransportError):
        out["category"] = "transport"
    elif isinstance(exc, (ValueError, TypeError)):
        out["category"] = "argument"
    elif isinstance(exc, RuntimeError):
        out["category"] = "state"
    else:
        raise exc
    return out


def same_error(expected: dict[str, Any], got: dict[str, Any]) -> bool:
    """Equal but for the exception's name, which is each language's own."""
    keys = {"category", "kind", "status", "code"}
    return {k: v for k, v in expected.items() if k in keys} == {k: v for k, v in got.items() if k in keys}


def normal(value: Any, drop_nulls: bool = False) -> Any:
    """For comparing: integral floats as ints; with ``drop_nulls`` (results), null members left
    out. Everything else, date-times and durations included, must be exactly as Java wrote it."""
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, list):
        return [normal(v, drop_nulls) for v in value]
    if drop_nulls and isinstance(value, dict) and "$model" in value:
        # A model by its JSON, as the README says (a derived type's by its @odata.type in it):
        # its class is each language's own, such as Java's Unknown for a root read without one
        return normal(value.get("json"), drop_nulls)
    if isinstance(value, dict):
        return {k: normal(v, drop_nulls) for k, v in value.items() if not (drop_nulls and v is None)}
    return value


def ordered(value: Any) -> Any:
    """JSON with its members' order kept, for comparing bodies exactly."""
    if isinstance(value, dict):
        return [(k, ordered(v)) for k, v in value.items()]
    if isinstance(value, list):
        return [ordered(v) for v in value]
    return normal(value)


def request_of(method: str, target: str, headers: dict[str, str], body: bytes) -> dict[str, Any]:
    """A request as a fixture records it."""
    out: dict[str, Any] = {"method": method, "target": target}
    kept = {k: v for k, v in sorted(headers.items()) if k in HEADERS or (k.startswith("tm1-") and k != "tm1-sessioncontext")}
    out["headers"] = kept
    if body:
        ctype = headers.get("content-type", "").lower()
        if ctype.startswith("application/json"):
            out["json"] = json.loads(body)
        elif ctype.startswith("text/"):
            out["text"] = body.decode("utf-8")
        else:
            out["bytes"] = base64.b64encode(body).decode("ascii")
    return out


def same_request(expected: dict[str, Any], got: dict[str, Any]) -> bool:
    return {k: (ordered(v) if k == "json" else v) for k, v in expected.items()} == {k: (ordered(v) if k == "json" else v) for k, v in got.items()}
