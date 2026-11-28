# SPDX-License-Identifier: Apache-2.0

"""Every endpoint of the spec, called through the generated API against a fake TM1: each sends
the request OData says it should (method, path, parameters).

The routes are worked out from the spec, not from the generated code: from each entity set
and singleton, through navigation properties and type casts, to an entity of every type, and
from there to its navigation properties and bound operations. The generated index says only
what each member is called. So a member that's missing, misnamed, or builds the wrong
request fails here.
"""

from __future__ import annotations

import json
import uuid
from collections import deque
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import Bind
from skipcheck_tm1._generated import index, model
from skipcheck_tm1.version import Tm1Capabilities

from .conftest import SPEC, Api, FakeTm1, snapshot, target

_V11_METADATA = snapshot("11.8.35").read_bytes()
_V12_METADATA = snapshot("12.6.0").read_bytes()
_V11_ENDPOINTS = Tm1Capabilities.parse(_V11_METADATA).endpoints

DOC = json.loads(SPEC.read_text(encoding="utf-8"))
TYPES = {t["qualifiedName"]: t for t in DOC["types"]}
ENUMS = {e["qualifiedName"]: e for e in DOC["enums"]}
OPERATIONS = {o["endpoint"]: o for o in DOC["operations"] if o["bound"]}
UNBOUND = {o["qualifiedName"]: o for o in DOC["operations"] if not o["bound"]}
ALIAS = {s["namespace"]: s["alias"] for s in DOC["schemas"]}


def alias(qualified: str) -> str:
    ns, _, name = qualified.rpartition(".")
    return f"{ALIAS.get(ns, ns)}.{name}"


def chain(t: dict[str, Any]) -> list[dict[str, Any]]:
    out = [t]
    while out[0].get("baseType"):
        out.insert(0, TYPES[out[0]["baseType"]])
    return out


def root(t: dict[str, Any]) -> dict[str, Any]:
    return chain(t)[0]


# Sample values, by CSDL type, and how each is written in a path

SAMPLES: dict[str, tuple[Any, str]] = {
    "Edm.String": ("k'1", "'k''1'"),
    "Edm.Boolean": (True, "true"),
    "Edm.Int16": (3, "3"),
    "Edm.Int32": (3, "3"),
    "Edm.Int64": (3, "3"),
    "Edm.Double": (1.5, "1.5"),
    "Edm.Single": (1.5, "1.5"),
    "Edm.Decimal": (1.5, "1.5"),
    "Edm.Date": (date(2026, 1, 2), "2026-01-02"),
    "Edm.TimeOfDay": (time(3, 4, 5), "03:04:05"),
    "Edm.DateTimeOffset": (datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC), "2026-01-02T03:04:05Z"),
    "Edm.Duration": (timedelta(seconds=90), "duration'PT1M30S'"),
    "Edm.Guid": (uuid.UUID(int=1), str(uuid.UUID(int=1))),
    "Edm.PrimitiveType": ("v", "'v'"),
    "Edm.Untyped": ("v", "'v'"),
}

_ENCODED = {"'k''1'": "'k''1'", "2026-01-02T03:04:05Z": "2026-01-02T03%3A04%3A05Z", "03:04:05": "03%3A04%3A05"}


def path_text(literal: str) -> str:
    """A literal as it appears in a path: percent-encoded, as the clients encode it."""
    return _ENCODED.get(literal, literal)


def sample(ref: dict[str, Any]) -> tuple[Any, Any]:
    """A value of a parameter's type, and what it should look like in JSON."""
    name = ref["type"]
    if name in ENUMS:
        member = ENUMS[name]["members"][0]["name"]
        cls = getattr(model, ENUMS[name]["name"])
        value, json_value = (member, member) if ENUMS[name]["flags"] else (cls(member), member)
    elif name in TYPES:
        t = TYPES[name]
        if t["kind"] == "entity":
            value, json_value = Bind("Things('x')"), "Things('x')"
        else:
            value, json_value = getattr(model, t["name"])(), {}
            if t.get("baseType"):
                json_value = {"@odata.type": "#" + name}
    else:
        value, text = SAMPLES[name]
        json_value = {
            "Edm.Date": "2026-01-02",
            "Edm.TimeOfDay": "03:04:05",
            "Edm.DateTimeOffset": "2026-01-02T03:04:05Z",
            "Edm.Duration": "PT1M30S",
            "Edm.Guid": str(uuid.UUID(int=1)),
        }.get(name, value)
    if ref["collection"]:
        return [value], [json_value]
    return value, json_value


def key_of(t: dict[str, Any]) -> tuple[Any, str]:
    """A key for an entity of type ``t``, and how it's written: ``('k''1')``."""
    r = root(t)
    parts = []
    for name in r["key"]:
        prop = next(p for p in r["properties"] if p["name"] == name)
        parts.append((name, SAMPLES[prop["type"]]))
    if len(parts) == 1:
        value, text = parts[0][1]
        return value, f"({path_text(text)})"
    return tuple(v for _, (v, _t) in parts), "(" + ",".join(f"{n}={path_text(t)}" for n, (_v, t) in parts) + ")"


# Routes: how to reach an entity of each type, from the root


@dataclass(frozen=True)
class Step:
    attr: str
    call: bool = False  # a cast is a method
    key: Any = None
    has_key: bool = False


@dataclass(frozen=True)
class Route:
    steps: tuple[Step, ...]
    path: str

    def walk(self, api: Any) -> Any:
        current = api
        for step in self.steps:
            current = getattr(current, step.attr)
            if step.call:
                current = current()
            if step.has_key:
                current = current[step.key]
        return current


def routes() -> dict[str, Route]:
    """The shortest route to an entity of each entity type."""
    found: dict[str, Route] = {}
    queue: deque[tuple[str, Route]] = deque()
    for s in DOC["container"]["entitySets"]:
        key, text = key_of(TYPES[s["entityType"]])
        queue.append((s["entityType"], Route((Step(index.ENTITY_SETS[s["name"]], key=key, has_key=True),), s["name"] + text)))
    for s in DOC["container"]["singletons"]:
        queue.append((s["type"], Route((Step(index.SINGLETONS[s["name"]]),), s["name"])))
    while queue:
        qname, route = queue.popleft()
        if qname in found:
            continue
        found[qname] = route
        t = TYPES[qname]
        for d in t["derivedTypes"]:
            step = Step(index.CASTS[(qname, d)], call=True)
            queue.append((d, Route(route.steps + (step,), route.path + "/" + alias(d))))
        for level in chain(t):
            for n in level["navigationProperties"]:
                target_type = TYPES.get(n["type"])
                if target_type is None or target_type["kind"] != "entity":
                    continue
                attr = index.NAVIGATION[(level["qualifiedName"], n["name"])]
                if n["collection"]:
                    key, text = key_of(target_type)
                    step = Step(attr, key=key, has_key=True)
                    queue.append((n["type"], Route(route.steps + (step,), f"{route.path}/{n['name']}{text}")))
                else:
                    queue.append((n["type"], Route(route.steps + (Step(attr),), f"{route.path}/{n['name']}")))
    return found


ROUTES = routes()


def respond_for(ref: dict[str, Any] | None) -> Any:
    if ref is None:
        return None
    if ref["collection"]:
        return {"value": []}
    if ref["type"] in TYPES:
        return {}
    return {"value": None}


# The cases: one per endpoint


def endpoint_ids(kind: str) -> list[str]:
    return [e["id"] for e in DOC["endpoints"] if e["kind"] == kind]


def call(api: Api, owner: Any, method: str, op: dict[str, Any], endpoint_id: str) -> dict[str, Any]:
    names = index.PARAMETERS[endpoint_id]
    kwargs, expected = {}, {}
    for p in op["parameters"]:
        if p["nullable"]:
            continue  # optional ones are left out
        value, json_value = sample(p)
        kwargs[names[p["name"]]] = value
        expected[p["name"]] = json_value
    api.run(getattr(owner, method)(**kwargs))
    return expected


@pytest.mark.parametrize("endpoint_id", endpoint_ids("EntitySet"))
def test_entity_set(endpoint_id: str, api: Api, fake: FakeTm1) -> None:
    name = endpoint_id.split(" ", 1)[1]
    collection = getattr(api.api, index.ENTITY_SETS[name])
    fake.respond = lambda r: {"value": []}
    assert api.run(collection.list()) == []
    assert target(fake.last) == f"GET {name}"
    fake.respond = lambda r: {}
    entity_set = next(s for s in DOC["container"]["entitySets"] if s["name"] == name)
    key, text = key_of(TYPES[entity_set["entityType"]])
    api.run(collection[key].get())
    assert target(fake.last) == f"GET {name}{text}"
    fake.respond = lambda r: "7"
    assert api.run(collection.count()) == 7
    assert target(fake.last) == f"GET {name}/$count"


@pytest.mark.parametrize("endpoint_id", endpoint_ids("Singleton"))
def test_singleton(endpoint_id: str, api: Api, fake: FakeTm1) -> None:
    name = endpoint_id.split(" ", 1)[1]
    api.run(getattr(api.api, index.SINGLETONS[name]).get(select="ID"))
    assert target(fake.last) == f"GET {name}?$select=ID"


@pytest.mark.parametrize("endpoint_id", endpoint_ids("Navigation"))
def test_navigation(endpoint_id: str, api: Api, fake: FakeTm1) -> None:
    e = next(x for x in DOC["endpoints"] if x["id"] == endpoint_id)
    owner = TYPES[e["owner"]]
    nav = next(n for n in owner["navigationProperties"] if n["name"] == e["name"])
    if e["ownerKind"] == "complex":
        # Read through $expand into the complex value's field
        cls = getattr(model, owner["name"])
        field = next(f for f, info in cls.model_fields.items() if info.alias == e["name"])
        value = cls.model_validate({e["name"]: [] if nav["collection"] else {}})
        assert getattr(value, field) is not None
        return
    route = ROUTES[e["owner"]]
    resource = getattr(route.walk(api.api), index.NAVIGATION[(e["owner"], e["name"])])
    if nav["collection"]:
        fake.respond = lambda r: {"value": []}
        api.run(resource.list())
    else:
        fake.respond = lambda r: {}
        api.run(resource.get())
    assert target(fake.last) == f"GET {route.path}/{e['name']}"


@pytest.mark.parametrize("endpoint_id", endpoint_ids("Action") + endpoint_ids("Function"))
def test_bound_operation(endpoint_id: str, api: Api, fake: FakeTm1) -> None:
    if endpoint_id not in _V11_ENDPOINTS:
        fake.metadata = _V12_METADATA
    op = OPERATIONS[endpoint_id]
    binding = op["binding"]
    assert not binding["collection"], "no TM1 operation is bound to a collection yet: route one here"
    route = ROUTES[binding["type"]]
    cls_name, method = index.OPERATIONS[endpoint_id]
    owner = route.walk(api.api)
    assert type(owner).__name__ == cls_name or cls_name in [c.__name__ for c in type(owner).__mro__]
    fake.respond = lambda r: respond_for(op["returns"])
    expected = call(api, owner, method, op, endpoint_id)
    check_operation(fake.last, op, route.path + "/" + op["pathName"], expected)


@pytest.mark.parametrize("endpoint_id", endpoint_ids("ActionImport") + endpoint_ids("FunctionImport"))
def test_unbound_operation(endpoint_id: str, api: Api, fake: FakeTm1) -> None:
    if endpoint_id not in _V11_ENDPOINTS:
        fake.metadata = _V12_METADATA
    e = next(x for x in DOC["endpoints"] if x["id"] == endpoint_id)
    op = UNBOUND[e["operation"]]
    cls_name, method = index.OPERATIONS[endpoint_id]
    assert cls_name == "Tm1Api"
    fake.respond = lambda r: respond_for(op["returns"])
    expected = call(api, api.api, method, op, endpoint_id)
    check_operation(fake.last, op, e["name"], expected)


def check_operation(request: httpx2.Request, op: dict[str, Any], path: str, expected: dict[str, Any]) -> None:
    if op["kind"] == "action":
        assert target(request) == f"POST {path}"
        body = json.loads(request.content) if request.content else {}
        sent = {k.removesuffix("@odata.bind"): v for k, v in body.items()}
        assert sent == expected
        for k in body:
            if k.endswith("@odata.bind"):
                param = next(p for p in op["parameters"] if p["name"] == k.removesuffix("@odata.bind"))
                assert TYPES[param["type"]]["kind"] == "entity"
    else:
        required = [p for p in op["parameters"] if not p["nullable"]]
        structured = [p for p in required if p["type"] in TYPES]
        arguments = ",".join(f"{p['name']}=@{p['name']}" if p in structured else f"{p['name']}={path_text(arg_text(p))}" for p in required)
        sent = target(request)
        assert sent.split("?")[0] == f"GET {path}({arguments})"
        for p in structured:
            # Passed as JSON through a parameter alias
            assert json.loads(request.url.params[f"@{p['name']}"]) == expected[p["name"]]


def arg_text(p: dict[str, Any]) -> str:
    if p["type"] in ENUMS:
        e = ENUMS[p["type"]]
        member = e["members"][0]["name"]
        return f"'{member}'" if e["flags"] else f"{e['qualifiedName']}'{member}'"
    return SAMPLES[p["type"]][1]


def test_every_endpoint_has_a_case() -> None:
    kinds = {"EntitySet", "Singleton", "Navigation", "Action", "Function", "ActionImport", "FunctionImport"}
    assert {e["kind"] for e in DOC["endpoints"]} <= kinds
    unreachable = sorted(
        e["id"]
        for e in DOC["endpoints"]
        if (e["kind"] == "Navigation" and e["ownerKind"] == "entity" and e["owner"] not in ROUTES)
        or (e["kind"] in ("Action", "Function") and OPERATIONS[e["id"]]["binding"]["type"] not in ROUTES)
    )
    assert not unreachable, f"no route from the root to these endpoints' types: {unreachable}"
