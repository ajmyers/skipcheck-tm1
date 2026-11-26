# SPDX-License-Identifier: Apache-2.0

"""scripts/live-test-versions.py: the list it reads, the environment and command it runs each version
with, what it keeps of each run, and its summary, with a stand-in for Gradle (CI has no TM1)."""

from __future__ import annotations

import os
from pathlib import Path

import pytest
from conftest import load

versions = load("scripts/live-test-versions.py")


def test_reads_a_label_and_the_variables_that_give_each_tm1() -> None:
    listed = versions.parse(
        "# oldest first\n\n2.1.2  TM1_HOME_DIR=~/ibm/2.1.2/tm1_64\n2.1.25 TM1_BUNDLE_DIR=/ibm/25 TM1_BUNDLE='tm1server 2.1.25.tar.gz'  # the newest\n"
    )
    assert [v.label for v in listed] == ["2.1.2", "2.1.25"]
    assert listed[0].variables == {
        "TM1_HOME_DIR": os.path.expanduser("~/ibm/2.1.2/tm1_64")
    }
    assert listed[1].variables == {
        "TM1_BUNDLE_DIR": "/ibm/25",
        "TM1_BUNDLE": "tm1server 2.1.25.tar.gz",
    }


@pytest.mark.parametrize(
    ("text", "why"),
    [
        ("", "no TM1"),
        ("2.1.2 TM1_BUNDLE=x.tar.gz", "gives no TM1"),
        ("2.1.2 TM1_HOME_DIR", "isn't NAME=value"),
        ("2.1.2 TM1_HOME_DIR=", "gives no TM1"),
        ("TM1_HOME_DIR=/x", "not a label"),
        ("../up TM1_HOME_DIR=/x", "not a label"),
        ("'..\\..' TM1_HOME_DIR=/x", "not a label"),
        ("C: TM1_HOME_DIR=/x", "not a label"),
        ("'' TM1_HOME_DIR=/x", "not a label"),
        ("summary.md TM1_HOME_DIR=/x", "not a label"),
        ("a TM1_HOME_DIR=/x\na TM1_HOME_DIR=/y", "listed twice"),
        ("PA TM1_HOME_DIR=/x\npa TM1_HOME_DIR=/y", "listed twice"),
        ("2.1.2 TM1_HOME_DIR='/x", "line 1: No closing quotation"),
    ],
)
def test_refuses_a_list_it_cant_run(text: str, why: str) -> None:
    with pytest.raises(versions.ListError, match=why):
        versions.parse(text)


def test_reads_values_as_written_and_comments_only_at_a_words_start() -> None:
    (listed,) = versions.parse(
        "w TM1_HOME_DIR=C:\\ibm\\tm1_64 TM1_URL=https://h/api/v1 TM1_PASSWORD=pa#ss # a comment TM1_USER=nobody",
    )
    assert listed.variables == {
        "TM1_HOME_DIR": "C:\\ibm\\tm1_64",
        "TM1_URL": "https://h/api/v1",
        "TM1_PASSWORD": "pa#ss",
    }


def test_reads_a_relative_path_from_the_lists_folder(tmp_path: Path) -> None:
    (listed,) = versions.parse("r TM1_BUNDLE_DIR=ibm/pa TM1_BUNDLE=x.tar.gz", tmp_path)
    assert listed.variables == {
        "TM1_BUNDLE_DIR": str((tmp_path / "ibm/pa").resolve()),
        "TM1_BUNDLE": "x.tar.gz",
    }


def test_shows_no_password_and_no_signed_links_token() -> None:
    version = versions.Version(
        "v",
        {
            "TM1_BUNDLE_URL": "https://user:tok@bucket.s3:443/x.tar.gz?X-Amz-Signature=abc",
            "TM1_PASSWORD": "apple",
            "TM1_BUNDLE": "x.tar.gz",
        },
    )
    shown = versions.shown(version)
    assert "abc" not in shown and "tok" not in shown and "apple" not in shown
    assert (
        "TM1_BUNDLE_URL=https://bucket.s3:443/x.tar.gz" in shown
        and "TM1_BUNDLE=x.tar.gz" in shown
    )


def test_runs_each_version_with_its_tm1_and_no_other() -> None:
    base = {
        "PATH": "/bin",
        "TM1_URL": "http://mine",
        "TM1_HOME_DIR": "/old",
        "TM1_BUNDLE": "old.tar.gz",
        "TM1_PASSWORD": "x",
    }
    env = versions.environment(
        base, versions.Version("2.1.25", {"TM1_BUNDLE_DIR": "/ibm/25"})
    )
    assert env == {"PATH": "/bin", "TM1_BUNDLE_DIR": "/ibm/25"}


def test_runs_the_suites_asked_for_then_the_comparison(tmp_path: Path) -> None:
    gradlew = str(tmp_path / "gradlew")
    assert versions.command([], tmp_path, windows=False) == [
        gradlew,
        "--continue",
        ":tm1-client:liveTest",
        "liveTestPython",
        "liveTestDotnet",
        "liveTestTypescript",
        "liveTestTypescriptContainers",
        "compareLiveTraffic",
    ]
    assert versions.command(["python", "java"], tmp_path, windows=True) == [
        str(tmp_path / "gradlew.bat"),
        "--continue",
        "liveTestPython",
        ":tm1-client:liveTest",
        "compareLiveTraffic",
    ]
    with pytest.raises(versions.ListError, match="unknown suite"):
        versions.command(["cobol"])



def test_runs_every_task_of_a_suite_that_has_several(tmp_path: Path) -> None:
    # TypeScript's testcontainers tests are a task of their own, so a failing client test can't
    # leave them unrun (#437)
    assert versions.command(["typescript"], tmp_path, windows=False) == [
        str(tmp_path / "gradlew"),
        "--continue",
        "liveTestTypescript",
        "liveTestTypescriptContainers",
        "compareLiveTraffic",
    ]

def _report(path: Path, tests: int, failures: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f'<testsuite name="x" tests="{tests}" failures="{failures}" errors="0" skipped="0"/>',
        encoding="utf-8",
    )


def test_keeps_each_versions_results_and_summarises_them(tmp_path: Path) -> None:
    root = tmp_path / "repo"
    (root / "scripts").mkdir(parents=True)
    listing = tmp_path / "versions.txt"
    listing.write_text("old TM1_HOME_DIR=/a\nnew TM1_HOME_DIR=/b\n", encoding="utf-8")
    stale = root / "build" / "test-results" / "dotnet-live" / "stale.xml"
    _report(stale, 9, 9)
    seen: list[dict[str, str]] = []

    def gradle(cmd: list[str], env: dict[str, str]) -> int:
        assert not stale.exists(), "a run before's results are cleared first"
        seen.append(env)
        failing = env["TM1_HOME_DIR"] == "/a"
        _report(
            root / "java/tm1-client/build/test-results/liveTest/TEST-x.xml",
            3,
            1 if failing else 0,
        )
        _report(root / "build/test-results/python-live/pytest.xml", 2, 0)
        return 1 if failing else 0

    versions.ROOT = (
        Path(versions.__file__).resolve().parents[1]
    )  # test-summary.py stays the repository's
    assert (
        versions.main(["x", str(listing), "java", "python"], run=gradle, root=root) == 1
    )
    assert [e["TM1_HOME_DIR"] for e in seen] == ["/a", "/b"]
    kept = root / "build" / "live-versions"
    assert (
        kept / "old/java/tm1-client/build/test-results/liveTest/TEST-x.xml"
    ).exists()
    assert not (kept / "old/build/test-results/dotnet-live").exists(), (
        "only what this run wrote"
    )
    report = (kept / "summary.md").read_text(encoding="utf-8")
    assert "## old: failed (exit 1)" in report and "## new: passed" in report
    assert "| Java tm1-client (liveTest) | 3 | 2 | 1 | 0 |" in report
    assert "| python-live | 2 | 2 | 0 | 0 |" in report


def test_writes_a_summary_when_nothing_ran_or_a_report_is_cut_short(
    tmp_path: Path,
) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    listing = tmp_path / "versions.txt"
    listing.write_text("none TM1_HOME_DIR=/a\ncut TM1_HOME_DIR=/b\n", encoding="utf-8")

    def gradle(cmd: list[str], env: dict[str, str]) -> int:
        if env["TM1_HOME_DIR"] == "/b":
            report = root / "build/test-results/python-live/pytest.xml"
            report.parent.mkdir(parents=True)
            report.write_text("", encoding="utf-8")  # a suite killed while writing it
        return 1

    assert versions.main(["x", str(listing)], run=gradle, root=root) == 1
    report = (root / "build/live-versions/summary.md").read_text(encoding="utf-8")
    assert "## none: failed (exit 1)" in report and "No test results." in report
    assert "## cut: failed (exit 1)" in report and "can't be read" in report


def test_reports_a_list_it_cant_read(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    listing = tmp_path / "versions.txt"
    listing.write_bytes(b"\xff\xfe not utf-8")
    assert versions.main(["x", str(listing)], root=tmp_path) == 2
    assert "live-test-versions:" in capsys.readouterr().err


def test_says_how_to_use_it_without_a_list(capsys: pytest.CaptureFixture[str]) -> None:
    assert versions.main(["x"]) == 2
    assert "one TM1 per line" in capsys.readouterr().err
