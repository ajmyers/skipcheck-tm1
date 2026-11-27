# SPDX-License-Identifier: Apache-2.0

"""docker/pa-setup's setup.sh and setup.ps1: the launchers people pipe into a shell. They check
Docker and run the pa-setup image; here docker is a script that records how it was called. The
image itself is tested by docker/pa-setup/ci-smoke-test.sh."""

from __future__ import annotations

import os
import shutil
import stat
import subprocess
from pathlib import Path

import pytest
from conftest import REPO

SETUP = REPO / "docker" / "pa-setup"


@pytest.fixture
def docker(tmp_path: Path) -> Path:
    """A folder to put first on the path, whose docker writes its arguments, one a line, to
    docker.log beside it. "docker info" fails if a file named down is there, and says the
    containers are Windows's if one named windows is. A pull is written to pulls.log."""
    folder = tmp_path / "bin"
    folder.mkdir()
    fake = folder / "docker"
    fake.write_text(
        '#!/bin/sh\nhere=$(dirname "$0")\n'
        'if [ "$1" = info ]; then\n'
        '  [ ! -f "$here/down" ] || exit 1\n'
        '  if [ "${2:-}" = --format ]; then if [ -f "$here/windows" ]; then echo windows; else echo linux; fi; fi\n'
        "  exit 0\nfi\n"
        'if [ "$1" = pull ]; then echo "$*" >> "$here/pulls.log"; exit 0; fi\n'
        'printf \'%s\\n\' "$@" > "$here/docker.log"\n',
        encoding="utf-8",
    )
    fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
    return folder


def environment(path: Path, **extra: str) -> dict[str, str]:
    """The environment for a launcher, with a folder first on the path (or, with only=True, alone on it)."""
    only = extra.pop("only", "")
    kept = {k: v for k, v in os.environ.items() if not k.startswith(("PA_", "DOCKER_"))}
    kept["PATH"] = str(path) if only else f"{path}{os.pathsep}{kept['PATH']}"
    kept.update(extra)
    return kept


def run(
    command: list[str], folder: Path, env: dict[str, str]
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=folder,
        env=env,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        check=False,
    )


def arguments(docker: Path) -> list[str]:
    return (docker / "docker.log").read_text(encoding="utf-8").splitlines()


SHELLS = [
    pytest.param(["sh", str(SETUP / "setup.sh")], id="setup.sh"),
    pytest.param(
        ["pwsh", "-NoProfile", "-File", str(SETUP / "setup.ps1")],
        id="setup.ps1",
        marks=pytest.mark.skipif(
            shutil.which("pwsh") is None and not os.environ.get("CI"),
            reason="PowerShell isn't installed",
        ),
    ),
]


@pytest.mark.parametrize("setup", SHELLS)
def test_runs_the_image_with_the_folder_and_what_was_asked(
    setup: list[str], docker: Path, tmp_path: Path
) -> None:
    work = tmp_path / "project"
    work.mkdir()
    result = run([*setup, "logs", "tm1"], work, environment(docker))
    assert result.returncode == 0, result.stderr
    called = arguments(docker)
    assert called[:2] == ["run", "--rm"] and called[-3:] == [
        "ghcr.io/ajmyers/pa-setup:edge",
        "logs",
        "tm1",
    ]
    assert "-it" not in called  # no terminal here
    assert f"{work.resolve()}:/work" in [
        str(Path(a.split(":/work")[0]).resolve()) + ":/work"
        for a in called
        if a.endswith(":/work")
    ]
    assert any(a.startswith("PA_HOST_DIR=") for a in called) and any(
        a.startswith("PA_HOST_HOME=") for a in called
    )
    assert "PA_SETUP_IMAGE=ghcr.io/ajmyers/pa-setup:edge" in called
    assert not any(a.startswith("PA_IMAGE_TAG=") for a in called)


@pytest.mark.parametrize("setup", SHELLS)
def test_takes_the_image_or_its_tag_from_the_environment(
    setup: list[str], docker: Path, tmp_path: Path
) -> None:
    assert (
        run(
            [*setup, "up"], tmp_path, environment(docker, PA_IMAGE_TAG="1.2.3")
        ).returncode
        == 0
    )
    called = arguments(docker)
    assert (
        called[-2:] == ["ghcr.io/ajmyers/pa-setup:1.2.3", "up"]
        and "PA_IMAGE_TAG=1.2.3" in called
    )
    assert (
        run(
            [*setup, "up"],
            tmp_path,
            environment(docker, PA_SETUP_IMAGE="local/pa-setup:dev"),
        ).returncode
        == 0
    )
    assert arguments(docker)[-2:] == ["local/pa-setup:dev", "up"]


@pytest.mark.parametrize("setup", SHELLS)
def test_says_so_when_docker_isnt_running(
    setup: list[str], docker: Path, tmp_path: Path
) -> None:
    (docker / "down").touch()
    result = run([*setup, "up"], tmp_path, environment(docker))
    assert result.returncode == 1 and "Docker isn't running" in result.stderr
    assert not (docker / "docker.log").exists()


@pytest.mark.skipif(
    shutil.which("pwsh") is None and not os.environ.get("CI"),
    reason="PowerShell isn't installed",
)
def test_setup_ps1_says_so_when_docker_runs_windows_containers(
    docker: Path, tmp_path: Path
) -> None:
    (docker / "windows").touch()
    setup = ["pwsh", "-NoProfile", "-File", str(SETUP / "setup.ps1")]
    result = run([*setup, "up"], tmp_path, environment(docker))
    assert (
        result.returncode == 1
        and "Docker is running Windows containers" in result.stderr
    )
    assert not (docker / "docker.log").exists()


@pytest.mark.parametrize("setup", SHELLS)
def test_fetches_the_setup_tools_image_where_an_environment_starts(
    setup: list[str], docker: Path, tmp_path: Path
) -> None:
    # A copy from an earlier day wouldn't know what the script may ask of it. Not on every
    # command: up, urls and the rest use what's here
    pulls = docker / "pulls.log"
    for arguments_given in (["up"], ["urls"], ["down"]):
        assert (
            run([*setup, *arguments_given], tmp_path, environment(docker)).returncode
            == 0
        )
    assert not pulls.exists()
    for arguments_given in ([], ["--bundle", "x"], ["init"], ["pull"]):
        assert (
            run([*setup, *arguments_given], tmp_path, environment(docker)).returncode
            == 0
        )
    assert (
        pulls.read_text(encoding="utf-8").splitlines()
        == ["pull -q ghcr.io/ajmyers/pa-setup:edge"] * 4
    )
    # And the options are handed on as they are
    assert (
        run(
            [*setup, "--bundle", "a b.tar.gz", "--no-start"],
            tmp_path,
            environment(docker),
        ).returncode
        == 0
    )
    assert arguments(docker)[-3:] == ["--bundle", "a b.tar.gz", "--no-start"]


@pytest.mark.skipif(
    shutil.which("pwsh") is None and not os.environ.get("CI"),
    reason="PowerShell isn't installed",
)
@pytest.mark.parametrize(
    "piped",
    [
        "& ([scriptblock]::Create((Get-Content -Raw '{script}'))) urls",
        "Get-Content -Raw '{script}' | Invoke-Expression",
    ],
    ids=["script block", "iex"],
)
def test_setup_ps1_piped_from_the_web_leaves_the_window_open(
    docker: Path, tmp_path: Path, piped: str
) -> None:
    # The documented one-liners run the script's text, not a file: an exit there ends the
    # user's PowerShell, window and all. What comes after it must still run
    command = piped.format(script=SETUP / "setup.ps1") + "; 'STILL HERE'"
    result = run(
        ["pwsh", "-NoProfile", "-Command", command], tmp_path, environment(docker)
    )
    assert "STILL HERE" in result.stdout, result.stdout + result.stderr
    assert arguments(docker)[-1] in ("urls", "ghcr.io/ajmyers/pa-setup:edge")


def test_setup_sh_says_so_when_docker_isnt_installed(tmp_path: Path) -> None:
    # A path with nothing on it: the real docker, where there is one, must not be found and run
    empty = tmp_path / "empty"
    empty.mkdir()
    result = run(
        ["/bin/sh", str(SETUP / "setup.sh"), "up"],
        tmp_path,
        environment(empty, only="yes"),
    )
    assert result.returncode == 1 and "pa-setup needs Docker" in result.stderr


def test_setup_sh_mounts_the_socket_docker_host_names(
    docker: Path, tmp_path: Path
) -> None:
    assert (
        run(
            ["sh", str(SETUP / "setup.sh"), "up"],
            tmp_path,
            environment(docker, DOCKER_HOST="unix:///run/user/1000/docker.sock"),
        ).returncode
        == 0
    )
    assert "/run/user/1000/docker.sock:/var/run/docker.sock" in arguments(docker)
    # A remote daemon has no socket to mount: the default stays
    assert (
        run(
            ["sh", str(SETUP / "setup.sh"), "up"],
            tmp_path,
            environment(docker, DOCKER_HOST="tcp://remote:2376"),
        ).returncode
        == 0
    )
    assert "/var/run/docker.sock:/var/run/docker.sock" in arguments(docker)
