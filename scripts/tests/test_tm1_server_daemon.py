# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-server's daemon.sh, which the entrypoint sources to find the daemonized TM1 server and
Admin Server by their command lines: matched wherever they start, since under an emulator (Docker
Desktop's Rosetta) the line begins with the emulator's path. The image itself is tested by
docker/tm1-server/ci-smoke-test.sh, which CI runs without an emulator."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest
from conftest import REPO

DAEMON = REPO / "docker" / "tm1-server" / "docker" / "daemon.sh"
SERVER = "./tm1s.exe -w -z/tm1/config"


def bash(script: str, *args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["bash", "-c", '. "$0"; ' + script, str(DAEMON), *args], capture_output=True, text=True, cwd=cwd, timeout=60)


def matches(command: str, line: str) -> bool:
    pattern = bash('daemon_pattern "$1"', command).stdout
    return subprocess.run(["grep", "-qE", pattern], input=line, text=True).returncode == 0


def test_finds_the_command_line_as_run_and_behind_an_emulator() -> None:
    assert matches(SERVER, SERVER)
    assert matches(SERVER, "/run/rosetta/rosetta ./tm1s.exe ./tm1s.exe -w -z/tm1/config"), "Rosetta's line"
    assert matches("./tm1admsrv.exe", "/run/rosetta/rosetta ./tm1admsrv.exe ./tm1admsrv.exe")


def test_finds_nothing_else() -> None:
    assert not matches(SERVER, "./tm1s.exe -w -z/tm1/boot"), "the CAM setup's server"
    assert not matches(SERVER, "./tm1s.exe -w -z/tm1/config2")
    assert not matches(SERVER, "x./tm1s.exe -w -z/tm1/config"), "only after the start or a space"
    assert not matches(SERVER, "./tm1sXexe -w -z/tm1/config"), "a dot is a dot"
    assert not matches("./tm1admsrv.exe", "./tm1s.exe -w -z/tm1/config")


@pytest.mark.skipif(os.name != "posix", reason="pgrep")
def test_waits_for_a_daemon_behind_an_interpreter_and_prints_its_pid(tmp_path: Path) -> None:
    # A script named tm1s.exe runs as "/bin/sh ./tm1s.exe -w -z/tm1/config": its line starts
    # with the interpreter, as an emulated one starts with the emulator
    (tmp_path / "tm1s.exe").write_text("#!/bin/sh\nsleep 30\n")
    (tmp_path / "tm1s.exe").chmod(0o755)
    daemon = subprocess.Popen(["./tm1s.exe", "-w", "-z/tm1/config"], cwd=tmp_path)
    try:
        found = bash('daemon_pid "$1" 5', SERVER, cwd=tmp_path)
        assert found.returncode == 0, found.stderr
        assert found.stdout.strip() == str(daemon.pid)
    finally:
        daemon.kill()
        daemon.wait()


def test_gives_up_when_no_daemon_starts() -> None:
    assert bash('daemon_pid "$1" 1', "./tm1s.exe -w -z/nowhere").returncode == 1
