# SPDX-License-Identifier: Apache-2.0

"""The test counts CI puts in each job's summary (scripts/test-summary.py)."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import load

summary = load("scripts/test-summary.py")

TRX = """<?xml version="1.0" encoding="utf-8"?>
<TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
  <ResultSummary outcome="Completed">
    <Counters total="{total}" executed="{executed}" passed="{passed}" failed="{failed}" error="0" notExecuted="0" />
  </ResultSummary>
</TestRun>
"""


def write(root: Path, name: str, content: str) -> None:
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content, encoding="utf-8")


def test_counts_each_java_module_and_task_from_gradles_reports(tmp_path: Path) -> None:
    results = "java/tm1-client/build/test-results"
    write(
        tmp_path,
        f"{results}/test/TEST-a.xml",
        '<testsuite name="a" tests="10" skipped="4" failures="1" errors="0"/>',
    )
    write(
        tmp_path,
        f"{results}/test/TEST-b.xml",
        '<testsuite name="b" tests="5" skipped="0" failures="0" errors="1"/>',
    )
    write(
        tmp_path,
        f"{results}/liveTest/TEST-c.xml",
        '<testsuite name="c" tests="3" skipped="3" failures="0" errors="0"/>',
    )
    write(
        tmp_path,
        "java/tools/example/build/test-results/test/TEST-d.xml",
        '<testsuite name="d" tests="2" skipped="0" failures="0" errors="0"/>',
    )
    write(tmp_path, f"{results}/test/binary/results.bin", "not a report")
    suites = summary.collect(tmp_path)
    assert list(suites) == [
        "Java tm1-client (liveTest)",
        "Java tm1-client",
        "Java tools/example",
    ]
    client = suites["Java tm1-client"]
    assert (client.tests, client.passed, client.failed, client.skipped) == (15, 9, 2, 4)
    assert suites["Java tm1-client (liveTest)"].skipped == 3


def test_reads_pytests_and_vitests_junit_and_dotnets_trx(tmp_path: Path) -> None:
    results = "build/test-results"
    write(
        tmp_path,
        f"{results}/python/pytest.xml",
        '<testsuites><testsuite name="pytest" errors="0" failures="0" skipped="220" tests="2916"/></testsuites>',
    )
    write(
        tmp_path,
        f"{results}/typescript/tm1-client.xml",
        '<testsuites tests="9" failures="1"><testsuite tests="4" failures="1" errors="0" skipped="1"/><testsuite tests="5" failures="0" errors="0" skipped="5"/></testsuites>',
    )
    write(
        tmp_path,
        f"{results}/dotnet/a.trx",
        TRX.format(total=1410, executed=1256, passed=1256, failed=0),
    )
    write(
        tmp_path,
        f"{results}/dotnet/b.trx",
        TRX.format(total=75, executed=63, passed=62, failed=1),
    )
    write(tmp_path, f"{results}/dotnet/notes.txt", "not a report")
    suites = {
        name: (c.tests, c.passed, c.failed, c.skipped)
        for name, c in summary.collect(tmp_path).items()
    }
    assert suites == {
        "dotnet": (1485, 1318, 1, 166),
        "python": (2916, 2696, 0, 220),
        "typescript": (9, 2, 1, 6),
    }


def test_prints_a_table(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    write(
        tmp_path,
        "build/test-results/python/pytest.xml",
        '<testsuite tests="3" failures="0" errors="0" skipped="1"/>',
    )
    assert summary.main(["summary", str(tmp_path)]) == 0
    assert capsys.readouterr().out.splitlines() == [
        "| Suite | Tests | Passed | Failed | Skipped |",
        "|---|---:|---:|---:|---:|",
        "| python | 3 | 2 | 0 | 1 |",
    ]


def test_fails_when_nothing_was_tested(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert summary.main(["summary", str(tmp_path)]) == 1
    assert "No test results" in capsys.readouterr().err
