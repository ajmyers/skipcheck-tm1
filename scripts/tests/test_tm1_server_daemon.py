# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-server's daemon.sh, which the entrypoint sources to find the daemonized TM1 server and
Admin Server by their command lines: matched wherever they start, since under an emulator (Docker
Desktop's Rosetta) the line begins with the emulator's path. The image itself is tested by
docker/tm1-server/ci-smoke-test.sh, which CI runs without an emulator."""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
from pathlib import Path

import pytest
from conftest import REPO

DAEMON = REPO / "docker" / "tm1-server" / "docker" / "daemon.sh"
SERVER = "./tm1s.exe -w -z/tm1/config"

needs_bash = pytest.mark.skipif(
    shutil.which("bash") is None or shutil.which("sed") is None,
    reason="needs bash and sed",
)
needs_pgrep = pytest.mark.skipif(
    shutil.which("pgrep") is None or shutil.which("bash") is None,
    reason="needs pgrep and bash",
)


def bash(
    script: str, command: str, cwd: Path | None = None
) -> subprocess.CompletedProcess[str]:
    """Runs the script with daemon.sh sourced and the command line in $COMMAND. Not as an argument:
    the shell's own line would then end with it, and pgrep (procps leaves out only itself) would
    find the shell."""
    return subprocess.run(
        ["bash", "-c", '. "$0"; ' + script, str(DAEMON)],
        capture_output=True,
        text=True,
        cwd=cwd,
        timeout=60,
        env={**os.environ, "COMMAND": command},
    )


def matches(command: str, line: str) -> bool:
    pattern = bash('daemon_pattern "$COMMAND"', command).stdout
    return (
        subprocess.run(["grep", "-qE", pattern], input=line, text=True).returncode == 0
    )


@needs_bash
def test_finds_the_command_line_as_run_and_behind_an_emulator() -> None:
    assert matches(SERVER, SERVER)
    assert matches(
        SERVER, "/run/rosetta/rosetta ./tm1s.exe ./tm1s.exe -w -z/tm1/config"
    ), "Rosetta's line"
    assert matches(
        "./tm1admsrv.exe", "/run/rosetta/rosetta ./tm1admsrv.exe ./tm1admsrv.exe"
    )


@needs_bash
def test_finds_nothing_else() -> None:
    # The entrypoint's other servers: the CAM setup's, and the real one when /tm1/config is read-only
    assert not matches(SERVER, "./tm1s.exe -w -z/tmp/tm1-cam-setup"), (
        "the CAM setup's server"
    )
    assert not matches(
        "./tm1s.exe -w -z/tmp/tm1-cam-setup", "./tm1s.exe -w -z/tmp/tm1-config"
    )
    assert not matches(SERVER, "./tm1s.exe -w -z/tm1/config2")
    assert not matches(SERVER, "x./tm1s.exe -w -z/tm1/config"), (
        "only after the start or a space"
    )
    assert not matches(SERVER, "./tm1sXexe -w -z/tm1/config"), "a dot is a dot"
    assert not matches("./tm1admsrv.exe", "./tm1s.exe -w -z/tm1/config")


@needs_pgrep
def test_waits_for_a_daemon_behind_an_interpreter_and_prints_its_pid(
    tmp_path: Path,
) -> None:
    # A script named tm1s.exe runs as "/bin/sh ./tm1s.exe -w -z<dir>": its line starts with the
    # interpreter, as an emulated one starts with the emulator. Its own config path keeps a real
    # tm1-server (a Linux host's pgrep sees into its containers) from being found instead.
    command = f"./tm1s.exe -w -z{tmp_path}"
    (tmp_path / "tm1s.exe").write_text("#!/bin/sh\nsleep 30\n")
    (tmp_path / "tm1s.exe").chmod(0o755)
    daemon = subprocess.Popen(
        ["./tm1s.exe", "-w", f"-z{tmp_path}"], cwd=tmp_path, start_new_session=True
    )
    try:
        found = bash('daemon_pid "$COMMAND" 5', command, cwd=tmp_path)
        assert found.returncode == 0, found.stderr
        assert found.stdout.strip() == str(daemon.pid)
    finally:
        os.killpg(daemon.pid, signal.SIGKILL)  # its sleep too
        daemon.wait()


@needs_pgrep
def test_gives_up_when_no_daemon_starts(tmp_path: Path) -> None:
    assert (
        bash(
            'daemon_pid "$COMMAND" 1', f"./tm1s.exe -w -z{tmp_path}/nowhere"
        ).returncode
        == 1
    )
