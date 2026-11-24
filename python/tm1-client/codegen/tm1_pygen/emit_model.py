# SPDX-License-Identifier: Apache-2.0

"""Emits ``skipcheck_tm1/_generated/model.py``: an enum per CSDL enum type and a pydantic model
per entity and complex type, the Python counterpart of the Java client's generated model."""

from __future__ import annotations

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


def _copied_names(spec: Spec, t: dict[str, Any], create: bool) -> list[str]:
    """The properties ``builder(read)`` copies: structural ones, of the type and its base, a write takes."""
    out: list[str] = []
    for level in spec.chain(t):
        for p in level["properties"]:
            if (
                p["type"] != "Edm.Stream"
                and p["name"] not in out
                and (creates(t, p["name"], spec) if create else patches(t, p["name"]) and offers_in_patch(spec, t, p["name"]))
            ):
                out.append(p["name"])
    return out


def _builder_method(spec: Spec, t: dict[str, Any], name: str, create: bool | None) -> list[str]:
    """``builder(read=None)``: an empty builder, or, given an entity read from TM1, one holding what it
    holds that the write takes, as the Java model's ``builder(read)``. A hierarchy's root
    (``create`` None) has only the empty one, as in Java."""
    out = ["", "    @classmethod"]
    if create is None:
        out += [f"    def builder(cls) -> {name}.Builder:", "        return cls.Builder(cls)"]
        return out
    copied = tuple(_copied_names(spec, t, create))
    what = "a POST applies, to recreate it" if create else "a PATCH applies, leaving out collections a PATCH appends to"
    declared = None
    if any(level["open"] for level in spec.chain(t)):
        declared = tuple(p["name"] for level in spec.chain(t) for p in level["properties"] + level["navigationProperties"])
    out += [
        f"    def builder(cls, read: Any = None) -> {name}.Builder:",
        f'        """A builder with nothing set; or, given an entity read from TM1, one holding what it holds that {what}.',
        '        Navigation properties aren\'t copied, nor nulls."""',
        f"        return cls.Builder(cls)._copy(read, {copied!r}, {declared!r})  # type: ignore[no-any-return]",
    ]
    return out


def emit_patches(spec: Spec) -> str:
    """Patch builders, as the Java model's ``CubeCreate.builder().name("Sales").build()``: only
    what's set is sent, explicit ``None`` included, with ``bind_…`` for ``@odata.bind``."""
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
        "",
        "if TYPE_CHECKING:",
        "    # A setter returns the builder it's called on, so a subtype's builder keeps its own setters",
        "    from typing import Self",
        "",
    ]
    names_all = []
    ordered = sorted(spec.types, key=lambda t: (t.get("baseType") is not None, t["name"]))
    for t in ordered:
        name = t["name"] + "Patch"
        names_all.append(name)
        base = spec.type_by_name[t["baseType"]]["name"] + "Patch" if t.get("baseType") else "Tm1Patch"
        out += ["", "", f"class {name}({base}):"]
        out.append(f'    """A write of ``{t["name"]}``: only the properties set are sent."""')
        out.append("")
        out.append(f"    odata_type = {('#' + t['qualifiedName'])!r}" if t.get("baseType") else "    odata_type = None")
        out += _builder_method(spec, t, name, None if spec.derived(t) else False)
        out.append("")
        builder_base = f"{base}.Builder"
        out.append(f"    class Builder({builder_base}):")
        out.append(f'        """Builds a ``{name}``; each setter replaces what an earlier call set."""')
        taken = {"build", "property"}
        methods = 0
        for p in t["properties"]:
            if p["type"] == "Edm.Stream" or not offers_in_patch(spec, t, p["name"]):
                continue
            m = names.unique(
                names.field_name(p["name"]).rstrip("_") if names.field_name(p["name"]).rstrip("_") not in taken else names.field_name(p["name"]), taken
            )
            out.append("")
            out.append(f"        def {m}(self, value: Any) -> Self:")
            out.append(f"            return self._set({p['name']!r}, value)  # type: ignore[return-value]")
            methods += 1
        for n in t["navigationProperties"]:
            # A navigation a PATCH ignores or rejects (a cube's Dimensions) isn't offered either
            if not offers_in_patch(spec, t, n["name"]):
                continue
            m = names.unique(names.field_name(n["name"]).rstrip("_"), taken)
            out.append("")
            out.append(f"        def {m}(self, value: Any) -> Self:")
            out.append(f"            return self._set({n['name']!r}, value)  # type: ignore[return-value]")
            if spec.is_entity(n["type"]):
                bind = names.unique("bind_" + names.field_name(n["name"]).rstrip("_"), taken)
                out.append("")
                if n["collection"]:
                    out.append(f"        def {bind}(self, *targets: ODataPath | str) -> Self:")
                    out.append(f"            return self._bind_all({n['name']!r}, targets)  # type: ignore[return-value]")
                else:
                    out.append(f"        def {bind}(self, target: ODataPath | str) -> Self:")
                    out.append(f"            return self._bind({n['name']!r}, target)  # type: ignore[return-value]")
            methods += 1
        # As the other clients: only an open type's builder takes properties $metadata doesn't declare
        if t["open"]:
            out.append("")
            out.append("        def property(self, name: str, value: Any) -> Self:")
            out.append('            """A property ``$metadata`` doesn\'t declare, which this open type can have, such as an attribute\'s value."""')
            out.append("            return self._set(name, value)  # type: ignore[return-value]")
            methods += 1
        if not methods:
            out.append("        pass")
    for t in ordered:
        if not creatable(spec, t):
            continue
        name = t["name"] + "Create"
        names_all.append(name)
        base = spec.type_by_name[t["baseType"]]["name"] + "Create" if t.get("baseType") else "Tm1Patch"
        has = "as the probe found" if t.get("write") else "all, as the probe has no writes for this type"
        out += ["", "", f"class {name}({base}):"]
        out.append(f'    """A create of ``{t["name"]}``, for a POST: only the properties set are sent, and only those TM1')
        out.append(f"    applies in a create are offered ({has}). ``build()`` checks what a create needs. As the Java")
        out.append(f'    model\'s ``{name}``."""')
        out.append("")
        out.append(f"    odata_type = {('#' + t['qualifiedName'])!r}" if t.get("baseType") else "    odata_type = None")
        out += _builder_method(spec, t, name, None if spec.derived(t) else True)
        out.append("")
        out.append(f"    class Builder({base}.Builder):")
        out.append(f'        """Builds a ``{name}``; each setter replaces what an earlier call set."""')
        taken = {"build", "property"}
        for p in t["properties"]:
            if p["type"] == "Edm.Stream" or not creates(t, p["name"], spec):
                continue
            m = names.unique(
                names.field_name(p["name"]).rstrip("_") if names.field_name(p["name"]).rstrip("_") not in taken else names.field_name(p["name"]), taken
            )
            out.append("")
            out.append(f"        def {m}(self, value: Any) -> Self:")
            out.append(f"            return self._set({p['name']!r}, value)  # type: ignore[return-value]")
        for n in t["navigationProperties"]:
            m = names.unique(names.field_name(n["name"]).rstrip("_"), taken)
            out.append("")
            out.append(f"        def {m}(self, value: Any) -> Self:")
            out.append(f"            return self._set({n['name']!r}, value)  # type: ignore[return-value]")
            if spec.is_entity(n["type"]):
                bind = names.unique("bind_" + names.field_name(n["name"]).rstrip("_"), taken)
                out.append("")
                if n["collection"]:
                    out.append(f"        def {bind}(self, *targets: ODataPath | str) -> Self:")
                    out.append(f"            return self._bind_all({n['name']!r}, targets)  # type: ignore[return-value]")
                else:
                    out.append(f"        def {bind}(self, target: ODataPath | str) -> Self:")
                    out.append(f"            return self._bind({n['name']!r}, target)  # type: ignore[return-value]")
        if t["open"]:
            out.append("")
            out.append("        def property(self, name: str, value: Any) -> Self:")
            out.append('            """A property ``$metadata`` doesn\'t declare, which this open type can have, such as an attribute\'s value."""')
            out.append("            return self._set(name, value)  # type: ignore[return-value]")
        required = (t.get("write") or {}).get("create", {}).get("required", [])
        if required:
            out.append("")
            out.append("        def build(self) -> Any:")
            out.append("            for needed in " + repr(tuple(r.replace("@odata.bind", "") for r in required)) + ":")
            out.append('                if needed not in self._values and needed + "@odata.bind" not in self._values:')
            out.append(f'                    raise ValueError(f"A {t["name"]} create needs {{needed}}: TM1 refuses one without it")')
            out.append("            return super().build()")
    out.append("")
    out.append("__all__ = [" + ", ".join(repr(n) for n in names_all) + "]")
    out.append("")
    return "\n".join(out)
