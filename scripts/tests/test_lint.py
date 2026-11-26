# SPDX-License-Identifier: Apache-2.0

"""ShellCheck over every shell script. It has to be installed; without it the test is skipped on a
developer's machine and fails in CI, where a check that quietly didn't run would look like one
that passed."""

from __future__ import annotations

import os
import shutil
import subprocess

import pytest
from conftest import REPO, git

# Hooks have no extension
HOOKS = [".githooks/commit-msg", ".githooks/pre-commit", ".githooks/pre-push"]


def test_shell_scripts_pass_shellcheck() -> None:
    shellcheck = shutil.which("shellcheck")
    if shellcheck is None:
        if os.environ.get("CI"):
            pytest.fail("shellcheck isn't installed, and CI must run this check")
        pytest.skip(
            "shellcheck isn't installed (brew, apt or scoop install shellcheck)"
        )
    scripts = HOOKS + git(REPO, "ls-files", "*.sh").splitlines()
    assert len(scripts) > 20, scripts
    # Warnings and errors. The notes below them are style, and the scripts were written to other tastes
    result = subprocess.run(
        [shellcheck, "--severity=warning", "--external-sources", *scripts],
        cwd=REPO,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
