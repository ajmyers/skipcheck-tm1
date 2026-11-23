# SPDX-License-Identifier: Apache-2.0

"""The commit message and pull request rules (.githooks/check_message.py), which the commit-msg
hook and the PR Conventions workflow both run."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest
from conftest import REPO, load

check = load(".githooks/check_message.py")

GOOD = ["Add cube listing to CubeService", "", "Lists the cubes of a server, for the model browser.", "", "Refs: #12"]


def commit_errors(*lines: str) -> list[str]:
    return check.check_commit(list(lines))


def pr_errors(*lines: str, require_issue: bool = True) -> list[str]:
    return check.check_pr(list(lines), require_issue)


def test_accepts_a_title_a_description_and_the_issue_trailer() -> None:
    assert commit_errors(*GOOD) == []


def test_accepts_other_trailers_beside_the_issue() -> None:
    assert commit_errors(*GOOD, "Co-authored-by: Someone <someone@example.invalid>") == []
    assert commit_errors(*GOOD[:4], "Refs: #12, #13") == []


@pytest.mark.parametrize(
    ("title", "problem"),
    [
        ("", "Title is empty"),
        ("A" * 73, "73 characters"),
        ("add cube listing", "capital letter"),
        ("Add cube listing.", "period"),
        ("Add cube listing (#12)", "issue numbers out of the title"),
    ],
)
def test_refuses_a_bad_title(title: str, problem: str) -> None:
    errors = commit_errors(title, *GOOD[1:])
    assert len(errors) == 1 and problem in errors[0], errors


def test_a_title_of_72_characters_is_the_longest_allowed() -> None:
    assert commit_errors("A" * 72, *GOOD[1:]) == []


def test_refuses_a_message_without_a_description() -> None:
    errors = commit_errors("Add cube listing", "", "Refs: #12")
    assert len(errors) == 1 and "Add a description" in errors[0], errors


def test_refuses_a_message_without_the_issue_trailer() -> None:
    errors = commit_errors(*GOOD[:3])
    assert len(errors) == 1 and "Refs: #12" in errors[0], errors
    # The issue named in the prose isn't the trailer
    assert commit_errors("Add cube listing", "", "It closes #12.") != []
    # Nor is a trailer that isn't the last paragraph
    assert commit_errors("Add cube listing", "", "Refs: #12", "", "And a description after it.") != []


def test_refuses_a_description_that_starts_on_the_second_line() -> None:
    errors = commit_errors("Add cube listing", "Lists the cubes.", "", "Refs: #12")
    assert any("blank line" in e for e in errors), errors


@pytest.mark.parametrize(
    "title", ["Merge branch 'master' into topic", 'Revert "Add cube listing"', "fixup! Add cube listing", "squash! Add cube listing", "amend! Add cube listing"]
)
def test_lets_through_what_git_writes_itself(title: str) -> None:
    assert commit_errors(title) == []


def test_reads_a_message_as_git_does(tmp_path: Path) -> None:
    file = tmp_path / "COMMIT_EDITMSG"
    file.write_text(
        "\n".join(["", *GOOD, "# Please enter the commit message", check.SCISSORS, "diff --git a/x b/x", "+lowercase line that is not a title."]) + "\n",
        encoding="utf-8",
    )
    assert check.read_lines(str(file), strip_comments=True) == GOOD
    # A pull request's description is taken as written: a # line is a heading there
    file.write_bytes(b"Title\r\n\r\n# Heading\r\n")
    assert check.read_lines(str(file), strip_comments=False) == ["Title", "", "# Heading"]


@pytest.mark.parametrize("link", ["Closes #12", "closes #12.", "Fixes #12", "Resolves #12", "Part of #12", "  Part of #12 (the first step)"])
def test_accepts_a_pull_request_that_links_its_issue(link: str) -> None:
    assert pr_errors("Add cube listing", "", link, "", "What changed and why.") == []


def test_refuses_a_pull_request_without_an_issue_or_a_description() -> None:
    assert any("Link the issue" in e for e in pr_errors("Add cube listing", "", "What changed and why."))
    assert any("Link the issue" in e for e in pr_errors("Add cube listing", "", "See #12 for why."))
    assert any("Add a description" in e for e in pr_errors("Add cube listing", "", "Closes #12"))
    assert any("capital letter" in e for e in pr_errors("add cube listing", "", "Closes #12", "", "Why."))


def test_a_generated_pull_request_needs_only_a_good_title() -> None:
    assert pr_errors("Bump the gradle group with 3 updates", "", "Bumps a, b and c.", require_issue=False) == []
    assert pr_errors("bump the gradle group", require_issue=False) != []


def run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(REPO / ".githooks" / "check_message.py"), *arguments], capture_output=True, text=True, check=False)


def test_exits_0_1_or_2(tmp_path: Path) -> None:
    good, bad = tmp_path / "good", tmp_path / "bad"
    good.write_text("\n".join(GOOD) + "\n", encoding="utf-8")
    bad.write_text("add cube listing\n", encoding="utf-8")
    assert run("commit", str(good)).returncode == 0
    refused = run("commit", str(bad))
    assert refused.returncode == 1 and "capital letter" in refused.stderr and "Refs: #12" in refused.stderr
    assert run("pr", str(good)).returncode == 1  # Refs isn't how a pull request links its issue
    assert run("pr", "--no-issue", str(good)).returncode == 0
    for usage in ([], ["commit"], ["push", str(good)], ["commit", "--no-issue", str(good)]):
        assert run(*usage).returncode == 2, usage
