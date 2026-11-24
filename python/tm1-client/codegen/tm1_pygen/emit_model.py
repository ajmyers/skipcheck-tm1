# SPDX-License-Identifier: Apache-2.0

"""Emits ``skipcheck_tm1/_generated/model.py``: an enum per CSDL enum type and a pydantic model
per entity and complex type, the Python counterpart of the Java client's generated model."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from . import names
from .docs import docstring, md, revisions
from .spec import Spec

EDM = {
    "Edm.String": "str",
    "Edm.Boolean": "bool",
    "Edm.Byte": "int",
    "Edm.SByte": "int",
    "Edm.Int16": "int",
    "Edm.Int32": "int",
    "Edm.Int64": "int",
    "Edm.Single": "float",
    "Edm.Double": "float",
    "Edm.Decimal": "Decimal",
    "Edm.Date": "date",
    "Edm.TimeOfDay": "time",
    "Edm.DateTimeOffset": "datetime",
    "Edm.Duration": "timedelta",
    "Edm.Guid": "UUID",
    "Edm.PrimitiveType": "Any",
}


def element_type(spec: Spec, type_name: str) -> str | None:
    """The Python type for a CSDL type, or None for one that isn't in JSON (streams)."""
    if type_name == "Edm.Stream":
        return None
    if type_name in EDM:
        return EDM[type_name]
    enum = spec.enum_by_name.get(type_name)
    if enum is not None:
        # Flags enums arrive comma-separated
        return "str" if enum["flags"] else enum["name"]
    t = spec.type_by_name.get(type_name)
    if t is not None:
        return t["name"]
    return "Any"


def python_type(spec: Spec, ref: dict[str, Any], *, bindable: bool = False) -> str | None:
    element = element_type(spec, ref["type"])
    if element is None:
        return None
    if bindable:
        element = f"{element} | Bind"
    return f"list[{element}]" if ref["collection"] else element


def field_names(spec: Spec, t: dict[str, Any]) -> dict[tuple[str, str], str]:
    """Python field names for a type's properties and navigation properties, by
    ``(kind, name)``, unique across its hierarchy (root first)."""
    taken: set[str] = set()
    out: dict[tuple[str, str], str] = {}
    for level in spec.chain(t):
        for p in level["properties"]:
            out[("p", p["name"])] = names.unique(names.field_name(p["name"]), taken)
        for n in level["navigationProperties"]:
            out[("n", n["name"])] = names.unique(names.field_name(n["name"]), taken)
    return out


def emit(spec: Spec) -> str:
    out: list[str] = [
        f'"""The TM1 REST API\'s model (schema {spec.schema_version}), generated from spec/tm1-api.json by',
        "tm1_pygen: don't edit.",
        "",
        "Every field is optional: ``$select`` can leave any property out, and navigation properties",
        "are only there when expanded. Fields are named in snake_case; TM1's names are their aliases,",
        "and either is accepted. Writes send only the fields set (see :mod:`skipcheck_tm1.base`).",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from datetime import date, datetime, time, timedelta",
        "from decimal import Decimal",
        "from typing import Annotated, Any, ClassVar",
        "from uuid import UUID",
        "",
        "from pydantic import ConfigDict, Field",
        "",
        "from ..base import LenientDateTime, Tm1Enum, Tm1Model",
        "from ..odata import Bind, ODataProperty",
        "from .._calls import register_endpoint",
        "from ..version import Tm1Departure",
        "",
        "__all__ = [",
    ]
    for e in spec.enums:
        out.append(f'    "{e["name"]}",')
    for t in spec.types:
        out.append(f'    "{t["name"]}",')
    out.append("]")
    out.append("")

    for e in spec.enums:
        out.extend(_enum(e))
    # Bases before subtypes: roots first, then the rest by name
    ordered = sorted(spec.types, key=lambda t: (t.get("baseType") is not None, t["name"]))
    for t in ordered:
        out.extend(_model(spec, t))

    out.append("")
    out.append("# Subtypes, by @odata.type, on the roots of hierarchies")
    for t in ordered:
        if t["derivedTypes"]:
            entries = []
            for d in spec.derived(t):
                entries.append(f'"{d["qualifiedName"]}": {d["name"]}')
                entries.append(f'"{spec.alias_name(d["qualifiedName"])}": {d["name"]}')
            out.append(f"{t['name']}.odata_subtypes = {{{', '.join(entries)}}}")
    out.append("")
    out.append("for _model in (" + ", ".join(t["name"] for t in ordered) + "):")
    out.append("    _model.model_rebuild()")
    out.append("")
    out.append("# Navigation properties of complex types aren't paths of their own: they're read")
    out.append("# through $expand on the entity holding the complex value, into these fields.")
    for e in spec.endpoints:
        if e["kind"] == "Navigation" and e["ownerKind"] == "complex":
            owner = spec.type_by_name[e["owner"]]
            fname = field_names(spec, owner)[("n", e["name"])]
            out.append(f'register_endpoint("{e["id"]}", "skipcheck_tm1._generated.model.{owner["name"]}.{fname} ($expand)")')
    out.append("")
    return "\n".join(out)


def _enum(e: dict[str, Any]) -> list[str]:
    out = ["", "", f"class {e['name']}(Tm1Enum):"]
    head = e.get("description") or f"The ``{e['name']}`` enum type of the TM1 REST API."
    out.extend(
        docstring(
            head + revisions(e) + "\n\nA value this client doesn't know reads as the fallback member, which is never sent.",
            "    ",
        )
    )
    taken: set[str] = set()
    members = []
    for m in e["members"]:
        constant = names.unique(names.constant(m["name"]), taken)
        members.append((constant, m))
    fallback = "UNRECOGNIZED" if "UNKNOWN" in taken else "UNKNOWN"
    out.append(f'    __odata_prefix__ = "{e["qualifiedName"]}"')
    out.append(f'    __odata_fallback__ = "{fallback}"')
    out.append("")
    for constant, m in members:
        out.append(f"    {constant} = {m['name']!r}")
        doc = (m.get("description") or "") + revisions(m)
        if doc.strip():
            out.extend(docstring(doc.strip(), "    "))
    out.append(f'    {fallback} = "\\x00unknown"')
    out.extend(docstring("A value this client doesn't know.", "    "))
    return out


def departure_tag(p: dict[str, Any]) -> str | None:
    """The ``Tm1Departure`` a property's field carries, as Java's ``@Tm1Departure``, or None."""
    if p.get("undeclared"):
        u = p["undeclared"]
        kind, observed, note = "UNDECLARED", tuple(u.get("observedIn", [])), p.get("description") or ""
    elif p.get("departure"):
        kind, observed, note = "TYPE", (), p["departure"].get("note", "")
    elif p.get("lenientDate"):
        kind, observed, note = "LENIENT_DATE", (), p["lenientDate"].get("note", "")
    else:
        return None
    return f"Tm1Departure(Tm1Departure.Kind.{kind}, {observed!r}, {note.replace('`', '')!r})"


def _model(spec: Spec, t: dict[str, Any]) -> list[str]:
    base = spec.type_by_name[t["baseType"]]["name"] if t.get("baseType") else "Tm1Model"
    root = spec.root(t)
    out = ["", "", f"class {t['name']}({base}):"]
    kind = "entity" if t["kind"] == "entity" else "complex"
    head = t.get("description") or f"The ``{t['name']}`` {kind} type of the TM1 REST API."
    if t.get("longDescription"):
        head += "\n\n" + t["longDescription"]
    if t.get("baseType"):
        head += f"\n\nA kind of :class:`{root['name']}`, read and sent with ``@odata.type`` ``#{t['qualifiedName']}``."
    if t["derivedTypes"]:
        kinds = ", ".join(f":class:`{d['name']}`" for d in spec.derived(t))
        head += f"\n\nReads as one of {kinds} by ``@odata.type``" + (
            "; as this class itself when the type is one this client doesn't know." if t["abstract"] else ", or as this class itself."
        )
    out.extend(docstring(head + revisions(t), "    "))
    if t["open"] and not (t.get("baseType") and spec.type_by_name[t["baseType"]]["open"]):
        out.append("")
        out.append('    model_config = ConfigDict(extra="allow")')
        out.append("    # An open type: properties $metadata doesn't declare, such as attribute values, are kept")
        out.append("    # in model_extra, by TM1's names, and sent back as set.")
    out.append("")
    out.append(f'    odata_name: ClassVar[str] = "{t["qualifiedName"]}"')
    if t.get("baseType"):
        out.append(f'    odata_type: ClassVar[str | None] = "#{t["qualifiedName"]}"')
    fields = field_names(spec, t)
    if t["kind"] == "entity" and not t.get("baseType"):
        out.append("")
        out.append('    etag: str | None = Field(None, alias="@odata.etag")')
        out.extend(docstring("The entity's ETag (``@odata.etag``), for ``if_match``.", "    "))
    constants: list[tuple[str, str]] = []
    for p in t["properties"]:
        departure = p.get("departure")
        if departure:
            tp: str | None = "list[Any]" if p["collection"] else "Any"
        elif p.get("lenientDate"):
            tp = "LenientDateTime"
        else:
            tp = python_type(spec, p)
        if tp is None:
            continue
        name = fields[("p", p["name"])]
        out.append("")
        tag = departure_tag(p)
        annotated = f"Annotated[{tp} | None, {tag}]" if tag else f"{tp} | None"
        out.append(f'    {name}: {annotated} = Field(None, alias="{p["name"]}")')
        doc = p.get("description") or f"The ``{p['name']}`` property."
        if departure:
            doc += "\n\n" + md(departure["note"])
        if p.get("lenientDate"):
            doc += "\n\n" + md(p["lenientDate"]["note"])
        out.extend(docstring(doc + revisions(p), "    "))
        constants.append((names.constant(p["name"]), p["name"]))
    for n in t["navigationProperties"]:
        tp = python_type(spec, n, bindable=spec.is_entity(n["type"]))
        if tp is None:
            continue
        name = fields[("n", n["name"])]
        out.append("")
        out.append(f'    {name}: {tp} | None = Field(None, alias="{n["name"]}")')
        doc = (n.get("description") or f"The ``{n['name']}`` navigation property.") + " (When expanded.)"
        if spec.is_entity(n["type"]):
            doc += " To refer to existing entities in a write, set :class:`~skipcheck_tm1.odata.Bind` values."
        out.extend(docstring(doc + revisions(n), "    "))
        constants.append((names.constant(n["name"]), n["name"]))
    if constants:
        out.append("")
        out.append("    # Its own properties, for queries and filters (Cube.NAME), as in the Java client")
    taken: set[str] = set()
    for constant, prop in constants:
        out.append(f'    {names.unique(constant, taken)}: ClassVar[ODataProperty] = ODataProperty("{prop}")')
    return out


WRITES = {"applied", "writeOnly", "transformed", "untested"}


def _writes(outcome: str | None) -> bool:
    return outcome is None or outcome in WRITES


def creates(t: dict[str, Any], prop: str, spec: Spec | None = None) -> bool:
    """Whether a POST of ``t`` takes ``prop``, by the probe's writes (yes, for a type it has none for). For
    the root of a hierarchy without its own, whether every subtype with writes takes it, so each
    subtype's create offers at least what its root's does."""
    w = t.get("write")
    if w is None and spec is not None:
        probed = [d for d in spec.derived(t) if d.get("write")]
        return all(creates(d, prop) for d in probed)
    return w is None or _writes(w["create"]["properties"].get(prop))


def patches(t: dict[str, Any], prop: str) -> bool:
    """Whether ``builder(read)`` copies ``prop`` into a patch of ``t``: a PATCH applies it, and it isn't a
    collection a PATCH appends to (copying one would add its members again), ignores or rejects. A
    collection a PATCH replaces is copied: the read holds all of it. As the Java generator."""
    w = t.get("write")
    if w is None:
        return True
    collection = w.get("collections", {}).get(prop)
    return _writes(w["patch"]["properties"].get(prop)) and collection not in ("appends", "ignored", "rejected")


def _offers_in_patch(w: dict[str, Any], prop: str) -> bool:
    if prop == "Name":
        return w["patch"].get("rename", "applied") == "applied"
    outcome = w["patch"]["properties"].get(prop)
    collection = w.get("collections", {}).get(prop)
    return outcome not in ("rejected", "ignored") and collection not in ("rejected", "ignored")


def offers_in_patch(spec: Spec, t: dict[str, Any], prop: str) -> bool:
    """Whether a patch of ``t`` offers ``prop``: not if a PATCH rejects or ignores it, by the probe, nor
    ``Name`` where a PATCH doesn't rename; for the root of a hierarchy without writes of its own, if
    every probed subtype's patch offers it. As the Java generator."""
    w = t.get("write")
    if w is not None:
        return _offers_in_patch(w, prop)
    probed = [d["write"] for d in spec.derived(t) if d.get("write")]
    return all(_offers_in_patch(d, prop) for d in probed)


def creatable(spec: Spec, t: dict[str, Any]) -> bool:
    """Whether a type gets a create: the probe has writes for it, or a subtype it's the root of does."""
    if t["kind"] != "entity":
        return False
    if t.get("write"):
        return True
    return any(d.get("write") for d in spec.derived(t))


def _copied(spec: Spec, t: dict[str, Any], create: bool) -> list[str]:
    """What ``builder(read)`` copies, as code: the structural properties a write of ``t`` sets, of the
    type and its base, each a name, or, for a structured value, ``(name, its write type)``, whose own
    ``builder(read)`` converts it. A value whose type has subtypes isn't copied into a create (which
    one to make the read can't say). For a patch, not a collection a PATCH appends to. As the Java
    generator's ``from``."""
    out: list[str] = []
    seen: set[str] = set()
    for level in spec.chain(t):
        for s in _writable(spec, level, t, create):
            name = s.json
            if s.navigation or not s.set or name in seen or (not create and not patches(t, name)):
                continue
            seen.add(name)
            value = _value_type(spec, s.prop)
            if value is None:
                out.append(repr(name))
            elif value.endswith("Create") and spec.derived(spec.type_by_name[s.prop["type"]]):
                continue
            else:
                out.append(f"({name!r}, {value})")
    return out


def _read_binding(spec: Spec, n: dict[str, Any]) -> tuple[str, str, str, str] | None:
    """How ``builder(read)`` binds a navigation the read holds (for a patch, a single-valued one only:
    see ``_bindings``): ``(name, helper, collection, key)``, by
    key for a target in an entity set, by ``UniqueName`` for an element or a member; None for any
    other (a subset, public or private, or a view, whose cube the read doesn't say). As the Java
    generator's ``readBinding``."""
    target = spec.type_by_name.get(n["type"])
    if target is None or target["kind"] != "entity":
        return None
    key: list[str] = []
    entity_set = None
    # The type first, then its bases, as the Java generator looks
    for level in reversed(spec.chain(target)):
        if level["kind"] == "entity" and level.get("key") and not key:
            key = level["key"]
        if entity_set is None:
            entity_set = next((s["name"] for s in spec.container["entitySets"] if s["entityType"] == level["qualifiedName"]), None)
    many = n["collection"]
    if entity_set is not None and len(key) == 1:
        return (n["name"], "by_keys" if many else "by_key", entity_set, key[0])
    if target["name"] in ("Element", "Member"):
        return (n["name"], "all_in_hierarchy" if many else "in_hierarchy", target["name"] + "s", "UniqueName")
    return None


def _bindings(spec: Spec, t: dict[str, Any], create: bool) -> tuple[tuple[str, str, str, str], ...]:
    out: list[tuple[str, str, str, str]] = []
    for level in spec.chain(t):
        for s in _writable(spec, level, t, create):
            # A patch from a read binds no collection: sent back, it would replace or append to what's
            # there, whichever TM1 does for it, on an unrelated change
            if s.navigation and s.bind and (create or not s.prop["collection"]) and all(b[0] != s.json for b in out):
                binding = _read_binding(spec, s.prop)
                if binding is not None:
                    out.append(binding)
    return tuple(out)


def _declared(spec: Spec, t: dict[str, Any]) -> tuple[str, ...] | None:
    if any(level["open"] for level in spec.chain(t)):
        return tuple(p["name"] for level in spec.chain(t) for p in level["properties"] + level["navigationProperties"])
    return None


def _read_doc(create: bool) -> tuple[str, ...]:
    return (
        "        A navigation the read holds expanded is bound, where its targets' paths follow from it: an entity",
        "        set's by key, an element's or member's by its ``UniqueName``; one it doesn't hold, or holds empty, or",
        "        a subset or a view (whose path the read can't tell), isn't, nor are nested ones or nulls" + ('."""' if create else ", nor a"),
        *(() if create else ('        collection, which a PATCH would replace or append to."""',)),
    )


def _copy_call(spec: Spec, t: dict[str, Any], create: bool) -> str:
    copied = _copied(spec, t, create)
    names_code = "(" + ", ".join(copied) + ("," if len(copied) == 1 else "") + ")"
    return f"cls.Builder(cls)._copy(read, {names_code}, {_declared(spec, t)!r}, {_bindings(spec, t, create)!r})"


def _builder_method(spec: Spec, t: dict[str, Any], name: str, create: bool) -> list[str]:
    """``builder(read=None)``: an empty builder, or, given an entity read from TM1, one holding what it
    holds that the write takes, as the Java model's ``builder(read)``. A hierarchy's root starts from a
    read of any of its types with that type's write: a create raises ValueError for a type with none,
    and a patch copies what the root's takes."""
    out = ["", "    @classmethod", f"    def builder(cls, read: Any = None) -> {name}.Builder:"]
    subtypes = spec.derived(t)
    if subtypes:
        if create:
            out += [
                '        """A builder with nothing set; or, given an entity read from TM1, a create of what it holds that a',
                "        POST applies, to recreate it, by its type: that type's ``builder(read)``. Raises ValueError if TM1",
                '        makes none of its type."""',
            ]
        else:
            out += [
                '        """A builder with nothing set; or, given a value read from TM1, a patch of what it holds that a',
                "        write takes, by its type: that type's ``builder(read)``, or, for a type with no patch of its",
                '        own, what this one takes."""',
            ]
        out += ["        if read is None:", "            return cls.Builder(cls)"]
        for sub in subtypes:
            write = sub["name"] + ("Create" if create else "Patch")
            if (has_create if create else has_patch)(spec, sub):
                out += [f"        if isinstance(read, _read.{sub['name']}):", f"            return {write}.builder(read)"]
        if create:
            out.append('        raise ValueError(f"A {type(read).__name__} has no create: TM1 makes none of its type")')
        else:
            out.append(f"        return {_copy_call(spec, t, create)}  # type: ignore[no-any-return]")
        return out
    what = "a POST applies, to recreate it" if create else "a PATCH applies, leaving out collections a PATCH appends to"
    out += [
        f'        """A builder with nothing set; or, given an entity read from TM1, one holding what it holds that {what}.',
        *_read_doc(create),
        f"        return {_copy_call(spec, t, create)}  # type: ignore[no-any-return]",
    ]
    return out


BIND = "@odata.bind"


def verified_only(spec: Spec) -> bool:
    """Whether the spec lists the write types and setters to generate (its ``offers``): then only
    those, which the probe verified or the overlay keeps, are generated, as by the Java generator
    (#338). A spec without them gets a patch for every type, offering what the writes don't refuse."""
    return bool(spec.offers)


def has_patch(spec: Spec, t: dict[str, Any]) -> bool:
    """Whether a type gets a patch: every type does, unless the offers say which."""
    return not verified_only(spec) or t["name"] + "Patch" in spec.offers


def has_create(spec: Spec, t: dict[str, Any]) -> bool:
    """Whether a type gets a create: one the offers list; without offers, as :func:`creatable`."""
    if verified_only(spec):
        return t["kind"] == "entity" and t["name"] + "Create" in spec.offers
    return creatable(spec, t)


@dataclass
class _Setter:
    """A write type's property, for its setter and bind methods: ``set`` whether it has its setter
    (for a navigation, the nested one), ``bind`` whether a navigation has its ``@odata.bind`` one."""

    prop: dict[str, Any]
    navigation: bool
    set: bool
    bind: bool

    @property
    def json(self) -> str:
        return self.prop["name"]


def _writable(spec: Spec, level: dict[str, Any], t: dict[str, Any], create: bool) -> list[_Setter]:
    """The setters a write of ``t`` has for ``level``'s properties (``t``'s own, or its root's): with
    offers, what they list for it; else, for a create, what a POST takes, and for a patch, what a PATCH
    doesn't reject or ignore, navigations included."""
    out: list[_Setter] = []
    if verified_only(spec):
        offered = spec.offers.get(t["name"] + ("Create" if create else "Patch"), {})
        for p in level["properties"]:
            if p["type"] != "Edm.Stream" and p["name"] in offered:
                out.append(_Setter(p, False, True, False))
        for n in level["navigationProperties"]:
            nested = n["name"] in offered
            bind = spec.is_entity(n["type"]) and n["name"] + BIND in offered
            if nested or bind:
                out.append(_Setter(n, True, nested, bind))
        return out
    for p in level["properties"]:
        if p["type"] != "Edm.Stream" and (creates(t, p["name"], spec) if create else offers_in_patch(spec, t, p["name"])):
            out.append(_Setter(p, False, True, False))
    # A navigation a PATCH ignores or rejects (a cube's Dimensions) isn't offered either
    for n in level["navigationProperties"]:
        if create or offers_in_patch(spec, t, n["name"]):
            out.append(_Setter(n, True, True, spec.is_entity(n["type"])))
    return out


def _method_names(spec: Spec, level: dict[str, Any]) -> dict[str, tuple[str, str]]:
    """The names of the setter and bind methods for ``level``'s properties, by their names in TM1's
    JSON, as its own builder names them."""
    taken = {"build", "property"}
    out: dict[str, tuple[str, str]] = {}
    for p in level["properties"]:
        if p["type"] == "Edm.Stream":
            continue
        field = names.field_name(p["name"])
        out[p["name"]] = (names.unique(field.rstrip("_") if field.rstrip("_") not in taken else field, taken), "")
    for n in level["navigationProperties"]:
        m = names.unique(names.field_name(n["name"]).rstrip("_"), taken)
        bind = names.unique("bind_" + names.field_name(n["name"]).rstrip("_"), taken) if spec.is_entity(n["type"]) else ""
        out[n["name"]] = (m, bind)
    return out


def _value_type(spec: Spec, ref: dict[str, Any]) -> str | None:
    """The write type a setter of a structured value takes, as the Java generator: a complex type's
    ``Patch``; an entity's ``Create`` where it has one (a nested entity is new), else its ``Patch``.
    None for a primitive, for one TM1 sends otherwise than declared, and without offers."""
    if not verified_only(spec) or ref.get("departure"):
        return None
    t = spec.type_by_name.get(ref["type"])
    if t is None:
        return None
    write = t["name"] + ("Create" if t["kind"] == "entity" and t["name"] + "Create" in spec.offers else "Patch")
    return write if write in spec.offers else None


def _setter(spec: Spec, s: _Setter, method_names: dict[str, tuple[str, str]]) -> list[str]:
    m, bind = method_names[s.json]
    out: list[str] = []
    if s.set:
        value = _value_type(spec, s.prop)
        tp = "Any" if value is None else f"Sequence[{value}] | None" if s.prop["collection"] else f"{value} | None"
        out += [
            "",
            f"        def {m}(self, value: {tp}) -> Self:",
            f"            return self._set({s.json!r}, value)  # type: ignore[return-value]",
        ]
    if s.bind:
        out.append("")
        if s.prop["collection"]:
            out.append(f"        def {bind}(self, *targets: ODataPath | str) -> Self:")
            out.append(f"            return self._bind_all({s.json!r}, targets)  # type: ignore[return-value]")
        else:
            out.append(f"        def {bind}(self, target: ODataPath | str) -> Self:")
            out.append(f"            return self._bind({s.json!r}, target)  # type: ignore[return-value]")
    return out


def _setters(spec: Spec, t: dict[str, Any], create: bool) -> list[str]:
    """A builder's setters: for its type's own properties, and for its root's that the root's builder
    lacks but this one offers (the rest it inherits)."""
    out: list[str] = []
    if t.get("baseType"):
        root = spec.type_by_name[t["baseType"]]
        inherited = {s.json: s for s in _writable(spec, root, root, create)}
        root_names = _method_names(spec, root)
        for s in _writable(spec, root, t, create):
            have = inherited.get(s.json)
            missing = _Setter(s.prop, s.navigation, s.set and not (have and have.set), s.bind and not (have and have.bind))
            if missing.set or missing.bind:
                out += _setter(spec, missing, root_names)
    own = _method_names(spec, t)
    for s in _writable(spec, t, t, create):
        out += _setter(spec, s, own)
    return out


def emit_patches(spec: Spec) -> str:
    """Patch builders, as the Java model's ``CubeCreate.builder().name("Sales").build()``: only
    what's set is sent, explicit ``None`` included, with ``bind_…`` for ``@odata.bind``. With offers
    in the spec, only the write types and setters they list."""
    out = [
        '"""Patch builders for writes, generated from spec/tm1-api.json by tm1_pygen: don\'t edit.',
        "",
        "As the Java model's ``CubePatch``: ``CubeCreate.builder().name('Sales').bind_dimensions(",
        "ODataPath.of('Dimensions').key('Region')).build()``. A model instance (``Cube(name=…)``)",
        "works anywhere a patch does.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from typing import TYPE_CHECKING, Any",
        "",
        "from ..base import Tm1Patch",
        "from ..odata import ODataPath",
        "from . import model as _read",
        "",
        "if TYPE_CHECKING:",
        "    from collections.abc import Sequence",
        "",
        "    # A setter returns the builder it's called on, so a subtype's builder keeps its own setters",
        "    from typing import Self",
        "",
    ]
    names_all = []
    ordered = sorted(spec.types, key=lambda t: (t.get("baseType") is not None, t["name"]))
    for t in ordered:
        if not has_patch(spec, t):
            continue
        name = t["name"] + "Patch"
        names_all.append(name)
        base = spec.type_by_name[t["baseType"]]["name"] + "Patch" if t.get("baseType") else "Tm1Patch"
        out += ["", "", f"class {name}({base}):"]
        out.append(f'    """A write of ``{t["name"]}``: only the properties set are sent."""')
        out.append("")
        out.append(f"    odata_type = {('#' + t['qualifiedName'])!r}" if t.get("baseType") else "    odata_type = None")
        out += _builder_method(spec, t, name, False)
        out.append("")
        out.append(f"    class Builder({base}.Builder):")
        out.append(f'        """Builds a ``{name}``; each setter replaces what an earlier call set."""')
        setters = _setters(spec, t, False)
        out += setters
        # As the other clients: only an open type's builder takes properties $metadata doesn't declare
        if t["open"]:
            out += _property()
        if not setters and not t["open"]:
            out.append("        pass")
    for t in ordered:
        if not has_create(spec, t):
            continue
        name = t["name"] + "Create"
        names_all.append(name)
        base = spec.type_by_name[t["baseType"]]["name"] + "Create" if t.get("baseType") else "Tm1Patch"
        if verified_only(spec):
            has = "as the probe verified"
        else:
            has = "as the probe found" if t.get("write") else "all, as the probe has no writes for this type"
        out += ["", "", f"class {name}({base}):"]
        out.append(f'    """A create of ``{t["name"]}``, for a POST: only the properties set are sent, and only those TM1')
        out.append(f"    applies in a create are offered ({has}). ``build()`` checks what a create needs. As the Java")
        out.append(f'    model\'s ``{name}``."""')
        out.append("")
        out.append(f"    odata_type = {('#' + t['qualifiedName'])!r}" if t.get("baseType") else "    odata_type = None")
        out += _builder_method(spec, t, name, True)
        out.append("")
        out.append(f"    class Builder({base}.Builder):")
        out.append(f'        """Builds a ``{name}``; each setter replaces what an earlier call set."""')
        setters = _setters(spec, t, True)
        out += setters
        if t["open"]:
            out += _property()
        required = (t.get("write") or {}).get("create", {}).get("required", [])
        if required:
            out.append("")
            out.append("        def build(self) -> Any:")
            out.append("            for needed in " + repr(tuple(r.replace("@odata.bind", "") for r in required)) + ":")
            out.append('                if needed not in self._values and needed + "@odata.bind" not in self._values:')
            out.append(f'                    raise ValueError(f"A {t["name"]} create needs {{needed}}: TM1 refuses one without it")')
            out.append("            return super().build()")
        elif not setters and not t["open"]:
            out.append("        pass")
    out.append("")
    out.append("__all__ = [" + ", ".join(repr(n) for n in names_all) + "]")
    out.append("")
    return "\n".join(out)


def _property() -> list[str]:
    return [
        "",
        "        def property(self, name: str, value: Any) -> Self:",
        '            """A property ``$metadata`` doesn\'t declare, which this open type can have, such as an attribute\'s value."""',
        "            return self._set(name, value)  # type: ignore[return-value]",
    ]
