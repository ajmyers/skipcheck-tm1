# SPDX-License-Identifier: Apache-2.0

"""Writes a Python package's public API as JSON, in the shape the Java doclet writes
(java/tools/docs-doclet), for the docs site's reference pages (api-site/scripts/reference.mjs).

It reads the sources with the ast module and imports nothing, so it needs no dependencies and no
install. Classes are documented as the sync flavor names them: AsyncTm1Client in aio is the
Tm1Client most people use, with the same methods, not awaited. The asyncio flavor is the same API
with await, which the pages say once.

    python3 python_api.py <src/package> <out.json>
"""

from __future__ import annotations

import ast
import json
import re
import sys
from pathlib import Path

_ASYNC_TM1 = re.compile(r"\bAsync(Tm1\w*)")
_SYNC_NAMES = {"AsyncIterator": "Iterator", "AsyncIterable": "Iterable", "AsyncGenerator": "Generator"}


def sync(text: str) -> str:
    """A name or annotation as the sync flavor writes it (the rules of codegen's unasync)."""
    text = _ASYNC_TM1.sub(r"\1", text)
    for name, replacement in _SYNC_NAMES.items():
        text = re.sub(rf"\b{name}\b", replacement, text)
    return text


def endpoints(node: ast.AST) -> list[str]:
    found = []
    for decorator in getattr(node, "decorator_list", []):
        if isinstance(decorator, ast.Call) and getattr(decorator.func, "id", None) == "endpoint":
            found += [arg.value for arg in decorator.args if isinstance(arg, ast.Constant) and isinstance(arg.value, str)]
    return found


def decorated(node: ast.AST, name: str) -> bool:
    return any(getattr(d, "id", None) == name or getattr(d, "attr", None) == name for d in getattr(node, "decorator_list", []))


def annotation(node: ast.expr | None) -> str:
    return sync(ast.unparse(node)) if node is not None else ""


def parameters(function: ast.FunctionDef | ast.AsyncFunctionDef) -> list[dict[str, str]]:
    args = function.args
    out = []
    positional = args.posonlyargs + args.args
    defaults = [None] * (len(positional) - len(args.defaults)) + list(args.defaults)
    for arg, default in zip(positional, defaults):
        if arg.arg in ("self", "cls"):
            continue
        out.append(parameter(arg, default))
    if args.vararg:
        out.append({"name": "*" + args.vararg.arg, "type": annotation(args.vararg.annotation)})
    elif args.kwonlyargs:
        out.append({"name": "*", "type": ""})
    for arg, default in zip(args.kwonlyargs, args.kw_defaults):
        out.append(parameter(arg, default))
    if args.kwarg:
        out.append({"name": "**" + args.kwarg.arg, "type": annotation(args.kwarg.annotation)})
    return out


def parameter(arg: ast.arg, default: ast.expr | None) -> dict[str, str]:
    result = {"name": arg.arg, "type": annotation(arg.annotation)}
    if default is not None:
        result["default"] = ast.unparse(default)
    return result


def signature(name: str, params: list[dict[str, str]], returns: str) -> str:
    def one(p: dict[str, str]) -> str:
        text = p["name"]
        if p.get("type") and p["name"] != "*":
            text += ": " + p["type"]
        if "default" in p:
            text += " = " + p["default"]
        return text

    return name + "(" + ", ".join(one(p) for p in params) + ")" + (" -> " + returns if returns else "")


def member(node: ast.AST) -> dict | None:
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        name = node.name
        if name.startswith("_") and name != "__init__":
            return None
        params = parameters(node)
        returns = annotation(node.returns)
        is_property = decorated(node, "property")
        kind = "property" if is_property else ("constructor" if name == "__init__" else "method")
        static = decorated(node, "staticmethod") or decorated(node, "classmethod")
        shown = name + (": " + returns if returns else "") if is_property else signature(name, params, returns)
        return {
            "kind": kind,
            "name": name,
            "static": static,
            "signature": shown,
            "returns": returns,
            "params": params,
            "endpoints": endpoints(node),
            "doc": sync(ast.get_docstring(node) or ""),
            "deprecated": False,
        }
    if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and not node.target.id.startswith("_"):
        type_ = annotation(node.annotation)
        return {"kind": "field", "name": node.target.id, "static": False, "signature": node.target.id + ": " + type_, "returns": type_, "params": [], "endpoints": [], "doc": "", "deprecated": False}
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
        name = node.targets[0].id
        if not name.startswith("_") and name[:1].isupper():
            return {"kind": "constant", "name": name, "static": True, "signature": name, "returns": "", "params": [], "endpoints": [], "doc": "", "deprecated": False}
    return None


def classes(path: Path, package: str) -> list[dict]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    found = []
    for node in tree.body:
        if not isinstance(node, ast.ClassDef) or node.name.startswith("_"):
            continue
        bases = [annotation(b) for b in node.bases]
        kind = "enum" if any("Enum" in b for b in bases) else ("protocol" if "Protocol" in bases else "class")
        members = [m for m in (member(child) for child in node.body) if m is not None]
        found.append(
            {
                "name": sync(node.name),
                "package": package,
                "kind": kind,
                "signature": "class " + sync(node.name) + ("(" + ", ".join(bases) + ")" if bases else ""),
                "doc": sync(ast.get_docstring(node) or ""),
                "deprecated": False,
                "members": members,
            }
        )
    return found


def exported(init: Path) -> set[str]:
    """The names a package's __init__ lists in __all__: those are imported from the package itself."""
    if not init.is_file():
        return set()
    for node in ast.parse(init.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "__all__" for t in node.targets) and isinstance(node.value, ast.List):
            return {e.value for e in node.value.elts if isinstance(e, ast.Constant)}
    return set()


def main(source: str, out: str) -> None:
    root = Path(source)
    top = exported(root / "__init__.py")
    types: dict[str, dict] = {}
    for path in sorted(root.rglob("*.py"), key=lambda p: ("aio" not in p.parts, str(p))):
        parts = list(path.relative_to(root.parent).with_suffix("").parts)
        if any(part.startswith("_") and part != "__init__" for part in parts):
            continue
        if parts[-1] == "__init__":
            parts = parts[:-1]
        # The sync flavor is generated from aio/ and re-exported at the top: document it there
        module = ".".join(p for p in parts if p != "aio")
        if ".services." in module + ".":
            module = module[: module.index(".services") + len(".services")]
        for cls in classes(path, module):
            if cls["name"] in top:
                cls["package"] = root.name
            # A class in aio/ and its sync twin are one class; aio/ is the source of truth
            types.setdefault(cls["name"], cls)
    with Path(out).open("w", encoding="utf-8") as file:
        json.dump(sorted(types.values(), key=lambda t: t["name"]), file)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
