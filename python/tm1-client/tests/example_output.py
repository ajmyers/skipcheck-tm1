# SPDX-License-Identifier: Apache-2.0

"""What an example printed, recorded for the docs, as the Java tests' ExampleOutput: the walkthroughs
show each step's output under its code. A live test runs an example's steps through
:meth:`ExampleOutput.record`, and :meth:`ExampleOutput.write` writes
``api-site/example-output/<project>/python/<Example>.json``, with each region's output and a hash of
its code. The site's build fails when a region that prints has no recording, when its code has
changed since, or when the languages printed different things."""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import re
import sys
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
from typing import Any

ROOT = Path(__file__).resolve().parents[3]

_START = re.compile(r"@start\s+region\s*=\s*\"?([\w-]+)\"?")
_END = re.compile(r"@end\b")


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s).lower()


def example(name: str) -> ModuleType:
    """A tm1-client examples file, which isn't a package's module, by its Java class's name: ``HelloWorld``
    is examples/hello_world.py."""
    path = ROOT / "python" / "tm1-client" / "examples" / (snake(name) + ".py")
    spec = importlib.util.spec_from_file_location("examples_" + snake(name), path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def source_hash(source: str, region: str) -> str:
    """The hash of a region's code, as every language and the site's build work it out: the lines
    between its ``@start region = <name>`` and ``@end``, each without the whitespace around it,
    blank ones left out, joined with newlines, as the SHA-256 of their UTF-8, in hexadecimal."""
    lines: list[str] = []
    inside = found = False
    for line in source.splitlines():
        start = _START.search(line)
        if start:
            inside = start.group(1) == region
            found = found or inside
        elif inside and _END.search(line):
            inside = False
        elif inside and line.strip():
            lines.append(line.strip())
    if not found:
        raise ValueError(f"no region {region}")
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def lines(text: str) -> list[str]:
    """Printed text as lines, without their trailing whitespace and without the empty lines at the end."""
    out = [line.rstrip() for line in text.splitlines()]
    while out and not out[-1]:
        out.pop()
    return out


class ExampleOutput:
    """The recording of one examples file's regions: ``example`` is the Java class's name, such as
    ``ConnectWalkthrough`` (the file here is connect_walkthrough.py)."""

    def __init__(self, project: str, example: str) -> None:
        self.project = project
        self.example = example
        self.file = ROOT / "python" / project / "examples" / (snake(example) + ".py")
        self.source = self.file.read_text(encoding="utf-8")
        self.regions: dict[str, dict[str, Any]] = {}
        self.failed = False

    def record(self, region: str, step: Callable[[], object]) -> None:
        """Runs a region's code, keeping what it prints, which still reaches the test's output."""
        source = source_hash(self.source, region)
        printed = io.StringIO()
        try:
            with contextlib.redirect_stdout(printed):
                step()
        except BaseException:
            self.failed = True
            raise
        finally:
            sys.stdout.write(printed.getvalue())
        self.regions[region] = {"source": source, "output": lines(printed.getvalue())}

    def output(self, region: str) -> list[str]:
        """What a region printed."""
        return self.regions[region]["output"]

    def write(self) -> None:
        """Writes the recording, replacing the one before, unless a step failed: then the one before stays."""
        if self.failed:
            return
        path = ROOT / "api-site" / "example-output" / self.project / "python" / (self.example + ".json")
        path.parent.mkdir(parents=True, exist_ok=True)
        content = {"example": self.example, "language": "python", "regions": dict(sorted(self.regions.items()))}
        path.write_text(json.dumps(content, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def __enter__(self) -> ExampleOutput:
        return self

    def __exit__(self, *exc: object) -> None:
        self.write()
