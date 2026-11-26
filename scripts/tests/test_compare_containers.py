# SPDX-License-Identifier: Apache-2.0

"""The comparison of the containers each language's tm1-testcontainers got from Docker
(scripts/compare-containers.py), which ends ./gradlew containerConformance."""

from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any

import pytest
from conftest import REPO, load

containers = load("scripts/compare-containers.py")

HOME = REPO / "testcontainers-conformance"
RULES = """
alias tc-*: Java's random alias
label org.testcontainers*: each library's own
"""


def inspected(
    identifier: str, *aliases: str, started: str = "2026-01-01T00:00:01Z", **config: Any
) -> dict[str, Any]:
    """A container as docker inspect prints it, cut down to what's read, on the network "net"."""
    identifier = (identifier * 64)[:64]
    return {
        "Id": identifier,
        "Image": "sha256:abc",
        "State": {"StartedAt": started},
        "Config": {
            "Image": "tm1-server:test",
            "Hostname": identifier[:12],
            "Cmd": ["start"],
            "Env": ["TM1_SEED=empty"],
            "Labels": {"org.testcontainers.sessionId": identifier},
            "ExposedPorts": {"8010/tcp": {}},
            **config,
        },
        "HostConfig": {
            "NetworkMode": "net",
            "Binds": ["cache:/tm1/cache:rw"],
            "PortBindings": {"8010/tcp": [{"HostIp": "", "HostPort": ""}]},
        },
        "Mounts": [
            {"Type": "volume", "Name": "cache", "Destination": "/tm1/cache", "RW": True}
        ],
        "NetworkSettings": {
            "Networks": {
                "net": {
                    "Aliases": [*aliases, identifier[:12]],
                    "IPAddress": "172.20.0." + str(int(identifier[0], 16)),
                }
            }
        },
    }


def log(language: str, **parts: dict[str, Any]) -> dict[str, Any]:
    """A language's log of a scenario, with its own network name and ID."""
    text = json.dumps(parts).replace('"net"', json.dumps("net-" + language))
    return {
        "scenario": "two",
        "language": language,
        "network": [
            {
                "Id": language.encode().hex().ljust(64, "0"),
                "Name": "net-" + language,
                "Driver": "bridge",
                "Containers": {
                    part["Id"]: {"Name": "random_name"}
                    for part in json.loads(text).values()
                },
            }
        ],
        "containers": {
            role: [part] for role, part in json.loads(text).items()
        },
    }


def two(language: str, first: str, second: str) -> dict[str, Any]:
    """An Admin Server and a TM1 server that names it, started after it."""
    return log(
        language,
        adminServer=inspected(first, "tm1-admin", Cmd=["admin"]),
        **{
            "tm1/planning": inspected(
                second,
                "planning",
                started="2026-01-01T00:00:09Z",
                Env=["TM1_ADMIN_HOST=tm1-admin", "PEER=" + (first * 64)[:12]],
            )
        },
    )


def read(entry: dict[str, Any], rules: str = RULES, loose: str = "") -> dict[str, Any]:
    reading = containers.normalize(
        entry, containers.rules(rules), containers.unordered(loose)
    )
    assert isinstance(reading, dict)
    return reading


def test_reads_two_runs_of_the_same_containers_alike() -> None:
    java = two("java", "a", "b")
    java["containers"]["tm1/planning"][0]["NetworkSettings"]["Networks"]["net-java"][
        "Aliases"
    ].append("tc-x1Y2")
    assert read(java) == read(two("python", "c", "d"))
    assert containers.compare({"java": read(java), "python": read(two("python", "c", "d"))}) == []


def test_puts_placeholders_for_the_runs_own_values() -> None:
    planning = read(two("java", "a", "b"))["containers"]["tm1/planning"]
    assert planning["hostname"] == "<id of tm1/planning>"
    assert "PEER=<id of adminServer>" in planning["environment"]
    assert planning["networkMode"] == "<network>"
    assert planning["networks"] == {"<network>": ["planning"]}
    assert planning["publishedPorts"] == {"8010/tcp": [":random"]}


def test_a_container_on_a_second_network_is_a_difference() -> None:
    # What Testcontainers for Node did with aliases: created on the bridge, then connected
    typescript = two("typescript", "c", "d")
    planning = typescript["containers"]["tm1/planning"][0]
    planning["HostConfig"]["NetworkMode"] = "bridge"
    planning["NetworkSettings"]["Networks"]["bridge"] = {
        "Aliases": None,
        "IPAddress": "172.17.0.4",
    }
    problems = containers.compare(
        {"java": read(two("java", "a", "b")), "typescript": read(typescript)}
    )
    assert [problem.splitlines()[0] for problem in problems] == [
        "containers.tm1/planning.networkMode",
        "containers.tm1/planning.networks.bridge",
    ]
    assert 'java: "<network>"' in problems[0] and 'typescript: "bridge"' in problems[0]


@pytest.mark.parametrize(
    ("change", "path"),
    [
        (lambda c: c["Config"].update(Env=["TM1_SEED=sdata"]), "environment"),
        (lambda c: c["Config"].update(Cmd=["admin"]), "command"),
        (lambda c: c["Config"].update(Image="tm1-server:other"), "image"),
        (lambda c: c.update(Image="sha256:def"), "imageId"),
        (lambda c: c["Config"].update(Hostname="cognos"), "hostname"),
        (lambda c: c["Config"]["Labels"].update(mine="1"), "labels.mine"),
        (lambda c: c["Config"]["ExposedPorts"].update({"5895/tcp": {}}), "exposedPorts"),
        (lambda c: c["HostConfig"].update(ExtraHosts=["tm1:10.0.0.1"]), "extraHosts"),
        (lambda c: c["HostConfig"].update(Binds=["cache:/tm1/cache:ro"]), "binds"),
        (lambda c: c["HostConfig"].update(Privileged=True), "privileged"),
        (
            lambda c: c["HostConfig"]["PortBindings"].update(
                {"8010/tcp": [{"HostIp": "", "HostPort": "8010"}]}
            ),
            "publishedPorts.8010/tcp",
        ),
        (lambda c: c["Mounts"][0].update(RW=False), "mounts"),
        (
            lambda c: c["NetworkSettings"]["Networks"]["net-python"]["Aliases"].append(
                "extra"
            ),
            "networks.<network>",
        ),
    ],
)
def test_a_setting_that_differs_is_reported_by_its_path(change: Any, path: str) -> None:
    python = two("python", "c", "d")
    change(python["containers"]["tm1/planning"][0])
    problems = containers.compare(
        {"java": read(two("java", "a", "b")), "python": read(python)}
    )
    assert [problem.splitlines()[0] for problem in problems] == [
        "containers.tm1/planning." + path
    ]


def test_a_bind_without_a_mode_is_read_write() -> None:
    dotnet = two("dotnet", "c", "d")
    for part in dotnet["containers"].values():
        part[0]["HostConfig"]["Binds"] = ["cache:/tm1/cache"]
    assert read(dotnet) == read(two("java", "a", "b"))


def test_the_librarys_label_must_name_the_language_that_ran() -> None:
    def labelled(language: str, value: str) -> dict[str, Any]:
        entry = two(language, "a", "b")
        for part in entry["containers"].values():
            part[0]["Config"]["Labels"][containers.LIBRARY_LABEL] = value
        return read(entry)

    assert labelled("java", "java") == labelled("python", "python")
    problems = containers.compare(
        {"java": labelled("java", "java"), "python": labelled("python", "java")}
    )
    assert problems and all(containers.LIBRARY_LABEL in problem for problem in problems)


def test_a_different_start_order_is_a_difference_unless_the_scenario_allows_it() -> None:
    late = two("python", "c", "d")
    late["containers"]["adminServer"][0]["State"]["StartedAt"] = "2026-01-01T00:00:20Z"
    java = read(two("java", "a", "b"))
    assert java["startedBefore"] == ["adminServer before tm1/planning"]
    assert [
        problem.splitlines()[0]
        for problem in containers.compare({"java": java, "python": read(late)})
    ] == ["startedBefore"]
    loose = "# unordered: adminServer tm1/planning\nversion: 1\n"
    assert read(two("java", "a", "b"), loose=loose) == read(late, loose=loose)


def test_a_container_of_another_run_on_the_network_is_named() -> None:
    entry = two("java", "a", "b")
    entry["network"][0]["Containers"]["f" * 64] = {"Name": "stranger"}
    assert read(entry)["network"]["containers"] == [
        "adminServer",
        "stranger",
        "tm1/planning",
    ]


def test_a_network_option_is_compared_but_not_dockers_note_of_ipv4_and_ipv6() -> None:
    entry = two("java", "a", "b")
    entry["network"][0]["Options"] = {
        "com.docker.network.enable_ipv4": "true",
        "com.docker.network.enable_ipv6": "false",
        "com.docker.network.driver.mtu": "1400",
    }
    assert read(entry)["network"]["options"] == {"com.docker.network.driver.mtu": "1400"}


def test_an_alias_that_is_also_the_hostname_is_compared() -> None:
    # Cognos: its hostname is set to its alias, and TM1 is pointed at that name
    def cognos(language: str, *aliases: str) -> dict[str, Any]:
        return read(log(language, cognos=inspected("a", *aliases, Hostname="cognos")))

    problems = containers.compare(
        {"java": cognos("java", "cognos"), "python": cognos("python")}
    )
    assert [problem.splitlines()[0] for problem in problems] == [
        "containers.cognos.networks.<network>"
    ]


def test_orders_starts_by_time_whatever_the_length_of_the_fraction() -> None:
    def started(first: str, second: str) -> list[str]:
        entry = two("java", "a", "b")
        entry["containers"]["adminServer"][0]["State"]["StartedAt"] = first
        entry["containers"]["tm1/planning"][0]["State"]["StartedAt"] = second
        order = read(entry)["startedBefore"]
        assert isinstance(order, list)
        return order

    # Docker leaves a fraction's last zeros off: as text, 01.5Z sorts before 01Z
    assert started("2026-01-01T00:00:01Z", "2026-01-01T00:00:01.5Z") == [
        "adminServer before tm1/planning"
    ]
    assert started("2026-01-01T00:00:01.12Z", "2026-01-01T00:00:01.123Z") == [
        "adminServer before tm1/planning"
    ]
    assert started("2026-01-01T00:00:01.5Z", "2026-01-01T00:00:01Z") == [
        "tm1/planning before adminServer"
    ]


@pytest.mark.parametrize(
    ("bind", "read_as"),
    [
        ("cache:/tm1/cache", "cache:/tm1/cache:rw"),
        ("cache:/tm1/cache:ro", "cache:/tm1/cache:ro"),
        ("/host/ibm:/ibm:ro,z", "/host/ibm:/ibm:ro,z"),
        # A Windows path has a colon of its own
        ("C:\\data:/tm1/data", "C:\\data:/tm1/data:rw"),
        ("C:\\data:/tm1/data:rw", "C:\\data:/tm1/data:rw"),
    ],
)
def test_reads_a_binds_mode_by_what_it_says(bind: str, read_as: str) -> None:
    entry = two("java", "a", "b")
    entry["containers"]["adminServer"][0]["HostConfig"]["Binds"] = [bind]
    assert read(entry)["containers"]["adminServer"]["binds"] == [read_as]


@pytest.mark.parametrize(
    "line",
    [
        "label: no pattern",
        "environment TM1_*: not a kind",
        "label org.testcontainers* no reason",
        "label org.testcontainers*: not ported yet",
        "label org.testcontainers*: Not fixed yet.",
    ],
)
def test_refuses_a_line_that_isnt_a_difference_with_a_reason(line: str) -> None:
    with pytest.raises(ValueError, match="differences.txt:2"):
        containers.rules("# a comment\n" + line + "\n")


def write(root: Path, language: str, entry: dict[str, Any], scenario: str = "two") -> None:
    file = root / "logs" / language / f"{scenario}.json"
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(entry), encoding="utf-8")


@pytest.fixture
def home(tmp_path: Path) -> Path:
    (tmp_path / "home" / "scenarios").mkdir(parents=True)
    (tmp_path / "home" / "scenarios" / "two.yaml").write_text("version: 1\n")
    (tmp_path / "home" / "differences.txt").write_text(RULES)
    return tmp_path


def run(home: Path, *flags: str) -> int:
    code = containers.main(["", *flags, str(home / "logs"), str(home / "home")])
    assert isinstance(code, int)
    return code


def test_passes_when_every_language_got_the_same(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    for index, language in enumerate(containers.LANGUAGES):
        entry = two(language, "1234"[index], "5678"[index])
        if language == "java":
            entry["containers"]["adminServer"][0]["NetworkSettings"]["Networks"][
                "net-java"
            ]["Aliases"].append("tc-abc")
        write(home, language, entry)
    assert run(home, "--require-all") == 0
    assert "dotnet, java, python, typescript got the same 2 containers" in capsys.readouterr().out


def test_fails_and_prints_the_difference(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write(home, "java", two("java", "a", "b"))
    other = two("python", "c", "d")
    other["containers"]["adminServer"][0]["Config"]["Cmd"] = ["start"]
    write(home, "python", other)
    assert run(home) == 1
    out = capsys.readouterr().out
    assert "two: the languages got different containers" in out
    assert "containers.adminServer.command" in out


def test_a_line_that_matched_nothing_fails_once_all_four_ran(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    # Nothing here has Java's tc- alias, so that line is stale
    for index, language in enumerate(containers.LANGUAGES[:3]):
        write(home, language, two(language, "123"[index], "567"[index]))
    assert run(home) == 0
    write(home, "typescript", two("typescript", "4", "8"))
    assert run(home) == 1
    assert "'alias tc-*' matched nothing: remove it" in capsys.readouterr().out


def test_a_line_isnt_stale_while_a_scenario_hasnt_run(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    # The stale line may be for the scenario that was skipped here, for want of its source
    (home / "home" / "scenarios" / "cognos.yaml").write_text("version: 1\n")
    for index, language in enumerate(containers.LANGUAGES):
        write(home, language, two(language, "1234"[index], "5678"[index]))
    assert run(home, "--require-all") == 0
    assert "matched nothing" not in capsys.readouterr().out


def test_requiring_all_fails_when_a_language_was_skipped(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    for index, language in enumerate(containers.LANGUAGES[:3]):
        write(home, language, two(language, "123"[index], "567"[index]))
    assert run(home) == 0
    assert run(home, "--require-all") == 1
    assert "two: typescript didn't run it, and dotnet, java, python did" in capsys.readouterr().out


def test_requiring_all_fails_when_nothing_ran(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    # Docker wasn't running, say: every test was skipped, and nothing was compared
    assert run(home, "--require-all") == 1
    assert "nothing to compare" in capsys.readouterr().out


def test_one_language_alone_has_nothing_to_compare(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write(home, "java", two("java", "a", "b"))
    assert run(home) == 0
    assert "two: only java ran it, nothing to compare" in capsys.readouterr().out


def test_no_logs_at_all_passes(home: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert run(home) == 0
    assert "nothing to compare" in capsys.readouterr().out


def test_a_log_of_a_scenario_that_isnt_there_fails(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write(home, "java", two("java", "a", "b"), scenario="gone")
    assert run(home) == 1
    assert "gone: java ran a scenario that isn't in" in capsys.readouterr().out


def test_the_repositorys_differences_have_reasons() -> None:
    found = containers.rules((HOME / "differences.txt").read_text(encoding="utf-8"))
    assert found and all(len(rule.reason) > 20 for rule in found)


def test_pa_setup_accepts_every_scenario() -> None:
    # A scenario is a pa-environment.yaml: what every language's fromConfig reads
    spec = importlib.util.spec_from_file_location(
        "pa_setup_for_scenarios", REPO / "docker" / "pa-setup" / "pa_setup.py"
    )
    assert spec is not None and spec.loader is not None
    pa_setup = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = pa_setup
    spec.loader.exec_module(pa_setup)
    pa_setup.HOST_HOME = "/home/tester"
    scenarios = sorted((HOME / "scenarios").glob("*.yaml"))
    assert len(scenarios) >= 3
    for scenario in scenarios:
        pa_setup.load(str(scenario))
        for group in containers.unordered(scenario.read_text(encoding="utf-8")):
            assert len(group) > 1, scenario.name


def test_normalizing_leaves_the_log_as_it_was() -> None:
    entry = two("java", "a", "b")
    before = copy.deepcopy(entry)
    read(entry)
    assert entry == before
