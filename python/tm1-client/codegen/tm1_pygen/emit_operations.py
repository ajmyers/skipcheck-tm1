"""Emits ``skipcheck_tm1/_generated/operations.py``: ``Tm1Operations``, a :class:`Tm1Operation`
per action and function, named as in the Java client (``CHORE_ACTIVATE``, ``EXECUTE_MDX``)."""

from __future__ import annotations

from typing import Any

from . import names
from .spec import Spec


def _constant(name: str) -> str:
    """Java's ``constantName``: ``NativeView`` → ``NATIVE_VIEW``, ``MDX`` → ``MDX``."""
    import re

    s = re.sub(r"([a-z])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    s = re.sub(r"[^A-Za-z0-9_]", "_", s).upper()
    return "_" + s if s[0].isdigit() else s


def _type(spec: Spec, ref: dict[str, Any]) -> str:
    name = ref["type"] if ref["type"].startswith("Edm.") else spec.alias_name(ref["type"])
    return f"Collection({name})" if ref["collection"] else name


def emit(spec: Spec) -> str:
    out = [
        f'"""Every action and function of the TM1 REST API (schema {spec.schema_version}), generated from',
        "spec/tm1-api.json by tm1_pygen: don't edit. As the Java client's ``Tm1Operations``.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from ..operation import Tm1Operation",
        "",
        "_P = Tm1Operation.Parameter",
        "_A = Tm1Operation.Kind.ACTION",
        "_F = Tm1Operation.Kind.FUNCTION",
        "",
        "",
        "class Tm1Operations:",
        '    """Services refer to these, so a request checks the server has the operation."""',
        "",
    ]
    taken = {"ALL"}
    constants = []

    def line(constant: str, op: dict[str, Any], name: str, binding: str | None, import_name: str | None) -> None:
        params = ", ".join(f"_P({p['name']!r}, {_type(spec, p)!r}, {p['nullable']})" for p in op["parameters"])
        returns = None if not op["returns"] else _type(spec, op["returns"])
        deprecated = (op.get("deprecated") or {}).get("version")
        kind = "_A" if op["kind"] == "action" else "_F"
        out.append(
            f"    {constant} = Tm1Operation({kind}, {name!r}, {binding!r}, {import_name!r}, ({params}{',' if len(op['parameters']) == 1 else ''}), "
            f"{returns!r}, {op.get('since')!r}, {deprecated!r})"
        )
        constants.append(constant)

    bound = sorted((o for o in spec.operations if o["bound"]), key=lambda o: (o["binding"]["type"], o["name"]))
    for op in bound:
        binding = spec.alias_name(op["binding"]["type"])
        constant = names.unique(_constant(binding.rsplit(".", 1)[-1]) + "_" + _constant(op["name"]), taken)
        line(constant, op, op["pathName"], binding, None)
    imports = [(i["name"], i["operation"]) for i in spec.container["actionImports"]]
    imports += [(i["name"], i["operation"]) for i in spec.container["functionImports"]]
    for import_name, qualified in sorted(imports):
        op = spec.unbound_operation[qualified]
        line(names.unique(_constant(import_name), taken), op, spec.alias_name(qualified), None, import_name)
    out.append("")
    out.append("    ALL: tuple[Tm1Operation, ...] = ()")
    out.append('    """Every operation above, bound ones first."""')
    out.append("")
    out.append("")
    out.append("Tm1Operations.ALL = (" + ", ".join(f"Tm1Operations.{c}" for c in constants) + ")")
    out.append("")
    return "\n".join(out)
