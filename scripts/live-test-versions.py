#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Runs the live suites against each TM1 in a list, one after another, and keeps each one's results.

    scripts/live-test-versions.py <list> [suite ...]       suites: java, python, dotnet, typescript (all by default)

The list is a text file, one TM1 per line: a label, then the variables that give that TM1, as
scripts/live-tests.sh takes them (blank lines and # comments are passed over):

    2.1.2    TM1_HOME_DIR=~/ibm/pa-2.1.2/tm1_64       # relative paths are read from the list's folder
    2.1.19   TM1_HOME_DIR=~/ibm/tm1_64
    2.1.25   TM1_BUNDLE_DIR=~/ibm/pa-2.1.25 TM1_BUNDLE=tm1server-2.1.25-26101616-linuxi38664h_bundle.tar.gz

For each line it runs ./gradlew --continue with the suites' live tasks and compareLiveTraffic, with
that TM1 and no other (any TM1 source already in the environment, TM1_URL included, is left out). It
then copies what the run wrote (each suite's results and the scenario traffic) to
build/live-versions/<label>/, and at the end writes build/live-versions/summary.md: per version,
whether the run passed, and each suite's counts (scripts/test-summary.py's table).

The walkthroughs' examples record what they print in api-site/example-output, which the docs show,
so after a run it holds what the last version printed: commit it only from the version the docs
show (or restore it with git checkout).

Not a Gradle task: the suites read their TM1 from the environment, and a task can't run this
project's build again with another one (the inner build would wait on the outer one's locks).
Standard library only. Exits 1 if any version's run failed, 2 on a bad list.
"""

from __future__ import annotations

import importlib.util
import os
import re
import shlex
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]

# What gives the live tests a TM1 (build.gradle.kts's liveSources), and what goes with them
SOURCES = (
    "TM1_PRIVATE_IMAGE",
    "TM1_HOME_DIR",
    "TM1_INSTALL_VOLUME",
    "TM1_BUNDLE_DIR",
    "TM1_BUNDLE_URL",
    "TM1_URL",
)
WITH_SOURCES = (
    "TM1_BUNDLE",
    "TM1_BUNDLE_SHA256",
    "TM1_USER",
    "TM1_PASSWORD",
    "TM1_NAMESPACE",
    "TM1_VERIFY",
)

# Paths in the list are read from the list's own folder, so every suite (each runs from its own) sees one
PATHS = ("TM1_HOME_DIR", "TM1_BUNDLE_DIR")
SECRET = ("TM1_PASSWORD",)
URLS = ("TM1_BUNDLE_URL", "TM1_URL")
LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
SUMMARY = "summary.md"

TASKS = {
    "java": ":tm1-client:liveTest",
    "python": "liveTestPython",
    "dotnet": "liveTestDotnet",
    "typescript": "liveTestTypescript",
}

# What a run writes, relative to the root, kept per version
RESULTS = (
    "java/tm1-client/build/test-results/liveTest",
    "build/test-results/python-live",
    "build/test-results/dotnet-live",
    "build/test-results/typescript-live",
    "build/conformance-live",
)


@dataclass(frozen=True)
class Version:
    label: str
    variables: dict[str, str]


class ListError(ValueError):
    pass


def _words(line: str, number: int) -> list[str]:
    """A line's words, as a shell splits them but for two things: a backslash is a backslash (Windows
    paths), and # starts a comment only at the start of a word (a password may hold one)."""
    lexer = shlex.shlex(line, posix=True)
    lexer.whitespace_split = True
    lexer.escape = ""
    lexer.commenters = ""
    try:
        words = list(lexer)
    except ValueError as e:
        raise ListError(f"line {number}: {e}") from e
    for i, word in enumerate(words):
        if word.startswith("#"):
            return words[:i]
    return words


def parse(text: str, base: Path | None = None) -> list[Version]:
    """The TM1s in the list, in order; relative paths read from base (the list's folder)."""
    versions: list[Version] = []
    for number, line in enumerate(text.splitlines(), 1):
        words = _words(line, number)
        if not words:
            continue
        label, assignments = words[0], words[1:]
        if not LABEL.fullmatch(label) or label.casefold() == SUMMARY:
            raise ListError(
                f"line {number}: starts with {label!r}, not a label (letters, digits, . _ -)"
            )
        variables: dict[str, str] = {}
        for assignment in assignments:
            name, equals, value = assignment.partition("=")
            if not equals or not name:
                raise ListError(f"line {number}: {assignment!r} isn't NAME=value")
            value = os.path.expanduser(value)
            if (
                name in PATHS
                and value
                and base is not None
                and not Path(value).is_absolute()
            ):
                value = str((base / value).resolve())
            variables[name] = value
        if not any(variables.get(name, "").strip() for name in SOURCES):
            raise ListError(
                f"line {number}: {label} gives no TM1 (one of {', '.join(SOURCES)}, not blank)"
            )
        if any(v.label.casefold() == label.casefold() for v in versions):
            raise ListError(
                f"line {number}: {label} is listed twice (labels are folder names, whatever their case)"
            )
        versions.append(Version(label, variables))
    if not versions:
        raise ListError("the list has no TM1")
    return versions


def shown(version: Version) -> str:
    """The version's variables for the console: a password masked, a URL without its credentials or
    query (a signed link's token), as the testcontainers libraries describe them."""
    parts = []
    for name, value in version.variables.items():
        if name in SECRET:
            value = "***"
        elif name in URLS:
            url = urlsplit(value)
            value = f"{url.scheme}://{url.hostname or ''}{f':{url.port}' if url.port else ''}{url.path}"
        parts.append(f"{name}={value}")
    return " ".join(parts)


def environment(base: Mapping[str, str], version: Version) -> dict[str, str]:
    """The environment for a version's run: the base without any TM1 of its own, and the version's."""
    env = {k: v for k, v in base.items() if k not in SOURCES and k not in WITH_SOURCES}
    env.update(version.variables)
    return env


def command(
    suites: list[str], root: Path = ROOT, windows: bool = os.name == "nt"
) -> list[str]:
    """The Gradle command line for the suites: the root's own wrapper, wherever this runs from."""
    unknown = [s for s in suites if s not in TASKS]
    if unknown:
        raise ListError(f"unknown suite {unknown[0]} ({', '.join(TASKS)})")
    gradlew = str(root / ("gradlew.bat" if windows else "gradlew"))
    return [
        gradlew,
        "--continue",
        *[TASKS[s] for s in suites or TASKS],
        "compareLiveTraffic",
    ]


def keep(root: Path, into: Path) -> None:
    """Copies what the run wrote to the version's folder, replacing what an earlier run kept."""
    shutil.rmtree(into, ignore_errors=True)
    for relative in RESULTS:
        source = root / relative
        if source.is_dir():
            shutil.copytree(source, into / relative)


def clear(root: Path) -> None:
    """Removes what a run before wrote, so a suite that didn't run this time doesn't look as if it had."""
    for relative in RESULTS:
        shutil.rmtree(root / relative, ignore_errors=True)


def _test_summary() -> object:
    spec = importlib.util.spec_from_file_location(
        "test_summary", ROOT / "scripts" / "test-summary.py"
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def summary(runs: list[tuple[str, int]], kept: Path) -> str:
    """The summary: per version, how its run ended, and its suites' counts."""
    test_summary = _test_summary()
    lines = ["# Live tests by TM1 version", ""]
    for label, code in runs:
        lines += [
            f"## {label}: {'passed' if code == 0 else f'failed (exit {code})'}",
            "",
        ]
        try:
            suites = test_summary.collect(kept / label)  # type: ignore[attr-defined]
            lines += [test_summary.table(suites) if suites else "No test results.", ""]  # type: ignore[attr-defined]
        except (ET.ParseError, OSError) as e:
            # A suite killed while writing its report: say so, and keep the rest of the summary
            lines += [f"Its results can't be read: {e}", ""]
    return "\n".join(lines)


def main(
    argv: list[str],
    run: Callable[[list[str], dict[str, str]], int] | None = None,
    root: Path = ROOT,
) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    listing = Path(argv[1])
    try:
        versions = parse(listing.read_text(encoding="utf-8"), listing.resolve().parent)
        cmd = command(argv[2:], root)
    except (OSError, ValueError) as e:  # ListError, and a list that isn't UTF-8
        print(f"live-test-versions: {e}", file=sys.stderr)
        return 2
    run = run or (lambda c, env: subprocess.run(c, cwd=root, env=env).returncode)
    kept = root / "build" / "live-versions"
    kept.mkdir(parents=True, exist_ok=True)
    runs: list[tuple[str, int]] = []
    report = ""
    for version in versions:
        print(f"==> {version.label}: {shown(version)}", flush=True)
        clear(root)
        code = run(cmd, environment(os.environ, version))
        keep(root, kept / version.label)
        runs.append((version.label, code))
        # After each version, so a run stopped partway still says how far it got
        report = summary(runs, kept)
        (kept / SUMMARY).write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0 if all(code == 0 for _, code in runs) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
