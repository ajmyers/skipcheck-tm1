# SPDX-License-Identifier: Apache-2.0

"""Starting from nothing with pa-setup: the options that say where TM1 comes from, the file
they write, and an installed tm1_64 folder as the TM1 servers' install."""

from __future__ import annotations

import builtins
import importlib.util
import stat
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
import yaml
from conftest import REPO

BUNDLE = "tm1server-2.1.25-26101616-linuxi38664h_bundle.tar.gz"


@pytest.fixture
def pa_setup(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "pa_setup_start", REPO / "docker" / "pa-setup" / "pa_setup.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["pa_setup_start"] = module
    spec.loader.exec_module(module)
    # Paths in the file are the host's; pa_setup only joins them, so any will do
    module.HOST_HOME = "/home/tester"
    module.HOST_DIR = "/work/env"
    module.WORK = str(tmp_path)
    monkeypatch.delenv("CI", raising=False)
    monkeypatch.delenv("PA_SETUP_IMAGE", raising=False)
    return module


@pytest.fixture
def ran(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch) -> list[list[str]]:
    """The docker commands run, each answered as done."""
    calls: list[list[str]] = []

    def run(cmd: list[str], **kwargs: Any) -> Any:
        calls.append(cmd)
        return type("Done", (), {"returncode": 0, "stdout": "", "stderr": ""})()

    monkeypatch.setattr(pa_setup.subprocess, "run", run)
    return calls


def main(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, *argv: str) -> int:
    monkeypatch.setattr(sys, "argv", ["setup", *argv])
    code = pa_setup.main()
    assert isinstance(code, int)
    return code


def options(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, *argv: str
) -> tuple[dict[str, Any], dict[str, str]]:
    """What init writes for these options: the file, parsed, and the .env settings."""
    assert main(pa_setup, monkeypatch, "init", *argv) == 0
    work = Path(pa_setup.WORK)
    written = yaml.safe_load((work / "pa-environment.yaml").read_text())
    return written, pa_setup.read_env()


def generated(pa_setup: ModuleType) -> dict[str, Any]:
    result: dict[str, Any] = pa_setup.generate(
        pa_setup.load(str(Path(pa_setup.WORK) / "pa-environment.yaml"))
    )
    return result


def test_a_bundle_file_is_one_tm1_server_from_its_folder(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    written, env = options(pa_setup, monkeypatch, "--bundle", f"~/Downloads/{BUNDLE}")
    # The folder as it was written, for the file to stay readable; and this bundle of whatever
    # else a Downloads folder holds
    assert written == {
        "version": 1,
        "ibm": "~/Downloads",
        "tm1": {"servers": {"tm1": {"port": 8010}}},
    }
    assert env == {"TM1_BUNDLE": f"/ibm/{BUNDLE}"}
    # And it's a file pa-setup runs
    tm1 = generated(pa_setup)["services"]["tm1"]
    assert "/home/tester/Downloads:/ibm:ro" in tm1["volumes"]
    assert tm1["environment"]["TM1_BUNDLE"] == "${TM1_BUNDLE:-}"


@pytest.mark.parametrize(
    "name",
    ["tm1server-2.1.2-1-linuxi38664h.zip", "tm1_linuxi38664h_11.8.1.2_ml.tar.gz"],
)
def test_the_older_downloads_are_bundles_too(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, name: str
) -> None:
    assert options(pa_setup, monkeypatch, "--bundle", f"/dl/{name}")[1] == {
        "TM1_BUNDLE": f"/ibm/{name}"
    }


def test_tm1_v12s_package_is_an_engine_and_no_tm1_server(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    written, env = options(
        pa_setup,
        monkeypatch,
        "--bundle",
        "C:\\Users\\me\\Downloads\\tm1-v12.6.7-standalone-linux.tgz",
    )
    assert written == {
        "version": 1,
        "ibm": "C:\\Users\\me\\Downloads",
        "tm1Engine": True,
    }
    assert env == {}
    assert list(generated(pa_setup)["services"]) == ["tm1-engine-mongo", "tm1-engine"]


def test_every_other_choice_goes_into_the_file(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    written, env = options(
        pa_setup, monkeypatch, "--ibm", "./downloads", "--samples", "--engine", "--image", "registry.example/tm1:1",
        "--files-dir", "/data/pa", "--source", "bundle",
    )  # fmt: skip
    assert written == {
        "version": 1,
        "ibm": "./downloads",
        "files": "/data/pa",
        "images": {"tm1Server": "registry.example/tm1:1"},
        "tm1": {
            "servers": {"tm1": {"port": 8010}, "sdata": {"port": 8011, "seed": "sdata"}}
        },
        "tm1Engine": True,
    }
    assert env == {}


def test_a_link_and_its_hash_go_into_env_not_the_file(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    written, env = options(
        pa_setup,
        monkeypatch,
        "--url",
        "https://x.example/b.tar.gz?sig=a%2Fb",
        "--sha256",
        "AB" * 32,
    )
    # A pre-signed link is a secret; and nothing is in an ibm folder
    assert written == {"version": 1, "tm1": {"servers": {"tm1": {"port": 8010}}}}
    assert env == {
        "TM1_BUNDLE_URL": "https://x.example/b.tar.gz?sig=a%2Fb",
        "TM1_BUNDLE_SHA256": "ab" * 32,
    }
    assert stat.S_IMODE((Path(pa_setup.WORK) / ".env").stat().st_mode) == 0o600


def test_an_installed_folder_is_the_tm1_servers_install(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    written, env = options(
        pa_setup, monkeypatch, "--tm1-home", "~/ibm/tm1_64", "--samples"
    )
    assert "ibm" not in written
    assert env == {"TM1_HOME_DIR": "/home/tester/ibm/tm1_64"}
    made = generated(pa_setup)
    # Every TM1 server, and their Admin Server, run the folder: no volume to install into
    for service in ("tm1", "sdata", "tm1-admin"):
        assert (
            "/home/tester/ibm/tm1_64:/opt/ibm/cognos/tm1_64"
            in made["services"][service]["volumes"]
        )
    assert "tm1-install" not in made["volumes"]
    # Without it, the volume a bundle is installed into
    (Path(pa_setup.WORK) / ".env").write_text("# TM1_HOME_DIR=/x\nOTHER='kept'\n")
    made = generated(pa_setup)
    assert "tm1-install:/opt/ibm/cognos/tm1_64" in made["services"]["tm1"]["volumes"]
    assert "tm1-install" in made["volumes"]


@pytest.mark.parametrize(
    ("argv", "message"),
    [
        (
            ["--bundle", "/dl/tm1server-2.1.25-1-win64_bundle.zip"],
            "isn't the Linux TM1 Server download",
        ),
        (
            ["--bundle", "/dl/x.tgz", "--url", "https://x"],
            "Choose one TM1 source: --bundle and --url",
        ),
        (["--ibm", "/dl", "--tm1-home", "/t"], "Choose one TM1 source"),
        (["--url", "ftp://x"], "must start with https://"),
        (
            ["--url", "https://x", "--sha256", "abc"],
            "--sha256 must be 64 hex characters",
        ),
        (["--sha256", "ab" * 32], "--sha256 checks the file --url downloads"),
        (["--dir", "tm1", "--bundle", "/dl/x"], "mkdir -p tm1 && cd tm1"),
        (["--base-url", "https://x"], "--base-url isn't needed any more"),
        (["--source", "image"], "--source image needs --image"),
        (["--timeout", "soon"], "--timeout is a number of seconds"),
        (["--timeout", "0"], "--timeout is a number of seconds"),
    ],
)
def test_refuses_what_it_cant_start_from(
    pa_setup: ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    argv: list[str],
    message: str,
) -> None:
    assert main(pa_setup, monkeypatch, "init", *argv) == 2
    assert message in capsys.readouterr().err
    assert not (Path(pa_setup.WORK) / "pa-environment.yaml").exists()


def test_looks_on_the_host_for_what_it_was_pointed_at(
    pa_setup: ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    # This container sees only the current folder: another one looks, with the folder mounted
    asked: list[list[str]] = []
    found: dict[str, Any] = {"code": 1, "said": b""}

    def run(cmd: list[str], **kwargs: Any) -> Any:
        asked.append(cmd)
        return type(
            "Done",
            (),
            {"returncode": found["code"], "stdout": b"", "stderr": found["said"]},
        )()

    monkeypatch.setattr(pa_setup.subprocess, "run", run)
    monkeypatch.setenv("PA_SETUP_IMAGE", "pa-setup:test")
    assert main(pa_setup, monkeypatch, "init", "--bundle", f"/dl/{BUNDLE}") == 2
    assert f"--bundle: there's no {BUNDLE} in /dl" in capsys.readouterr().err
    # --mount, not -v: a folder that isn't there is refused, where -v would make it
    mount = "type=bind,source=/dl,target=/host,readonly"
    assert asked == [
        [
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            "test",
            "--mount",
            mount,
            "pa-setup:test",
            "-e",
            f"/host/{BUNDLE}",
        ]
    ]
    assert main(pa_setup, monkeypatch, "init", "--tm1-home", "/opt/tm1") == 2
    assert (
        "/opt/tm1 isn't an installed TM1 Server: there's no bin64 in it"
        in capsys.readouterr().err
    )
    # A folder that isn't there is Docker's to refuse, and an answer all the same
    found["code"], found["said"] = (
        125,
        b"invalid mount config for type bind: bind source path does not exist: /dl",
    )
    assert main(pa_setup, monkeypatch, "init", "--bundle", f"/dl/{BUNDLE}") == 2
    assert f"--bundle: there's no {BUNDLE} in /dl" in capsys.readouterr().err
    # Found: written. And when Docker can't say (125 for another reason), the file is written too:
    # the start will say what's wrong
    for code in (0, 125):
        found["code"], found["said"] = code, b"something else"
        (Path(pa_setup.WORK) / "pa-environment.yaml").unlink(missing_ok=True)
        assert main(pa_setup, monkeypatch, "init", "--bundle", f"/dl/{BUNDLE}") == 0


def test_no_command_starts_from_what_was_said(
    pa_setup: ModuleType,
    ran: list[list[str]],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    work = Path(pa_setup.WORK)
    # The old one-liner: where TM1 comes from, and it's written and started
    assert (
        main(
            pa_setup,
            monkeypatch,
            "--bundle",
            f"/dl/{BUNDLE}",
            "--yes",
            "--timeout",
            "600",
        )
        == 0
    )
    assert (work / "pa-environment.yaml").exists() and (work / "compose.yaml").exists()
    up = [cmd for cmd in ran if "up" in cmd]
    assert len(up) == 1 and up[0][-2:] == ["--wait-timeout", "600"]
    assert "Ready:" in capsys.readouterr().out
    # Again: what's here is started, and the options, which are for a new file, are said to be unused
    ran.clear()
    assert main(pa_setup, monkeypatch, "--url", "https://x.example/other") == 0
    assert "Using the pa-environment.yaml that's here" in capsys.readouterr().out
    assert "TM1_BUNDLE_URL" not in pa_setup.read_env()
    assert next(cmd for cmd in ran if "up" in cmd)[-2:] == ["--wait-timeout", "1800"]


def test_no_start_writes_the_files_and_starts_nothing(
    pa_setup: ModuleType, ran: list[list[str]], monkeypatch: pytest.MonkeyPatch
) -> None:
    assert main(pa_setup, monkeypatch, "--tm1-home", "/opt/tm1_64", "--no-start") == 0
    work = Path(pa_setup.WORK)
    assert (
        (work / "pa-environment.yaml").exists()
        and (work / "compose.yaml").exists()
        and (work / ".env").exists()
    )
    assert ran == []


def test_nothing_said_and_nobody_to_ask_says_what_to_do(
    pa_setup: ModuleType,
    ran: list[list[str]],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert main(pa_setup, monkeypatch) == 2
    assert (
        "There's no pa-environment.yaml here. Say where TM1 comes from (--bundle FILE"
        in capsys.readouterr().err
    )
    assert ran == [] and not (Path(pa_setup.WORK) / "pa-environment.yaml").exists()
    # A command that isn't a start takes none of the options for one
    (Path(pa_setup.WORK) / "pa-environment.yaml").write_text(
        "version: 1\ntm1: {servers: {tm1: {}}}\n"
    )
    assert main(pa_setup, monkeypatch, "up", "--bundle", "/dl/x") == 2
    assert (
        "'up' takes none of the options for starting from nothing"
        in capsys.readouterr().err
    )
    assert main(pa_setup, monkeypatch, "down", "--no-start") == 2


SERVER = {"tm1": {"port": 8010}}


@pytest.mark.parametrize(
    ("answers", "written", "env"),
    [
        (
            ["", f"~/Downloads/{BUNDLE}", "y"],
            {
                "ibm": "~/Downloads",
                "tm1": {
                    "servers": {**SERVER, "sdata": {"port": 8011, "seed": "sdata"}}
                },
            },
            {"TM1_BUNDLE": f"/ibm/{BUNDLE}"},
        ),
        (
            ["2", "https://x.example/b", ""],
            {"tm1": {"servers": SERVER}},
            {"TM1_BUNDLE_URL": "https://x.example/b"},
        ),
        (
            ["3", "/opt/tm1_64", "n"],
            {"tm1": {"servers": SERVER}},
            {"TM1_HOME_DIR": "/opt/tm1_64"},
        ),
        (
            ["4", "/dl/tm1-v12.6.7-standalone-linux.tgz"],
            {"ibm": "/dl", "tm1Engine": True},
            {},
        ),
    ],
)
def test_asks_where_tm1_comes_from_when_someone_is_there(
    pa_setup: ModuleType,
    ran: list[list[str]],
    monkeypatch: pytest.MonkeyPatch,
    answers: list[str],
    written: dict[str, Any],
    env: dict[str, str],
) -> None:
    monkeypatch.setattr(pa_setup, "interactive", lambda a: not a.yes)
    asked = iter(answers)
    monkeypatch.setattr(builtins, "input", lambda prompt="": next(asked))
    assert main(pa_setup, monkeypatch, "--no-start") == 0
    assert yaml.safe_load(
        (Path(pa_setup.WORK) / "pa-environment.yaml").read_text()
    ) == {"version": 1, **written}
    assert pa_setup.read_env() == env
    assert list(asked) == []


def test_asks_nothing_when_told_or_when_nobody_is_there(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    class Terminal:
        def isatty(self) -> bool:
            return True

    class Args:
        yes = False

    args = Args()
    monkeypatch.setattr(sys, "stdin", Terminal())
    monkeypatch.setattr(sys, "stdout", Terminal())
    assert pa_setup.interactive(args) is True
    monkeypatch.setenv("CI", "true")
    assert pa_setup.interactive(args) is False
    monkeypatch.delenv("CI")
    args.yes = True
    assert pa_setup.interactive(args) is False


def test_init_with_nothing_said_writes_the_file_to_edit(
    pa_setup: ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert main(pa_setup, monkeypatch, "init") == 0
    path = Path(pa_setup.WORK) / "pa-environment.yaml"
    assert path.read_text() == pa_setup.SAMPLE
    # A file that's there is never written over
    assert main(pa_setup, monkeypatch, "init", "--bundle", f"/dl/{BUNDLE}") == 2
    assert "already exists" in capsys.readouterr().err
    assert path.read_text() == pa_setup.SAMPLE


def test_env_settings_are_set_beside_what_is_there(pa_setup: ModuleType) -> None:
    env = Path(pa_setup.WORK) / ".env"
    env.write_text(
        '# mine\nCOGNOS_ZIP_URL=https://kept\nTM1_BUNDLE_URL=https://old\n QUOTED = "a b" \n'
    )
    pa_setup.write_env({"TM1_BUNDLE_URL": "https://new", "TM1_HOME_DIR": "/opt/tm1_64"})
    assert env.read_text() == (
        '# mine\nCOGNOS_ZIP_URL=https://kept\n QUOTED = "a b" \nTM1_BUNDLE_URL=https://new\nTM1_HOME_DIR=/opt/tm1_64\n'
    )
    assert pa_setup.read_env() == {
        "COGNOS_ZIP_URL": "https://kept",
        "QUOTED": "a b",
        "TM1_BUNDLE_URL": "https://new",
        "TM1_HOME_DIR": "/opt/tm1_64",
    }


def test_pull_fetches_the_images_of_what_the_file_runs(
    pa_setup: ModuleType,
    ran: list[list[str]],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    (Path(pa_setup.WORK) / "pa-environment.yaml").write_text(
        "version: 1\ntm1: {servers: {tm1: {}}}\n"
    )
    assert main(pa_setup, monkeypatch, "pull") == 0
    assert (
        len(ran) == 1 and ran[0][:2] == ["docker", "compose"] and ran[0][-1] == "pull"
    )
    assert "Pulled" in capsys.readouterr().out


def test_help_says_everything_and_runs_nothing(
    pa_setup: ModuleType,
    ran: list[list[str]],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit) as helped:
        main(pa_setup, monkeypatch, "--help")
    out = capsys.readouterr().out
    assert helped.value.code == 0 and ran == []
    for said in (
        "--bundle FILE",
        "--tm1-home DIR",
        "pa-setup pull",
        "--no-start",
        "pa-setup session",
    ):
        assert said in out
