# SPDX-License-Identifier: Apache-2.0

"""spec/conformance/pa-environment, the files every client's PlanningAnalyticsEnvironment.fromConfig
tests read, against docker/pa-setup/pa_setup.py, the reference reader: it accepts the valid files,
and refuses each invalid one with the message the clients give, unless the file says pa-setup
accepts it (what pa-setup runs and the environment can't)."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

import pytest
from conftest import REPO

FIXTURES = REPO / "spec" / "conformance" / "pa-environment"
# TM1 v12's, beside the container conformance scenarios
ENGINE_FIXTURES = REPO / "testcontainers-conformance" / "pa-environment"
VALID = [
    FIXTURES / "settings.yaml",
    FIXTURES / "paw.yaml",
    FIXTURES / "paw-cognos.yaml",
    REPO / "docker" / "pa-setup" / "examples" / "everything.yaml",
    REPO / "docker" / "pa-setup" / "examples" / "tm1-engine.yaml",
    ENGINE_FIXTURES / "tm1-engine.yaml",
    ENGINE_FIXTURES / "oidc-true.yaml",
]
INVALID = sorted((FIXTURES / "invalid").glob("*.yaml")) + sorted(
    (ENGINE_FIXTURES / "invalid").glob("*.yaml")
)


@pytest.fixture(scope="module")
def pa_setup() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "pa_setup", REPO / "docker" / "pa-setup" / "pa_setup.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["pa_setup"] = module
    spec.loader.exec_module(module)
    # Paths in the file are the host's; pa_setup only joins them, so any will do
    module.HOST_HOME = "/home/tester"
    return module


@pytest.mark.parametrize("path", VALID, ids=lambda p: p.name)
def test_pa_setup_accepts_the_valid_files(pa_setup: ModuleType, path: Path) -> None:
    pa_setup.load(str(path))


def test_there_are_invalid_files() -> None:
    assert len(INVALID) > 10


@pytest.mark.parametrize("path", INVALID, ids=lambda p: p.name)
def test_pa_setup_refuses_the_invalid_files_as_the_clients_do(
    pa_setup: ModuleType, path: Path
) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    assert lines[0].startswith("# error: ")
    if "# pa-setup: accepts" in lines[:3]:
        pa_setup.load(str(path))
        return
    with pytest.raises(pa_setup.ConfigError) as refused:
        pa_setup.load(str(path))
    assert lines[0].removeprefix("# error: ") in str(refused.value)
