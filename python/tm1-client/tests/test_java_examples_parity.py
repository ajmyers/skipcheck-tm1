"""The guide's examples are the Java ones, file for file and region for region, read from the Java
sources themselves (java/<module>/src/examples/java/examples/*.java):

* each Java examples class has its file in python/<module>/examples, named in snake case
  (``CubeServiceExamples`` is ``cube_service_examples.py``);
* with the same regions, marked the same way in a comment (``# @start region = name`` to
  ``# @end``): the guide (api-site/scripts/guide.mjs) shows a region in every language behind tabs,
  so a region missing here is a tab missing there, and one only here is shown nowhere.

The examples aren't run (they need a TM1): Pyright checks them with the packages
(python/pyrightconfig.json, tests/test_types.py), and Ruff formats and lints them.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from .test_java_live_parity import snake

ROOT = Path(__file__).resolve().parents[3]
MODULES = ["tm1-client", "tm1-testcontainers"]

_START = re.compile(r"@start\s+region\s*=\s*\"?([\w-]+)\"?")
_END = re.compile(r"@end\b")


def regions(text: str) -> list[str]:
    """The names of the regions of an examples file, in order; a region that isn't closed, or one
    opened inside another, is an error."""
    found, current = [], None
    for number, line in enumerate(text.splitlines(), 1):
        start = _START.search(line)
        if start:
            assert current is None, f"line {number}: region {start.group(1)} starts inside {current}"
            current = start.group(1)
            found.append(current)
        elif _END.search(line):
            assert current is not None, f"line {number}: @end with no region open"
            current = None
    assert current is None, f"region {current} is never closed"
    return found


def java_examples(module: str) -> dict[str, list[str]]:
    """Each Java examples class of a module, with its regions."""
    folder = ROOT / "java" / module / "src" / "examples" / "java" / "examples"
    return {path.stem: regions(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*.java"))}


def problems(java: dict[str, list[str]], python: dict[str, list[str]]) -> list[str]:
    """What keeps a module's examples from being Java's: ``python`` is each examples file here,
    with its regions."""
    out = []
    expected = {snake(java_class) + ".py": java_class for java_class in java}
    for file, java_class in expected.items():
        if file not in python:
            out.append(f"{java_class} has no examples/{file}")
            continue
        for name in sorted({*python[file]} | {*java[java_class]}):
            here, there = python[file].count(name), java[java_class].count(name)
            if here == 0:
                out.append(f"examples/{file} lacks the region {name} of {java_class}")
            elif there == 0:
                out.append(f"examples/{file} has the region {name}, which {java_class} doesn't")
            elif here != there:
                out.append(f"examples/{file} has the region {name} {here} times, {java_class} {there}")
    for file in sorted(set(python) - set(expected)):
        out.append(f"examples/{file} isn't a Java examples class's file")
    return out


@pytest.mark.parametrize("module", MODULES)
def test_every_java_example_has_its_python_one(module: str) -> None:
    java = java_examples(module)
    assert java and any(java.values()), f"the Java examples of {module} weren't found"
    folder = ROOT / "python" / module / "examples"
    python = {path.name: regions(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*.py"))}
    found = problems(java, python)
    assert not found, "\n".join(found)


# The gate itself


def test_reads_the_regions_of_a_file() -> None:
    java = "class A {\n\t// @start region = list\n\tcode();\n\t// @end\n\t// @start region = update-rules\n\t// @end\n}\n"
    assert regions(java) == ["list", "update-rules"]
    assert regions("# @start region = list\ncode()\n# @end\n") == ["list"]
    with pytest.raises(AssertionError, match="never closed"):
        regions("# @start region = list\ncode()\n")
    with pytest.raises(AssertionError, match="starts inside"):
        regions("# @start region = a\n# @start region = b\n# @end\n")


JAVA = {"CubeServiceExamples": ["list", "create"], "HelloWorld": ["hello-world"]}
COMPLETE = {"cube_service_examples.py": ["list", "create"], "hello_world.py": ["hello-world"]}


def test_passes_when_every_file_and_region_is_here() -> None:
    assert problems(JAVA, COMPLETE) == []
    assert problems(JAVA, {**COMPLETE, "cube_service_examples.py": ["create", "list"]}) == [], "in any order"


def test_fails_on_a_missing_file_or_region() -> None:
    (missing,) = problems(JAVA, {"hello_world.py": ["hello-world"]})
    assert "CubeServiceExamples has no examples/cube_service_examples.py" in missing
    (missing,) = problems(JAVA, {**COMPLETE, "cube_service_examples.py": ["list"]})
    assert "lacks the region create of CubeServiceExamples" in missing


def test_fails_on_a_file_or_region_java_doesnt_have() -> None:
    (extra,) = problems(JAVA, {**COMPLETE, "cube_service_examples.py": ["list", "create", "delete"]})
    assert "has the region delete, which CubeServiceExamples doesn't" in extra
    (extra,) = problems(JAVA, {**COMPLETE, "view_examples.py": ["list"]})
    assert "examples/view_examples.py isn't a Java examples class's file" in extra
    (extra,) = problems(JAVA, {**COMPLETE, "hello_world.py": ["hello-world", "hello-world"]})
    assert "has the region hello-world 2 times" in extra
