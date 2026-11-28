# SPDX-License-Identifier: Apache-2.0

"""Emits ``skipcheck_tm1/_generated/api.py``: a resource class per entity type and the root of
the API, with a member for every endpoint of the spec, and ``index.py``, which says which
member implements which endpoint (the contract tests walk it)."""

from __future__ import annotations

from typing import Any

from . import names
from .docs import docstring, md, revisions
from .emit_model import python_type
from .spec import Spec

# The flavor being emitted: "sync" (api.py) or "async" (aio_api.py). The two differ only in
# how operations and media methods are declared and called; navigation stays a property.
_FLAVOR = "sync"


def _def() -> str:
    return "async def" if _FLAVOR == "async" else "def"


def _await() -> str:
    return "await " if _FLAVOR == "async" else ""


class _Index:
    """What the generated members are called, for index.py."""

    def __init__(self) -> None:
        self.entity_sets: dict[str, str] = {}
        self.singletons: dict[str, str] = {}
        self.navigation: dict[tuple[str, str], str] = {}
        self.casts: dict[tuple[str, str], str] = {}
        self.operations: dict[str, tuple[str, str]] = {}
        self.parameters: dict[str, dict[str, str]] = {}
        self.entity_classes: dict[str, str] = {}
        self.collection_classes: dict[str, str] = {}


def _forbid(spec: Spec, endpoint_id: str) -> str:
    note = spec.endpoint_note(endpoint_id)
    forbid = note.get("forbid", [])
    if not forbid:
        return ""
    reason = " ".join((note.get("note") or "TM1 doesn't do what OData says").split())
    return f"dict.fromkeys({tuple(forbid)!r}, {reason!r})"


def _endpoint_doc(spec: Spec, endpoint_id: str, head: str, item: dict[str, Any] | None = None) -> str:
    doc = head
    if item is not None:
        if item.get("description"):
            doc += "\n\n" + item["description"]
        if item.get("longDescription"):
            doc += "\n\n" + item["longDescription"]
        doc += revisions(item)
    note = spec.endpoint_note(endpoint_id).get("note")
    if note:
        doc += "\n\n" + md(note)
    return doc + f"\n\nEndpoint ``{endpoint_id}``."


def _returns(spec: Spec, ref: dict[str, Any] | None) -> tuple[str, str]:
    """The annotation of a method returning ``ref``, and the ``Returns`` reading it."""
    if ref is None:
        return "None", "_r.Returns()"
    tp = _qualify(spec, python_type(spec, {"type": ref["type"], "collection": False}) or "bytes")
    structured = spec.is_structured(ref["type"])
    if ref["collection"]:
        return f"list[{tp}]", f"_r.Returns({tp}, collection=True, structured={structured})"
    return f"{tp} | None", f"_r.Returns({tp}, structured={structured})"


def _param_type(spec: Spec, p: dict[str, Any]) -> str:
    return _qualify(spec, python_type(spec, p, bindable=spec.is_entity(p["type"])) or "bytes")


def _qualify(spec: Spec, annotation: str) -> str:
    known = {t["name"] for t in spec.types} | {e["name"] for e in spec.enums}
    out = []
    token = ""
    for c in annotation + " ":
        if c.isalnum() or c == "_":
            token += c
            continue
        if token:
            out.append(f"m.{token}" if token in known else token)
            token = ""
        out.append(c)
    return "".join(out).rstrip()


def _operation(spec: Spec, op: dict[str, Any], endpoint_id: str, method: str, index: _Index, call_name: str) -> list[str]:
    taken = {"self", "query", "options"}
    params = []
    for p in op["parameters"]:
        pname = names.unique(names.snake(p["name"]), taken)
        params.append((pname, p))
    index.parameters[endpoint_id] = {p["name"]: pname for pname, p in params}
    annotation, returns = _returns(spec, op["returns"])
    # $select and $expand apply to what an operation returns, when that's structured
    takes_query = bool(op["returns"]) and spec.is_structured(op["returns"]["type"])
    signature = ["self"]
    if params:
        signature.append("*")
    required = [(n, p) for n, p in params if not p["nullable"]]
    optional = [(n, p) for n, p in params if p["nullable"]]
    for pname, p in required:
        signature.append(f"{pname}: {_param_type(spec, p)}")
    for pname, p in optional:
        signature.append(f"{pname}: {_param_type(spec, p)} | None = _r.UNSET")
    if takes_query:
        if not params:
            signature.append("*")
        signature.append("query: ODataQuery | None = None")
        signature.append("**options: Any")
    out = ["", f"    @endpoint({endpoint_id!r})", f"    {_def()} {method}({', '.join(signature)}) -> {annotation}:"]
    kind = "action" if op["kind"] == "action" else "function"
    verb = "POST" if kind == "action" else "GET"
    head = f"``{verb} …/{call_name}``" if op["bound"] else f"``{verb} {call_name}``"
    doc = _endpoint_doc(spec, endpoint_id, head + ": the " + kind + ".", op)
    if params:
        doc += "\n\nParameters (left out when not passed; ``None`` sends ``null``):"
        for pname, p in params:
            line = f"\n\n* ``{pname}`` (``{p['name']}``, ``{'Collection(' + p['type'] + ')' if p['collection'] else p['type']}``)"
            if p.get("description"):
                line += ": " + p["description"]
            doc += line
    out.extend(docstring(doc, "        "))
    args = "{" + ", ".join(f"{p['name']!r}: {pname}" for pname, p in params) + "}"
    method_name = "_action" if kind == "action" else "_function"
    query = ", _r.query_of(query, options)" if takes_query else ", None"
    out.append(f"        return {_await()}self.{method_name}({call_name!r}, {args}, {returns}{query}, {endpoint_id!r})")
    return out


def _free(name: str, suffix: str, taken: set[str]) -> str:
    """``name``, or with ``suffix`` when a base class has it: the bound action ``Update`` is
    ``update_action``, as ``update`` is the generic PATCH; a navigation ``Session``, ``session_``."""
    return name + suffix if name in taken else name


def _member_names(spec: Spec, t: dict[str, Any]) -> dict[str, str]:
    """Names of a resource class's members, by ``nav:<Name>``, ``op:<endpoint id>`` and
    ``cast:<qualified name>``, unique against the bases' and the hierarchy's."""
    taken = set(names.RESOURCE_RESERVED)
    out: dict[str, str] = {}
    for level in spec.chain(t):
        for n in level["navigationProperties"]:
            out[f"nav:{n['name']}"] = names.unique(_free(names.snake(n["name"]), "_", taken), taken)
        for op in spec.bound_to(level["qualifiedName"]):
            out[f"op:{op['endpoint']}"] = names.unique(_free(names.snake(op["name"]), "_" + op["kind"], taken), taken)
        for d in spec.derived(level):
            out[f"cast:{d['qualifiedName']}"] = names.unique("as_" + names.snake(d["name"]), taken)
        if level.get("hasStream"):
            out["media:read"] = names.unique("read_media", taken)
            out["media:write"] = names.unique("write_media", taken)
        for p in level["properties"]:
            if p["type"] == "Edm.Stream":
                out[f"stream-read:{p['name']}"] = names.unique("read_" + names.snake(p["name"]), taken)
                out[f"stream-write:{p['name']}"] = names.unique("write_" + names.snake(p["name"]), taken)
    return out


def _entity_class(spec: Spec, t: dict[str, Any], index: _Index) -> list[str]:
    cls = names.entity_class(t["name"])
    base = names.entity_class(spec.type_by_name[t["baseType"]]["name"]) if t.get("baseType") else f"_r.EntityResource[m.{t['name']}]"
    index.entity_classes[t["qualifiedName"]] = cls
    members = _member_names(spec, t)
    out = ["", "", f"class {cls}({base}):"]
    out.extend(
        docstring(
            f"An entity of type :class:`~skipcheck_tm1._generated.model.{t['name']}`: ``get``, ``update``, ``delete``, its navigation properties and bound operations.",
            "    ",
        )
    )
    out.append("")
    out.append(f"    model = m.{t['name']}")
    for n in t["navigationProperties"]:
        endpoint_id = f"Navigation {t['name']}/{n['name']}"
        name = members[f"nav:{n['name']}"]
        index.navigation[(t["qualifiedName"], n["name"])] = name
        target = spec.type_by_name.get(n["type"])
        if target is None or target["kind"] != "entity":
            continue
        forbid = _forbid(spec, endpoint_id)
        if n["collection"]:
            rtype = names.collection_class(target["name"])
            ctor = f"{rtype}(self._session, self._path.nav({n['name']!r}){', ' + forbid if forbid else ''}, contained={n['containsTarget']})"
            what = "the collection"
        else:
            rtype = names.entity_class(target["name"])
            extra = forbid
            if not n["containsTarget"]:
                refused = "{'delete': 'it refers to an entity kept elsewhere; delete it through its own collection'}"
                extra = f"{{**{refused}, **{forbid}}}" if forbid else refused
            ctor = f"{rtype}(self._session, self._path.nav({n['name']!r}){', ' + extra if extra else ''})"
            what = "the entity"
        out.append("")
        out.append("    @property")
        if endpoint_id in spec.endpoint_by_id:
            out.append(f"    @endpoint({endpoint_id!r})")
        out.append(f"    def {name}(self) -> {rtype}:")
        out.extend(
            docstring(
                _endpoint_doc(
                    spec,
                    endpoint_id,
                    f"``…/{n['name']}``: {what}"
                    + (" (contained)." if n["containsTarget"] else " (a reference: read through here, change through its own collection)."),
                    n,
                ),
                "        ",
            )
        )
        out.append(f"        return {ctor}")
    for op in spec.bound_to(t["qualifiedName"]):
        name = members[f"op:{op['endpoint']}"]
        index.operations[op["endpoint"]] = (cls, name)
        out.extend(_operation(spec, op, op["endpoint"], name, index, op["pathName"]))
    for d in spec.derived(t):
        name = members[f"cast:{d['qualifiedName']}"]
        index.casts[(t["qualifiedName"], d["qualifiedName"])] = name
        alias = spec.alias_name(d["qualifiedName"])
        out.append("")
        out.append(f"    def {name}(self) -> {names.entity_class(d['name'])}:")
        out.extend(
            docstring(
                f"``…/{alias}``: this entity as a :class:`~skipcheck_tm1._generated.model.{d['name']}`, for its own properties, navigation and operations.",
                "        ",
            )
        )
        out.append(f"        return {names.entity_class(d['name'])}(self._session, self._path.cast({alias!r}), self._forbidden)")
    if t.get("hasStream"):
        out.append("")
        out.append(f"    {_def()} {members['media:read']}(self) -> bytes:")
        out.extend(docstring("``GET …/$value``: the entity's media content.", "        "))
        out.append(f"        return {_await()}self._read_stream(None)")
        out.append("")
        out.append(f'    {_def()} {members["media:write"]}(self, data: bytes, content_type: str = "application/octet-stream") -> None:')
        out.extend(docstring("``PUT …/$value``: replaces the entity's media content.", "        "))
        out.append(f"        {_await()}self._write_stream(None, data, content_type)")
    for p in t["properties"]:
        if p["type"] == "Edm.Stream":
            out.append("")
            out.append(f"    {_def()} {members['stream-read:' + p['name']]}(self) -> bytes:")
            out.extend(docstring(f"``GET …/{p['name']}/$value``" + (": " + p["description"] if p.get("description") else "."), "        "))
            out.append(f"        return {_await()}self._read_stream({p['name']!r})")
            out.append("")
            out.append(f'    {_def()} {members["stream-write:" + p["name"]]}(self, data: bytes, content_type: str = "application/octet-stream") -> None:')
            out.extend(docstring(f"``PUT …/{p['name']}/$value``: replaces it.", "        "))
            out.append(f"        {_await()}self._write_stream({p['name']!r}, data, content_type)")
    return out


def _collection_class(spec: Spec, t: dict[str, Any], index: _Index) -> list[str]:
    cls = names.collection_class(t["name"])
    index.collection_classes[t["qualifiedName"]] = cls
    root = spec.root(t)
    out = ["", "", f"class {cls}(_r.CollectionResource[m.{t['name']}, {names.entity_class(t['name'])}]):"]
    out.extend(
        docstring(
            f"A collection of :class:`~skipcheck_tm1._generated.model.{t['name']}`: ``list``, ``iter``, ``count``, ``create``, and ``[key]`` for one.", "    "
        )
    )
    out.append("")
    out.append(f"    model = m.{t['name']}")
    out.append(f"    entity = {names.entity_class(t['name'])}")
    out.append(f"    key_names = {tuple(root.get('key', []))!r}")
    for op in spec.bound_to(t["qualifiedName"], collection=True):
        name = names.snake(op["name"])
        index.operations[op["endpoint"]] = (cls, name)
        out.extend(_operation(spec, op, op["endpoint"], name, index, op["pathName"]))
    return out


def _root(spec: Spec, index: _Index) -> list[str]:
    out = ["", "", "class Tm1Api(_r.Resource):"]
    out.extend(
        docstring(
            "The root of the TM1 REST API: every entity set, singleton and unbound operation."
            "\n\nThe generated, complete layer: one member per endpoint of the spec. :class:`~skipcheck_tm1.Tm1Client` adds hand-written conveniences on top.",
            "    ",
        )
    )
    out.append("")
    out.append("    def __init__(self, session: Tm1Session):")
    out.append("        super().__init__(session, ODataPath())")
    taken = set(names.RESOURCE_RESERVED) | {"close"}
    c = spec.container
    for s in c["entitySets"]:
        endpoint_id = f"EntitySet {s['name']}"
        name = names.unique(names.snake(s["name"]), taken)
        index.entity_sets[s["name"]] = name
        t = spec.type_by_name[s["entityType"]]
        rtype = names.collection_class(t["name"])
        forbid = _forbid(spec, endpoint_id)
        out.append("")
        out.append("    @property")
        if endpoint_id in spec.endpoint_by_id:
            out.append(f"    @endpoint({endpoint_id!r})")
        out.append(f"    def {name}(self) -> {rtype}:")
        out.extend(docstring(_endpoint_doc(spec, endpoint_id, f"``{s['name']}``: the entity set.", s), "        "))
        out.append(f"        return {rtype}(self._session, ODataPath.of({s['name']!r}){', ' + forbid if forbid else ''})")
    for s in c["singletons"]:
        endpoint_id = f"Singleton {s['name']}"
        name = names.unique(names.snake(s["name"]), taken)
        index.singletons[s["name"]] = name
        t = spec.type_by_name[s["type"]]
        rtype = names.entity_class(t["name"])
        out.append("")
        out.append("    @property")
        if endpoint_id in spec.endpoint_by_id:
            out.append(f"    @endpoint({endpoint_id!r})")
        out.append(f"    def {name}(self) -> {rtype}:")
        out.extend(docstring(_endpoint_doc(spec, endpoint_id, f"``{s['name']}``: the singleton.", s), "        "))
        out.append(f"        return {rtype}(self._session, ODataPath.of({s['name']!r}), {{'delete': 'a singleton can\\'t be deleted'}})")
    imports = [("ActionImport", i) for i in c["actionImports"]] + [("FunctionImport", i) for i in c["functionImports"]]
    for kind, i in sorted(imports, key=lambda k: k[1]["name"]):
        endpoint_id = f"{kind} {i['name']}"
        op = spec.unbound_operation[i["operation"]]
        name = names.unique(_free(names.snake(i["name"]), "_" + op["kind"], taken), taken)
        index.operations[endpoint_id] = ("Tm1Api", name)
        out.extend(_operation(spec, op, endpoint_id, name, index, i["name"]))
    return out


def emit(spec: Spec, flavor: str = "sync") -> tuple[str, str]:
    """The API module of a flavor, ``sync`` or ``async``, and the index (the same for both)."""
    global _FLAVOR
    if flavor not in ("sync", "async"):
        raise ValueError(flavor)
    _FLAVOR = flavor
    try:
        return _emit(spec)
    finally:
        _FLAVOR = "sync"


def _emit(spec: Spec) -> tuple[str, str]:
    index = _Index()
    asyncio = _FLAVOR == "async"
    out = [
        f'"""The TM1 REST API (schema {spec.schema_version}) as {"asyncio " if asyncio else ""}resources, generated from',
        "spec/tm1-api.json by tm1_pygen: don't edit.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from datetime import date, datetime, time, timedelta",
        "from decimal import Decimal",
        "from typing import Any",
        "from uuid import UUID",
        "",
        "from ..aio import resources as _r" if asyncio else "from .. import resources as _r",
        "from ..odata import Bind, ODataPath, ODataQuery",
        "from ..resources import endpoint",
        "from ..aio.session import AsyncTm1Session as Tm1Session" if asyncio else "from ..session import Tm1Session",
        "from . import model as m",
        "",
        '__all__ = ["Tm1Api"]',
    ]
    entities = [t for t in spec.types if t["kind"] == "entity"]
    ordered = sorted(entities, key=lambda t: (len(spec.chain(t)), t["name"]))
    for t in ordered:
        out.extend(_entity_class(spec, t, index))
    for t in ordered:
        out.extend(_collection_class(spec, t, index))
    out.extend(_root(spec, index))
    out.append("")
    return "\n".join(out), _index(spec, index)


def _index(spec: Spec, index: _Index) -> str:
    def block(name: str, value: Any, doc: str) -> list[str]:
        lines = [f"{name} = {{"]
        for k in sorted(value, key=str):
            lines.append(f"    {k!r}: {value[k]!r},")
        lines.append("}")
        lines.append(f'"""{doc}"""')
        lines.append("")
        return lines

    out = [
        '"""Which generated member implements which endpoint, generated by tm1_pygen: don\'t edit.',
        "",
        "Read by the contract tests, which walk the API by these names to call every endpoint.",
        '"""',
        "",
        f"SCHEMA_VERSION = {spec.schema_version!r}",
        "",
    ]
    out += block("ENTITY_SETS", index.entity_sets, "Tm1Api's property for each entity set.")
    out += block("SINGLETONS", index.singletons, "Tm1Api's property for each singleton.")
    out += block("NAVIGATION", index.navigation, "The property for each navigation property, by (owner type, name).")
    out += block("CASTS", index.casts, "The cast method, by (type, derived type).")
    out += block("OPERATIONS", index.operations, "The (class, method) for each operation, by endpoint ID.")
    out += block("PARAMETERS", index.parameters, "Each operation's parameters' Python names, by TM1's, by endpoint ID.")
    since = {o["endpoint"]: o["since"] for o in spec.operations if o["bound"] and o.get("since")}
    for kind, key in (("ActionImport", "actionImports"), ("FunctionImport", "functionImports")):
        for i in spec.container[key]:
            op = spec.unbound_operation[i["operation"]]
            if op.get("since"):
                since[f"{kind} {i['name']}"] = op["since"]
    out += block("SINCE", since, "The schema version each operation was added in, by endpoint ID.")
    out += block("ENTITY_CLASSES", index.entity_classes, "The resource class for each entity type.")
    out += block("COLLECTION_CLASSES", index.collection_classes, "The collection class for each entity type.")
    return "\n".join(out)
