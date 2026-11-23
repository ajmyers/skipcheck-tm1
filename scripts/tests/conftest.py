# SPDX-License-Identifier: Apache-2.0

"""Tests of the repository's own tooling: the scripts in scripts/, the git hooks in .githooks/, and
the checks over the shell scripts and workflows. Run with ./gradlew checkScripts, or
python -m pytest scripts/tests.
"""

from __future__ import annotations

import importlib.util
import os
import subprocess
from pathlib import Path
from types import ModuleType

import pytest

REPO = Path(__file__).resolve().parents[2]


def load(path: str) -> ModuleType:
    """A script as a module: their names have hyphens, and they aren't in a package."""
    file = REPO / path
    spec = importlib.util.spec_from_file_location(file.stem.replace("-", "_"), file)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def clean_environment(**extra: str) -> dict[str, str]:
    """The environment for a hook: without what CI sets (the hooks do nothing there), and without
    the variables git sets for a hook it runs, which would point these at the wrong repository."""
    environment = {k: v for k, v in os.environ.items() if k not in ("CI", "GITHUB_ACTIONS", "SKIP_FORMAT_HOOK") and not k.startswith("GIT_")}
    environment.update(
        GIT_AUTHOR_NAME="Test",
        GIT_AUTHOR_EMAIL="test@example.invalid",
        GIT_COMMITTER_NAME="Test",
        GIT_COMMITTER_EMAIL="test@example.invalid",
        GIT_CONFIG_GLOBAL=os.devnull,
        GIT_CONFIG_SYSTEM=os.devnull,
    )
    environment.update(extra)
    return environment


def git(repository: Path, *arguments: str, environment: dict[str, str] | None = None) -> str:
    result = subprocess.run(["git", *arguments], cwd=repository, env=environment or clean_environment(), capture_output=True, text=True, check=True)
    return result.stdout.strip()


@pytest.fixture
def repository(tmp_path: Path) -> Path:
    """An empty git repository."""
    root = tmp_path / "repository"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    return root
