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


def emit_patches(spec: Spec) -> str:
    """Patch builders, as the Java model's ``CubePatch.builder().name("Sales").build()``: only
    what's set is sent, explicit ``None`` included, with ``bind_…`` for ``@odata.bind``."""
    out = [
        '"""Patch builders for writes, generated from spec/tm1-api.json by tm1_pygen: don\'t edit.',
        "",
        "As the Java model's ``CubePatch``: ``CubePatch.builder().name('Sales').bind_dimensions(",
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
        out.append("")
        out.append("    @classmethod")
        out.append(f"    def builder(cls) -> {name}.Builder:")
        out.append("        return cls.Builder(cls)")
        out.append("")
        builder_base = f"{base}.Builder"
        out.append(f"    class Builder({builder_base}):")
        out.append(f'        """Builds a ``{name}``; each setter replaces what an earlier call set."""')
        taken = {"build", "property"}
        methods = 0
        for p in t["properties"]:
            if p["type"] == "Edm.Stream":
                continue
            m = names.unique(
                names.field_name(p["name"]).rstrip("_") if names.field_name(p["name"]).rstrip("_") not in taken else names.field_name(p["name"]), taken
            )
            out.append("")
            out.append(f"        def {m}(self, value: Any) -> Self:")
            out.append(f"            return self._set({p['name']!r}, value)  # type: ignore[return-value]")
            methods += 1
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
            methods += 1
        if not methods:
            out.append("        pass")
    out.append("")
    out.append("__all__ = [" + ", ".join(repr(n) for n in names_all) + "]")
    out.append("")
    return "\n".join(out)
