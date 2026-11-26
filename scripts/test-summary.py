#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Prints how many tests each suite ran, passed, failed and skipped, as a Markdown table.

    scripts/test-summary.py           the results of the last run of each suite, from the repository's root
    scripts/test-summary.py <root>    another root

CI appends it to each job's summary. The live tests are skipped there, with no TM1: this is where
that shows as a number, per suite, rather than as nothing. Gradle prints no counts of its own.

It reads the reports the suites write:

    java/**/build/test-results/<task>/*.xml    Gradle's, one row per module and task
    build/test-results/<suite>/*.xml           JUnit XML: pytest (--junitxml) and vitest (its junit reporter)
    build/test-results/<suite>/*.trx           dotnet test (--logger trx)

Exits 1 if it finds no results at all: a run that tested nothing shouldn't look like one that passed.
Standard library only.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Counts:
    tests: int = 0
    failed: int = 0
    skipped: int = 0

    @property
    def passed(self) -> int:
        return self.tests - self.failed - self.skipped

    def add(self, tests: int, failed: int, skipped: int) -> None:
        self.tests += tests
        self.failed += failed
        self.skipped += skipped


def number(element: ET.Element, name: str) -> int:
    return int(element.get(name) or 0)


def read_junit(path: Path, counts: Counts) -> None:
    """A JUnit XML report: a testsuite, or testsuites of them."""
    root = ET.parse(path).getroot()
    for suite in [root] if root.tag == "testsuite" else root.iter("testsuite"):
        counts.add(
            number(suite, "tests"),
            number(suite, "failures") + number(suite, "errors"),
            number(suite, "skipped"),
        )


def read_trx(path: Path, counts: Counts) -> None:
    """A Visual Studio test report, as dotnet test writes: its Counters element."""
    for element in ET.parse(path).getroot().iter():
        if element.tag.endswith("}Counters") or element.tag == "Counters":
            total, executed = number(element, "total"), number(element, "executed")
            counts.add(total, executed - number(element, "passed"), total - executed)
            return


def collect(root: Path) -> dict[str, Counts]:
    """The counts of every suite with a report under root, by the suite's name."""
    suites: dict[str, Counts] = {}
    for report in sorted(root.glob("java/**/build/test-results/*/*.xml")):
        task = report.parent.name
        module = report.relative_to(root / "java").parts[:-4]
        name = "Java " + "/".join(module) + ("" if task == "test" else f" ({task})")
        read_junit(report, suites.setdefault(name, Counts()))
    for report in sorted((root / "build" / "test-results").glob("*/*")):
        if report.suffix not in (".xml", ".trx"):
            continue
        counts = suites.setdefault(report.parent.name, Counts())
        (read_trx if report.suffix == ".trx" else read_junit)(report, counts)
    return suites


def table(suites: dict[str, Counts]) -> str:
    lines = [
        "| Suite | Tests | Passed | Failed | Skipped |",
        "|---|---:|---:|---:|---:|",
    ]
    lines += [
        f"| {name} | {c.tests} | {c.passed} | {c.failed} | {c.skipped} |"
        for name, c in suites.items()
    ]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1]
    suites = collect(root)
    if not suites:
        print(f"No test results under {root}", file=sys.stderr)
        return 1
    print(table(suites))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
