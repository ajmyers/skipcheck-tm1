"""The layout and lint checks, as part of the tests: ``pytest`` fails on a file Ruff would reformat
or a finding it reports, as the Java build's ``checkFormat`` fails ``./gradlew build``. Fix with
``ruff format tm1-python`` and ``ruff check --fix tm1-python`` (the pre-commit hook formats what
you stage)."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("command", [["format", "--check"], ["check"]], ids=["layout", "lint"])
def test_the_code_is_formatted_and_lint_free(command: list[str]) -> None:
    done = subprocess.run([sys.executable, "-m", "ruff", *command, str(ROOT)], capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr
