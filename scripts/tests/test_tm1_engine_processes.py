# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-engine's processes.sh, which the entrypoint sources to kill what's left of TM1's
processes as the container stops: found by the install's path in their command lines, wherever it
starts, since under an emulator (Docker Desktop's Rosetta) the line begins with the emulator's
path. The image itself is tested by docker/tm1-engine/ci-smoke-test.sh, which CI runs without an
emulator."""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest
from conftest import REPO

PROCESSES = REPO / "docker" / "tm1-engine" / "docker" / "processes.sh"
HOME = "/tm1/cache/tm1-v12.5.0"

needs_bash = pytest.mark.skipif(
    shutil.which("bash") is None or shutil.which("sed") is None,
    reason="needs bash and sed",
)
needs_pkill = pytest.mark.skipif(
    shutil.which("pkill") is None
    or shutil.which("bash") is None
    or shutil.which("readlink") is None,
    reason="needs pkill, readlink and bash",
)


def bash(script: str, folder: str) -> subprocess.CompletedProcess[str]:
    """Runs the script with processes.sh sourced and the folder in $FOLDER. Not as an argument:
    the shell's own line would then hold the folder, and pkill would kill the shell."""
    return subprocess.run(
        ["bash", "-c", '. "$0"; ' + script, str(PROCESSES)],
        capture_output=True,
        text=True,
        timeout=60,
        env={**os.environ, "FOLDER": folder},
    )


def matches(folder: str, line: str) -> bool:
    pattern = bash('install_pattern "$FOLDER"', folder).stdout
    return (
        subprocess.run(["grep", "-qE", pattern], input=line, text=True).returncode == 0
    )


@needs_bash
def test_finds_tm1s_processes_as_run_and_behind_an_emulator() -> None:
    assert matches(HOME, f"{HOME}/bin/tm1sd.exe -z /tm1/deployment")
    assert matches(
        HOME, f"/run/rosetta/rosetta {HOME}/bin/tm1sd.exe -z /tm1/deployment"
    ), "Rosetta's line"
    assert matches(
        HOME, f"/run/rosetta/rosetta {HOME}/third_party/datastore/mongod.bin --port 27017"
    )
    assert matches(HOME + "/", f"{HOME}/bin/tm1sd.exe"), "a trailing slash is one slash"


@needs_bash
def test_finds_nothing_else() -> None:
    assert not matches(HOME, "/usr/bin/tini -- /usr/local/bin/tm1-engine-entrypoint")
    assert not matches(HOME, "tail -n 0 -F logs/tm1sd.jsonl")
    assert not matches(HOME, f"{HOME}2/bin/tm1sd.exe"), "another folder beginning so"
    assert not matches(HOME, f"x{HOME}/bin/tm1sd.exe"), "only after the start or a space"
    assert not matches(HOME, "/tm1/cacheXtm1-v12.5.0/bin/tm1sd.exe"), "a dot is a dot"
    assert not matches(HOME, f"{HOME}"), "the folder itself is no process of its"


def sleeper(folder: Path) -> subprocess.Popen[bytes]:
    """A script in the folder's bin, run as "/bin/sh <folder>/bin/tm1sd.exe": its line starts with
    the interpreter, as an emulated one starts with the emulator."""
    script = folder / "bin" / "tm1sd.exe"
    script.parent.mkdir(parents=True)
    script.write_text("#!/bin/sh\nwhile :; do sleep 1; done\n")  # the shell stays
    return subprocess.Popen(["/bin/sh", str(script)])


@needs_pkill
def test_kills_what_runs_from_the_install_behind_its_link(tmp_path: Path) -> None:
    # The entrypoint is given /opt/ibm/tm1-engine, a link to the cache, where TM1 really runs from
    install = (tmp_path / "cache" / "tm1-v12").resolve()
    other = (tmp_path / "cache" / "tm1-v12-other").resolve()
    link = tmp_path / "tm1-engine"
    tm1, bystander = sleeper(install), sleeper(other)
    link.symlink_to(install)
    try:
        assert bash('kill_install "$FOLDER"', str(link)).returncode == 0
        assert tm1.wait(timeout=10) == -9, "killed with SIGKILL"
        assert bystander.poll() is None, "another folder's process is left alone"
    finally:
        for process in (tm1, bystander):
            process.kill()
            process.wait()


@needs_pkill
def test_kills_nothing_for_a_folder_that_isnt_there_or_is_the_root(tmp_path: Path) -> None:
    bystander = sleeper(tmp_path.resolve())
    try:
        assert bash('kill_install "$FOLDER"', str(tmp_path / "nowhere")).returncode == 0
        assert bash('kill_install "$FOLDER"', "/").returncode == 0
        assert bystander.poll() is None
    finally:
        bystander.kill()
        bystander.wait()
