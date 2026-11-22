"""This package offers what the Java module does, read from the Java sources themselves
(tm1-testcontainers/src/main/java/net/ajmyers/tm1/testcontainers), as tm1-python's own
test_java_parity.py does for the client:

* each public Java type, nested ones too (``Tm1Source.PrivateImage``,
  ``PlanningAnalyticsEnvironment.Builder``), is a Python class of the same name;
* each public member is there, named the Python way: ``getRestUrl()`` → ``rest_url`` (getters are
  properties), ``withSeed`` → ``with_seed``, ``isRunning()`` → ``is_running``, constants and enum
  constants as they are, record components as attributes;
* each overload's parameters can be passed (overloads become optional parameters);
* the ``@Requires…`` messages are Java's, word for word, and the JUnit extensions are the pytest
  plugin's markers and fixtures.

What Java has and Python doesn't is listed in :data:`JAVA_ONLY`, with the reason.
"""

from __future__ import annotations

import inspect
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest

import tm1_testcontainers
from tm1_testcontainers import plugin

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "tm1-testcontainers" / "src" / "main" / "java" / "net" / "ajmyers" / "tm1" / "testcontainers"

JAVA_ONLY: dict[tuple[str, str], str] = {
    ("CognosContainer", "camAuthorization/3"): "Java's static overload; Python can't overload an instance method with a static one, so it's cam_authorization_for",
}
"""(type, member or member/parameter count) → why Python doesn't have it."""

# Strings, characters and comments, in one pass: "http://" isn't a comment, nor "{" a brace
_TOKEN = re.compile(r'"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])+\'|//[^\n]*|/\*.*?\*/', re.S)
_TYPE = re.compile(
    r"^(?P<mods>(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*)(?P<kind>class|interface|record|enum|@interface)\s+(?P<name>\w+)(?:\s*\((?P<components>[^)]*)\))?"
)
_METHOD = re.compile(
    r"^(?P<mods>(?:(?:public|protected|private|static|final|synchronized|abstract|default)\s+)*)(?:<[^>]+>\s+)?(?P<type>[\w.<>\[\]?, @]+?)\s+(?P<name>\w+)\s*\((?P<params>[^)]*)\)"
)
_FIELD = re.compile(r"^(?P<mods>(?:(?:public|protected|private|static|final)\s+)*)(?P<type>[\w.<>\[\]?, ]+?)\s+(?P<name>[A-Z][A-Z0-9_]*)\s*=")
_ENUM_CONSTANT = re.compile(r"^(?P<name>[A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)")


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def python_name(java: str, params: int | None) -> str:
    """``getRestUrl()`` → ``rest_url``; with parameters, ``getUser(name)`` → ``get_user``."""
    if java == "toString":
        return "__repr__"
    if params == 0 and re.match(r"get[A-Z]", java):
        return snake(java[3:])
    return snake(java)


def _split(params: str) -> list[str]:
    out, depth, current = [], 0, ""
    for c in params:
        depth += c == "<"
        depth -= c == ">"
        if c == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += c
    return [p.strip() for p in [*out, current] if p.strip()]


@dataclass
class JavaType:
    path: str  # Tm1Source.PrivateImage
    kind: str
    constants: set[str] = field(default_factory=set)
    components: list[str] = field(default_factory=list)
    methods: dict[str, list[list[str]]] = field(default_factory=dict)


def java_types() -> dict[str, JavaType]:
    """The public types of the module and their public members."""
    out: dict[str, JavaType] = {}
    for path in sorted(JAVA.glob("*.java")):
        source = _TOKEN.sub(lambda t: '""' if t.group(0)[0] in "\"'" else "", path.read_text(encoding="utf-8"))
        # One line per signature: a parameter list wrapped over lines joined up
        joined, parens = [], 0
        for c in source:
            parens += (c == "(") - (c == ")")
            joined.append(" " if c == "\n" and parens > 0 else c)
        source = "".join(joined)
        stack: list[tuple[int, JavaType | None]] = []  # (body depth, type, or None for a non-public one)
        depth = 0
        for raw in source.splitlines():
            line = raw.strip()
            while stack and depth < stack[-1][0]:
                stack.pop()
            owner = stack[-1][1] if stack and stack[-1][0] == depth else None
            in_interface = owner is not None and owner.kind in ("interface", "@interface")
            if (m := _TYPE.match(line)) is not None and (not stack or stack[-1][0] == depth):
                public = "public" in m["mods"].split() or (in_interface and "private" not in m["mods"])
                parent_public = not stack or stack[-1][1] is not None
                java_type = None
                if public and parent_public:
                    name = m["name"] if owner is None else f"{owner.path}.{m['name']}"
                    java_type = out.setdefault(name, JavaType(name, m["kind"]))
                    if m["components"]:
                        java_type.components = [p.split()[-1] for p in _split(m["components"])]
                stack.append((depth + 1, java_type))
            elif owner is not None:
                public = lambda mods: "public" in mods.split() or (in_interface and "private" not in mods.split())  # noqa: E731
                if owner.kind == "enum" and (e := _ENUM_CONSTANT.match(line)) is not None and not _METHOD.match(line):
                    owner.constants.add(e["name"])
                elif (f := _FIELD.match(line)) is not None and public(f["mods"]) and ("static" in f["mods"] or in_interface):
                    owner.constants.add(f["name"])
                elif (meth := _METHOD.match(line)) is not None and public(meth["mods"]) and meth["type"] not in ("new", "return"):
                    if meth["name"] != owner.path.split(".")[-1] and not raw.lstrip().startswith("@"):
                        owner.methods.setdefault(meth["name"], []).append(_split(meth["params"]))
            depth += raw.count("{") - raw.count("}")
    return out


JAVA_TYPES = java_types()


def python_type(path: str) -> Any:
    obj: Any = tm1_testcontainers
    for part in path.split("."):
        obj = getattr(obj, part, None)
        if obj is None:
            return None
    return obj


def has(cls: Any, name: str) -> bool:
    return hasattr(cls, name) or name in getattr(cls, "__dataclass_fields__", {})


def test_the_java_sources_are_read() -> None:
    assert {"Tm1ServerContainer", "Tm1Source", "Tm1Source.Bundle", "CognosContainer.Namespace", "PlanningAnalyticsEnvironment.Builder"} <= set(JAVA_TYPES)
    assert "withSeed" in JAVA_TYPES["Tm1ServerContainer"].methods
    assert JAVA_TYPES["Tm1Source.Bundle"].components == ["dir", "file"]
    assert {"EMPTY", "SDATA"} <= JAVA_TYPES["Tm1Seed"].constants
    assert "Tm1Extension" not in JAVA_TYPES  # not public: the pytest plugin instead


@pytest.mark.parametrize("path", sorted(JAVA_TYPES))
def test_every_public_type_and_member_is_ported(path: str) -> None:
    java = JAVA_TYPES[path]
    cls = python_type(path)
    assert inspect.isclass(cls), f"No Python class for Java's {path}"
    missing = []
    for constant in sorted(java.constants):
        if not has(cls, constant):
            missing.append(constant)
    for component in java.components:
        if not has(cls, snake(component)):
            missing.append(f"{component} (record component)")
    for name, overloads in sorted(java.methods.items()):
        for params in overloads:
            if (path, f"{name}/{len(params)}") in JAVA_ONLY or (path, name) in JAVA_ONLY:
                continue
            py = python_name(name, len(params))
            if not has(cls, py):
                missing.append(f"{name} → {py}")
                continue
            member = inspect.getattr_static(cls, py)
            if isinstance(member, property):
                assert not params, f"{path}.{py} is a property, but Java's {name} takes {params}"
                continue
            function = getattr(cls, py)
            if not callable(function):
                continue
            bound = isinstance(member, (staticmethod, classmethod))
            args = [None] * (len(params) + (0 if bound else 1))
            try:
                inspect.signature(function).bind(*args)
            except TypeError:
                missing.append(f"{name}({', '.join(params)}) → {py} can't take {len(params)} argument(s)")
    assert not missing, f"{path} lacks Java's " + "; ".join(missing)


def test_java_only_members_are_still_in_java() -> None:
    for (path, member), reason in JAVA_ONLY.items():
        assert reason
        name, _, count = member.partition("/")
        overloads = JAVA_TYPES[path].methods.get(name, [])
        assert any(not count or len(p) == int(count) for p in overloads), f"{path}.{member} is gone from Java: drop it from JAVA_ONLY"


def _java_constants() -> dict[str, str]:
    out = {}
    for path in JAVA.glob("*.java"):
        for name, value in re.findall(r'\b([A-Z][A-Z0-9_]*)\s*=\s*"((?:\\.|[^"\\])*)"\s*;', path.read_text(encoding="utf-8")):
            out[f"{path.stem}.{name}"] = value
    return out


def _java_message(type_name: str, constant: str) -> str:
    constants = _java_constants()
    source = (JAVA / f"{type_name}.java").read_text(encoding="utf-8")
    expression = re.search(rf"\b{constant}\s*=\s*(.*?);", source, re.S)
    assert expression is not None
    text = ""
    for part in re.findall(r'"(?:\\.|[^"\\])*"|[\w.]+', expression.group(1)):
        text += part[1:-1].replace('\\"', '"') if part.startswith('"') else constants[part]
    return text


@pytest.mark.parametrize(
    "java,python",
    [
        (("RequiresTm1", "NO_SOURCE_MESSAGE"), tm1_testcontainers.RequiresTm1.NO_SOURCE_MESSAGE),
        (("RequiresCognos", "NO_SOURCE_MESSAGE"), tm1_testcontainers.RequiresCognos.NO_SOURCE_MESSAGE),
        (("RequiresTm1Web", "NO_SOURCE_MESSAGE"), tm1_testcontainers.RequiresTm1Web.NO_SOURCE_MESSAGE),
        (("RequiresPaw", "NO_SOURCE_MESSAGE"), tm1_testcontainers.RequiresPaw.NO_SOURCE_MESSAGE),
        (("RequiresPaw", "NO_LICENSE_MESSAGE"), tm1_testcontainers.RequiresPaw.NO_LICENSE_MESSAGE),
    ],
)
def test_skip_messages_are_javas(java: tuple[str, str], python: str) -> None:
    assert python == _java_message(*java)


def test_the_junit_extensions_are_the_plugins_markers_and_fixtures() -> None:
    assert f'"{plugin.NO_DOCKER_MESSAGE}"' in (JAVA / "Tm1Extension.java").read_text(encoding="utf-8")
    extensions = {"Tm1Extension": "tm1_server", "CognosExtension": "cognos", "Tm1WebExtension": "tm1_web", "PawExtension": "paw"}
    for extension, fixture in extensions.items():
        assert (JAVA / f"{extension}.java").exists()
        assert callable(getattr(plugin, fixture)), f"No {fixture} fixture for Java's {extension}"
    annotations = {p.stem for p in JAVA.glob("Requires*.java")}
    assert {"requires_" + snake(a[len("Requires") :]) for a in annotations} == set(plugin._MARKERS)


def test_paw_local_sh_is_the_repositorys() -> None:
    from tm1_testcontainers import paw

    assert paw.script() == (ROOT / "docker" / "paw" / "paw-local.sh").read_bytes()
