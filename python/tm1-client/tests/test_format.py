# SPDX-License-Identifier: Apache-2.0

"""The layout and lint checks, and the license line, as part of the tests: ``pytest`` fails on a file Ruff would reformat
or a finding it reports, as the Java build's ``checkFormat`` fails ``./gradlew build``. Fix with
``ruff format python`` and ``ruff check --fix python`` (the pre-commit hook formats what
you stage)."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("command", [["format", "--check"], ["check"]], ids=["layout", "lint"])
def test_the_code_is_formatted_and_lint_free(command: list[str]) -> None:
    done = subprocess.run([sys.executable, "-m", "ruff", *command, str(ROOT)], capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr


def test_every_source_file_names_its_license() -> None:
    """Fix with ``scripts/license-headers.py --fix`` (the pre-commit hook adds the line to what you stage)."""
    repository = ROOT.parent
    script = repository / "scripts" / "license-headers.py"
    if not script.is_file():
        pytest.skip("not in the repository: an sdist has no scripts/")
    files = subprocess.run(["git", "ls-files", "python"], cwd=repository, capture_output=True, text=True, check=True).stdout.split()
    done = subprocess.run([sys.executable, str(script), *files], capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr
