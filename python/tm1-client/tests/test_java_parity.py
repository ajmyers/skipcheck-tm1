"""The Python client offers what the Java client does, method for method, read from the Java
sources themselves (java/tm1-client/src/main/java/.../service):

* each Java service class is a Python class of the same name, in the module named after its
  package (``service/cube/CubeService.java`` → ``skipcheck_tm1.aio.services.cube.CubeService``);
* each public method is a Python method named in snake case (``modelCubes`` → ``model_cubes``),
  implementing the same ``@Tm1Endpoint`` IDs;
* its parameters, but for the ``ODataQuery`` (which becomes ``query`` and ``**options``), come
  in the same order under the same names; overloads become optional parameters;
* the other public classes of those packages (``ContentPath``, ``Tuples``…) exist too, with
  their public methods;
* and :class:`Tm1Client` has a property per Java accessor.

The sync flavor is generated from the asyncio one, and test_generator.py checks they match.
"""

from __future__ import annotations

import inspect
import re
from dataclasses import dataclass, field
from importlib import import_module
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "java" / "tm1-client" / "src" / "main" / "java" / "io" / "skipcheck" / "tm1" / "client"
SERVICES = JAVA / "service"

_METHOD = re.compile(r"^\s+public\s+(?:static\s+)?(?:<[^>]+>\s+)?(?:[\w.<>\[\]?, ]+?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\{", re.M)


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def _split(params: str) -> list[str]:
    out, depth, current = [], 0, ""
    for c in params:
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
        if c == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += c
    if current.strip():
        out.append(current)
    return [" ".join(p.replace("@Nullable", "").split()) for p in out]


@dataclass
class JavaMethod:
    name: str
    overloads: list[list[tuple[str, str]]] = field(default_factory=list)
    endpoints: set[str] = field(default_factory=set)


def java_methods(path: Path) -> dict[str, JavaMethod]:
    """Public methods of a Java class, with each overload's parameters and the endpoints."""
    source = path.read_text(encoding="utf-8")
    cls = path.stem
    # Only the top-level class's own members: drop nested types' bodies
    out: dict[str, JavaMethod] = {}
    for m in re.finditer(
        r"((?:\s*@Tm1Endpoint\(\"[^\"]+\"\)\s*)*)(\s+public\s+(?:static\s+)?(?:<[^>]+>\s+)?[\w.<>\[\]?, ]+?\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\{)",
        source,
    ):
        name = m.group(3)
        if name == cls:
            continue
        line = m.group(2).lstrip("\n")
        if not line.startswith("\tpublic"):  # a nested type's member
            continue
        params = []
        for p in _split(m.group(4)):
            parts = p.rsplit(" ", 1)
            params.append((parts[0], parts[1].lstrip(".")))
        method = out.setdefault(name, JavaMethod(name))
        method.overloads.append(params)
        method.endpoints |= set(re.findall(r'@Tm1Endpoint\("([^"]+)"\)', m.group(1)))
    return out


def java_classes() -> list[tuple[str, Path]]:
    out = []
    for path in sorted(SERVICES.glob("*/*.java")):
        if path.stem in ("package-info",):
            continue
        source = path.read_text(encoding="utf-8")
        if re.search(rf"public\s+(final\s+)?(class|enum|record|interface)\s+{path.stem}\b", source):
            out.append((path.parent.name, path))
    return out


CLASSES = java_classes()


def python_class(package: str, name: str) -> type:
    module = import_module(f"skipcheck_tm1.aio.services.{package}")
    assert hasattr(module, name), f"skipcheck_tm1.aio.services.{package} has no {name}"
    return getattr(module, name)


@pytest.mark.parametrize("package,path", CLASSES, ids=[p.stem for _, p in CLASSES])
def test_every_java_class_has_its_python_counterpart(package: str, path: Path) -> None:
    cls = python_class(package, path.stem)
    source = path.read_text(encoding="utf-8")
    if re.search(rf"public\s+enum\s+{path.stem}\b", source):
        import enum

        assert issubclass(cls, enum.Enum), f"{path.stem} is an enum in Java"
        java_constants = re.findall(r"^\t([A-Z][A-Z_0-9]*)\b[,;(]", source, re.M)
        assert {c for c in java_constants} <= set(cls.__members__), f"{path.stem}'s constants"
        return
    missing, wrong = [], []
    for name, method in java_methods(path).items():
        if name in ("toString", "equals", "hashCode"):
            continue
        py_name = snake(name)
        member = inspect.getattr_static(cls, py_name, None)
        if member is None:
            missing.append(f"{name} → {py_name}")
            continue
        func = member.__func__ if isinstance(member, (staticmethod, classmethod)) else member
        if isinstance(func, property):
            func = func.fget
        if not callable(func):
            continue
        endpoints = set(getattr(func, "__tm1_endpoints__", ()))
        if endpoints != method.endpoints:
            wrong.append(f"{py_name}: endpoints {sorted(endpoints)}, Java {sorted(method.endpoints)}")
        parameters = [p for p in inspect.signature(func).parameters.values() if p.name not in ("self", "cls")]
        names = [p.name for p in parameters if p.kind not in (p.VAR_KEYWORD, p.VAR_POSITIONAL)]
        for overload in method.overloads:
            expected = [snake(n) for t, n in overload if t != "ODataQuery"]
            # A query is "query", or by Java's name (a stream's cell_query)
            queries = {"query", *(snake(n) for t, n in overload if t == "ODataQuery")}
            if any(t == "ODataQuery" for t, _ in overload) and not queries & set(names):
                wrong.append(f"{py_name}: no query parameter")
            got = [n for n in names if n not in queries]
            if got[: len(expected)] != expected and not _subsequence(expected, got):
                wrong.append(f"{py_name}: parameters {got}, Java {expected}")
    assert not missing and not wrong, "\n".join(["missing:", *missing, "different:", *wrong])


def _subsequence(expected: list[str], got: list[str]) -> bool:
    it = iter(got)
    return all(any(e == g for g in it) for e in expected)


def test_the_client_has_every_service() -> None:
    from skipcheck_tm1.aio import AsyncTm1Client

    methods = java_methods(JAVA / "Tm1Client.java")
    services = [
        n for n, m in methods.items() if m.overloads == [[]] and n not in ("builder", "session", "batch", "connect", "close", "withoutAsync", "toString")
    ]
    for name in services:
        assert isinstance(inspect.getattr_static(AsyncTm1Client, snake(name), None), property), name
    for name in ("batch", "with_async", "without_async", "with_sandbox", "with_change_set", "connect", "close", "builder", "of"):
        assert hasattr(AsyncTm1Client, name), name
