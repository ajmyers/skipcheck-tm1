# SPDX-License-Identifier: Apache-2.0

"""pa-setup's TM1 v12 engine (the tm1Engine section of pa-environment.yaml): what the file may say,
the compose services generated from it, what 'up' prints, and the engine's commands. The tool is
run for real, against TM1 v12, by hand: CI has no IBM software."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
import yaml
from conftest import REPO

SETUP = REPO / "docker" / "pa-setup"


@pytest.fixture(scope="module")
def pa_setup() -> ModuleType:
    spec = importlib.util.spec_from_file_location("pa_setup_engine", SETUP / "pa_setup.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.HOST_DIR = "/host/project"
    module.HOST_HOME = "/home/tester"
    return module


def model(pa_setup: ModuleType, text: str) -> dict[str, Any]:
    read = pa_setup.normalize(yaml.safe_load("version: 1\n" + text))
    assert isinstance(read, dict)
    return read


def refused(pa_setup: ModuleType, text: str) -> str:
    with pytest.raises(pa_setup.ConfigError) as error:
        model(pa_setup, text)
    return str(error.value)


@pytest.mark.parametrize("on", ["true", "{}"])
def test_on_alone_is_one_database_and_no_users(pa_setup: ModuleType, on: str) -> None:
    # true and an empty mapping both mean "on, with the defaults"
    engine = model(pa_setup, f"tm1Engine: {on}\n")["tm1Engine"]
    assert engine == {
        "port": 4444,
        "databases": {"tm1": {"instance": "tm1"}},
        "users": [],
        "datastorePort": None,
    }


@pytest.mark.parametrize("off", ["false", "null", "~"])
def test_off_is_no_engine(pa_setup: ModuleType, off: str) -> None:
    assert model(pa_setup, f"tm1: {{servers: {{tm1: {{}}}}}}\ntm1Engine: {off}\n")["tm1Engine"] is None
    assert "nothing to run" in refused(pa_setup, f"tm1Engine: {off}\n")


def test_reads_every_setting(pa_setup: ModuleType) -> None:
    engine = model(
        pa_setup,
        """
tm1Engine:
  port: 4445
  databases:
    planning:
    Sales: {}
    sales: { instance: other }
  users:
    - analyst::Analysts,Planners
    - viewer
    - boss:bosspw:ADMIN
    - { name: planner, groups: [Planners] }
  datastore: { port: 27018 }
""",
    )["tm1Engine"]
    assert engine["port"] == 4445 and engine["datastorePort"] == 27018
    # Sales and sales are two databases to TM1 v12
    assert engine["databases"] == {
        "planning": {"instance": "tm1"},
        "Sales": {"instance": "tm1"},
        "sales": {"instance": "other"},
    }
    assert [(u["name"], u["password"], u["groups"]) for u in engine["users"]] == [
        ("analyst", "", ["Analysts", "Planners"]),
        ("viewer", "", []),
        ("boss", "bosspw", ["ADMIN"]),
        ("planner", "", ["Planners"]),
    ]


@pytest.mark.parametrize(
    ("text", "message"),
    [
        ("tm1Engine: {prot: 1}", "tm1Engine: unknown setting(s) prot"),
        # Not in pa-setup yet: they come with the logins themselves
        ("tm1Engine: {login: oauth2}", "tm1Engine: unknown setting(s) login"),
        ("tm1Engine: {passthrough: {server: legacy}}", "unknown setting(s) passthrough"),
        ("tm1Engine: {databases: {}}", "tm1Engine.databases: name at least one database"),
        ("tm1Engine: {databases: [planning]}", "tm1Engine.databases: name at least one database"),
        ("tm1Engine: {databases: {ab: {}}}", "tm1Engine.databases.ab: TM1 needs database names of at least 3"),
        ("tm1Engine: {databases: {'two words': {}}}", "database names are letters, digits, _ and -"),
        ("tm1Engine: {databases: {_tm1: {}}}", "starting with a letter or digit"),
        ("tm1Engine: {databases: {planning: {instance: a/b}}}", "tm1Engine.databases.planning.instance: instance names"),
        ("tm1Engine: {databases: {planning: {seed: sdata}}}", "tm1Engine.databases.planning: unknown setting(s) seed"),
        ("tm1Engine: {users: ['bad user']}", "tm1Engine.users[0]: user name"),
        ("tm1Engine: {users: ['a:b:c:d']}", "tm1Engine.users[0]: expected user:password"),
        ("tm1Engine: {users: [analyst, Analyst]}", "tm1Engine.users: a user is listed twice"),
        ("tm1Engine: {port: 0}", "tm1Engine: port must be a number"),
        ("tm1Engine: {datastore: {prot: 1}}", "tm1Engine.datastore: unknown setting(s) prot"),
        ("tm1Engine: {port: 8010}\ntm1: {servers: {tm1: {}}}", "port(s) 8010 used by more than one service"),
        ("tm1Engine: {port: 4444, datastore: {port: 4444}}", "port(s) 4444 used by more than one service"),
        ("tm1Engine: true\ntm1Web: true", "tm1Web: needs a TM1 server"),
        ("ibm: ./ibm\ntm1Engine: true\npaw: {acceptLicense: true}", "paw: needs a TM1 server"),
    ],
)
def test_refuses_what_it_cant_run_and_says_what(pa_setup: ModuleType, text: str, message: str) -> None:
    assert message in refused(pa_setup, text + "\n")


def test_a_cognos_user_still_needs_a_password(pa_setup: ModuleType) -> None:
    # Only the engine's users may go without: Cognos's log in with theirs
    text = "tm1: {servers: {tm1: {}}}\ncognos: {users: ['admin::Admins']}\n"
    assert "admin's password must be non-empty" in refused(pa_setup, text)


def test_generates_the_engine_and_its_mongodb(pa_setup: ModuleType) -> None:
    generated = pa_setup.generate(
        model(
            pa_setup,
            """
ibm: ~/ibm
tm1Engine:
  databases: { planning: {}, sales: { instance: other } }
  users: [ 'analyst:pw:Analysts,Planners', viewer ]
""",
        )
    )
    engine, mongo = generated["services"]["tm1-engine"], generated["services"]["tm1-engine-mongo"]
    assert engine["image"] == "ghcr.io/ajmyers/tm1-engine:edge" and engine["platform"] == "linux/amd64"
    assert engine["ports"] == ["4444:4444"]
    assert engine["environment"]["TM1_DATABASES"] == "tm1/planning,other/sales"
    # The image's form: groups after the name, and no password, which TM1 v12 doesn't keep
    assert engine["environment"]["TM1_USERS"] == "analyst:Analysts:Planners,viewer"
    assert engine["environment"]["TM1_DATASTORE_URL"].endswith("@tm1-engine-mongo:27017")
    # A database is stopped through TM1 first, which commits it; and MongoDB is up first
    assert engine["stop_grace_period"] == "2m"
    assert engine["depends_on"] == {"tm1-engine-mongo": {"condition": "service_healthy"}}
    assert engine["volumes"] == [
        "tm1-engine-cache:/tm1/cache",
        "/home/tester/ibm:/ibm:ro",
        "/host/project/pa-files/tm1-engine/deployment:/tm1/deployment",
        "/host/project/pa-files/tm1-engine/credentials:/tm1/credentials",
    ]
    # The deployment and MongoDB's data are kept side by side
    assert mongo["volumes"] == ["/host/project/pa-files/tm1-engine/datastore:/data/db"]
    assert mongo["image"] == "mongo:8.0" and "ports" not in mongo and "healthcheck" in mongo
    assert "tm1-engine-cache" in generated["volumes"]


def test_publishes_the_datastore_only_when_asked_and_takes_other_images(pa_setup: ModuleType) -> None:
    generated = pa_setup.generate(
        model(
            pa_setup,
            "images: {tm1Engine: 'tm1-engine:local', mongo: 'mongo:8.2'}\n"
            "tm1Engine: {port: 4500, datastore: {port: 27018}}\n",
        )
    )
    assert generated["services"]["tm1-engine"]["image"] == "tm1-engine:local"
    assert generated["services"]["tm1-engine"]["ports"] == ["4500:4444"]
    assert generated["services"]["tm1-engine-mongo"]["image"] == "mongo:8.2"
    assert generated["services"]["tm1-engine-mongo"]["ports"] == ["27018:27017"]
    assert "TM1_USERS" not in generated["services"]["tm1-engine"]["environment"]


def test_an_engine_beside_tm1_11_leaves_its_services_as_they_were(pa_setup: ModuleType) -> None:
    alone = pa_setup.generate(model(pa_setup, "tm1: {servers: {legacy: {seed: sdata}}}\n"))
    both = pa_setup.generate(model(pa_setup, "tm1: {servers: {legacy: {seed: sdata}}}\ntm1Engine: true\n"))
    assert both["services"]["legacy"] == alone["services"]["legacy"]
    assert list(both["services"]) == ["legacy", "tm1-engine-mongo", "tm1-engine"]


def test_prints_where_the_credentials_are_and_never_a_secret(
    pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    pa_setup.urls(
        model(
            pa_setup,
            "tm1Engine: {port: 4500, databases: {planning: {}, sales: {instance: other}}, users: [analyst, Admin]}\n",
        )
    )
    out = capsys.readouterr().out
    assert "http://localhost:4500/manage/v1/" in out
    assert "http://localhost:4500/tm1/api/v1/Databases('planning')/" in out
    assert "http://localhost:4500/other/api/v1/Databases('sales')/" in out
    assert "/host/project/pa-files/tm1-engine/credentials/root.json" in out
    assert "/host/project/pa-files/tm1-engine/credentials/other/tm1.json" in out
    # admin is always there, and is named once
    assert "(as admin or analyst)" in out
    assert "setup.sh session planning" in out
    assert pa_setup.DATASTORE_PASSWORD not in out


def test_counts_the_engine_and_each_database_in_the_memory_it_needs(pa_setup: ModuleType) -> None:
    one = pa_setup.memory_needed(model(pa_setup, "tm1Engine: true\n"))
    three = pa_setup.memory_needed(model(pa_setup, "tm1Engine: {databases: {aaa: {}, bbb: {}, ccc: {}}}\n"))
    assert 0.9 < one < 1.5 and three > one
    both = pa_setup.memory_needed(model(pa_setup, "tm1: {servers: {tm1: {}}}\ntm1Engine: true\n"))
    assert both == pytest.approx(one + 0.5)


@pytest.fixture
def ran(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch) -> list[list[str]]:
    """What the engine's commands would run in the engine's container."""
    calls: list[list[str]] = []

    def compose(m: dict[str, Any], *args: str, check: bool = True) -> Any:
        calls.append(list(args))
        return type("Done", (), {"returncode": 0})()

    monkeypatch.setattr(pa_setup, "compose", compose)
    return calls


ENGINE = "tm1Engine: {databases: {planning: {}, sales: {instance: other}}}\n"
EXEC = ["exec", "-T", "tm1-engine", "tm1-engine"]


@pytest.mark.parametrize(
    ("command", "args", "sent"),
    [
        ("credentials", [], ["credentials"]),
        ("credentials", ["--json"], ["credentials", "--json"]),
        # A session is an instance's: the database says which
        ("session", [], ["session", "tm1"]),
        ("session", ["planning"], ["session", "tm1"]),
        ("session", ["sales", "analyst"], ["session", "other", "analyst"]),
        ("session", ["other/sales"], ["session", "other"]),
        ("database", ["create", "scratch"], ["database", "create", "tm1/scratch"]),
        ("database", ["create", "other/scratch"], ["database", "create", "other/scratch"]),
        # One the file names is dropped from the instance the file puts it in
        ("database", ["drop", "sales"], ["database", "drop", "other/sales"]),
    ],
)
def test_runs_the_images_own_command_in_the_engine(
    pa_setup: ModuleType, ran: list[list[str]], command: str, args: list[str], sent: list[str]
) -> None:
    assert pa_setup.engine_main(model(pa_setup, ENGINE), command, args) == 0
    assert ran == [EXEC + sent]


@pytest.mark.parametrize(
    ("command", "args", "message"),
    [
        ("credentials", ["--yaml"], "usage: credentials"),
        ("session", ["a", "b", "c"], "usage: session"),
        ("database", [], "usage: database create NAME"),
        ("database", ["rename", "a"], "usage: database create NAME"),
        ("database", ["create"], "usage: database create NAME"),
    ],
)
def test_refuses_a_command_it_doesnt_know_how_to_run(
    pa_setup: ModuleType, ran: list[list[str]], command: str, args: list[str], message: str
) -> None:
    with pytest.raises(pa_setup.ConfigError, match=message):
        pa_setup.engine_main(model(pa_setup, ENGINE), command, args)
    assert ran == []


def test_the_engines_commands_need_an_engine(pa_setup: ModuleType, ran: list[list[str]]) -> None:
    with pytest.raises(pa_setup.ConfigError, match="add tm1Engine to the file"):
        pa_setup.engine_main(model(pa_setup, "tm1: {servers: {tm1: {}}}\n"), "credentials", [])
    assert ran == []


def test_the_example_is_valid_and_fits_the_schema_it_documents(pa_setup: ModuleType) -> None:
    example = yaml.safe_load((SETUP / "examples" / "tm1-engine.yaml").read_text(encoding="utf-8"))
    engine = pa_setup.normalize(example)["tm1Engine"]
    assert list(engine["databases"]) == ["planning", "sales"]
    schema = json.loads((SETUP / "pa-environment.schema.json").read_text(encoding="utf-8"))
    described = schema["properties"]["tm1Engine"]["properties"]
    # Every setting the tool takes is in the schema, and nothing else
    assert sorted(described) == ["databases", "datastore", "port", "users"]
    assert set(example["tm1Engine"]) <= set(described)
    assert {"tm1Engine", "mongo"} <= set(schema["properties"]["images"]["properties"])
