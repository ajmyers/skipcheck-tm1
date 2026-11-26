#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Runs the live suites against each TM1 in a list, one after another, and keeps each one's results.

    scripts/live-test-versions.py <list> [suite ...]       suites: java, python, dotnet, typescript (all by default)

The list is a text file, one TM1 per line: a label, then the variables that give that TM1, as
scripts/live-tests.sh takes them (blank lines and # comments are passed over):

    2.1.2    TM1_HOME_DIR=~/ibm/pa-2.1.2/tm1_64
    2.1.19   TM1_HOME_DIR=~/ibm/tm1_64
    2.1.25   TM1_BUNDLE_DIR=~/ibm/pa-2.1.25 TM1_BUNDLE=tm1server-2.1.25-26101616-linuxi38664h_bundle.tar.gz

For each line it runs ./gradlew --continue with the suites' live tasks and compareLiveTraffic, with
that TM1 and no other (any TM1 source already in the environment, TM1_URL included, is left out). It
then copies what the run wrote (each suite's results and the scenario traffic) to
build/live-versions/<label>/, and at the end writes build/live-versions/summary.md: per version,
whether the run passed, and each suite's counts (scripts/test-summary.py's table).

Not a Gradle task: the suites read their TM1 from the environment, and a task can't run this
project's build again with another one (the inner build would wait on the outer one's locks).
Standard library only. Exits 1 if any version's run failed, 2 on a bad list.
"""

from __future__ import annotations

import importlib.util
import os
import shlex
import shutil
import subprocess
import sys
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# What gives the live tests a TM1 (build.gradle.kts's liveSources), and what goes with them
SOURCES = ("TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR", "TM1_BUNDLE_URL", "TM1_URL")
WITH_SOURCES = ("TM1_BUNDLE", "TM1_BUNDLE_SHA256", "TM1_USER", "TM1_PASSWORD", "TM1_NAMESPACE", "TM1_VERIFY")

TASKS = {"java": ":tm1-client:liveTest", "python": "liveTestPython", "dotnet": "liveTestDotnet", "typescript": "liveTestTypescript"}

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


def parse(text: str) -> list[Version]:
    """The TM1s in the list, in order."""
    versions: list[Version] = []
    for number, line in enumerate(text.splitlines(), 1):
        words = shlex.split(line, comments=True)
        if not words:
            continue
        label, assignments = words[0], words[1:]
        if "=" in label or "/" in label or label in (".", ".."):
            raise ListError(f"line {number}: starts with {label!r}, not a label")
        variables: dict[str, str] = {}
        for assignment in assignments:
            name, equals, value = assignment.partition("=")
            if not equals or not name:
                raise ListError(f"line {number}: {assignment!r} isn't NAME=value")
            variables[name] = os.path.expanduser(value)
        if not any(name in SOURCES for name in variables):
            raise ListError(f"line {number}: {label} gives no TM1 (one of {', '.join(SOURCES)})")
        if any(v.label == label for v in versions):
            raise ListError(f"line {number}: {label} is listed twice")
        versions.append(Version(label, variables))
    if not versions:
        raise ListError("the list has no TM1")
    return versions


def environment(base: Mapping[str, str], version: Version) -> dict[str, str]:
    """The environment for a version's run: the base without any TM1 of its own, and the version's."""
    env = {k: v for k, v in base.items() if k not in SOURCES and k not in WITH_SOURCES}
    env.update(version.variables)
    return env


def command(suites: list[str], windows: bool = os.name == "nt") -> list[str]:
    """The Gradle command line for the suites."""
    unknown = [s for s in suites if s not in TASKS]
    if unknown:
        raise ListError(f"unknown suite {unknown[0]} ({', '.join(TASKS)})")
    gradlew = "gradlew.bat" if windows else "./gradlew"
    return [gradlew, "--continue", *[TASKS[s] for s in suites or TASKS], "compareLiveTraffic"]


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
    spec = importlib.util.spec_from_file_location("test_summary", ROOT / "scripts" / "test-summary.py")
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
        lines += [f"## {label}: {'passed' if code == 0 else f'failed (exit {code})'}", ""]
        suites = test_summary.collect(kept / label)  # type: ignore[attr-defined]
        lines += [test_summary.table(suites) if suites else "No test results.", ""]  # type: ignore[attr-defined]
    return "\n".join(lines)


def main(argv: list[str], run: Callable[[list[str], dict[str, str]], int] | None = None, root: Path = ROOT) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        versions = parse(Path(argv[1]).read_text(encoding="utf-8"))
        cmd = command(argv[2:])
    except (OSError, ListError) as e:
        print(f"live-test-versions: {e}", file=sys.stderr)
        return 2
    run = run or (lambda c, env: subprocess.run(c, cwd=root, env=env).returncode)
    kept = root / "build" / "live-versions"
    runs: list[tuple[str, int]] = []
    for version in versions:
        print(f"==> {version.label}: {' '.join(f'{k}={v}' for k, v in version.variables.items())}", flush=True)
        clear(root)
        code = run(cmd, environment(os.environ, version))
        keep(root, kept / version.label)
        runs.append((version.label, code))
    report = summary(runs, kept)
    (kept / "summary.md").write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0 if all(code == 0 for _, code in runs) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
