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
        ("tm1Engine: {users: ['a:b:c:d']}", "tm1Engine.users[0]: expected user, user::group,group or"),
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
    assert generated["services"]["tm1-engine-mongo"]["ports"] == ["127.0.0.1:27018:27017"]
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


INVENTORY = {
    "port": 4444,
    "root": {"clientId": "rootid", "clientSecret": "rootsecret", "file": "/tm1/credentials/root.json"},
    "instances": [
        {
            "name": "tm1",
            "applications": [
                {"name": "tm1", "clientId": "appid", "clientSecret": "appsecret", "file": "/tm1/credentials/tm1/tm1.json"}
            ],
            "databases": [{"name": "planning"}, {"name": "scratch"}],
        },
        {
            "name": "other",
            "applications": [
                {"name": "tm1", "clientId": "otherid", "clientSecret": "othersecret", "file": "/tm1/credentials/other/tm1.json"}
            ],
            "databases": [{"name": "sales"}, {"name": "scratch"}, {"name": "extra"}],
        },
    ],
}


@pytest.fixture
def ran(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch) -> list[list[str]]:
    """What the engine's commands run in the engine's container, with the engine answering
    'credentials --json' as one with two instances would."""
    calls: list[list[str]] = []

    def run(cmd: list[str], **kwargs: Any) -> Any:
        assert cmd[:2] == ["docker", "compose"] and "exec" in cmd, cmd
        sent = cmd[cmd.index("exec") :]
        assert sent[:4] == ["exec", "-T", "tm1-engine", "tm1-engine"], cmd
        calls.append(sent[4:])
        out = json.dumps(INVENTORY) if sent[4:] == ["credentials", "--json"] else ""
        return type("Done", (), {"returncode": 0, "stdout": out, "stderr": ""})()

    monkeypatch.setattr(pa_setup.subprocess, "run", run)
    return calls


ENGINE = "tm1Engine: {port: 4500, databases: {planning: {}, sales: {instance: other}}}\n"
ASKED = ["credentials", "--json"]


@pytest.mark.parametrize(
    ("command", "args", "sent"),
    [
        # A session is an instance's: with no database, the file's first one's
        ("session", [], [["session", "tm1"]]),
        ("session", ["planning"], [ASKED, ["session", "tm1"]]),
        ("session", ["sales", "analyst"], [ASKED, ["session", "other", "analyst"]]),
        # One made with 'database create', which the file doesn't know: the engine does
        ("session", ["extra"], [ASKED, ["session", "other"]]),
        ("session", ["other/scratch"], [ASKED, ["session", "other"]]),
        # An instance's own name, as the image's command takes
        ("session", ["other"], [ASKED, ["session", "other"]]),
        ("database", ["create", "newone"], [["database", "create", "tm1/newone"]]),
        ("database", ["create", "other/newone"], [["database", "create", "other/newone"]]),
        ("database", ["drop", "extra"], [ASKED, ["database", "drop", "other/extra"]]),
        ("database", ["drop", "tm1/scratch"], [ASKED, ["database", "drop", "tm1/scratch"]]),
    ],
)
def test_runs_the_images_own_command_in_the_engine(
    pa_setup: ModuleType, ran: list[list[str]], command: str, args: list[str], sent: list[list[str]]
) -> None:
    assert pa_setup.engine_main(model(pa_setup, ENGINE), command, args) == 0
    assert ran == sent


def test_a_session_with_no_database_is_for_the_files_first_instance(
    pa_setup: ModuleType, ran: list[list[str]]
) -> None:
    # No database in the instance tm1 at all: 'session' alone must still log in
    only_other = "tm1Engine: {databases: {sales: {instance: other}}}\n"
    assert pa_setup.engine_main(model(pa_setup, only_other), "session", []) == 0
    assert ran == [["session", "other"]]


@pytest.mark.parametrize(
    ("command", "args", "message"),
    [
        ("credentials", ["extra"], "usage: credentials"),
        ("session", ["a", "b", "c"], "usage: session"),
        ("database", [], "usage: database create NAME"),
        ("database", ["rename", "a"], "usage: database create NAME"),
        ("database", ["create"], "usage: database create NAME"),
        # Never a guess at the instance: a wrong one would give a cookie that's refused
        ("session", ["nosuch"], "the engine has no database 'nosuch'; it has tm1/planning"),
        ("database", ["drop", "nosuch"], "the engine has no database 'nosuch'"),
        ("session", ["scratch"], "'scratch' is in several instances (tm1, other): write INSTANCE/scratch"),
        ("database", ["drop", "scratch"], "is in several instances"),
        # 'up' would make it again, empty
        ("database", ["drop", "planning"], "planning is one of the file's databases"),
        ("database", ["drop", "other/sales"], "sales is one of the file's databases"),
    ],
)
def test_refuses_a_command_it_cant_run_as_asked(
    pa_setup: ModuleType, ran: list[list[str]], command: str, args: list[str], message: str
) -> None:
    with pytest.raises(pa_setup.ConfigError) as error:
        pa_setup.engine_main(model(pa_setup, ENGINE), command, args)
    assert message in str(error.value)
    assert [call for call in ran if call != ASKED] == []


def test_the_engines_commands_need_an_engine(pa_setup: ModuleType, ran: list[list[str]]) -> None:
    with pytest.raises(pa_setup.ConfigError, match="add tm1Engine to the file"):
        pa_setup.engine_main(model(pa_setup, "tm1: {servers: {tm1: {}}}\n"), "credentials", [])
    assert ran == []


def test_credentials_are_as_seen_from_this_computer(
    pa_setup: ModuleType, ran: list[list[str]], capsys: pytest.CaptureFixture[str]
) -> None:
    # The engine reports its own port and paths, inside its container
    assert pa_setup.engine_main(model(pa_setup, ENGINE), "credentials", [], as_json=True) == 0
    printed = json.loads(capsys.readouterr().out)
    assert printed["port"] == 4500 and printed["url"] == "http://localhost:4500/"
    assert printed["root"]["file"] == "/host/project/pa-files/tm1-engine/credentials/root.json"
    assert printed["instances"][1]["applications"][0]["file"] == (
        "/host/project/pa-files/tm1-engine/credentials/other/tm1.json"
    )
    assert printed["instances"][0]["applications"][0]["clientSecret"] == "appsecret"

    assert pa_setup.engine_main(model(pa_setup, ENGINE), "credentials", []) == 0
    out = capsys.readouterr().out
    assert "http://localhost:4500/other/api/v1/Databases('extra')/" in out
    assert "secret in /host/project/pa-files/tm1-engine/credentials/tm1/tm1.json" in out
    # Without --json: where each secret is, never the secret
    assert "secret" in out and "appsecret" not in out and "rootsecret" not in out


def run_main(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, tmp_path: Path, *argv: str) -> int:
    monkeypatch.setattr(pa_setup, "WORK", str(tmp_path))
    monkeypatch.setattr(sys, "argv", ["setup", *argv])
    code = pa_setup.main()
    assert isinstance(code, int)
    return code


def test_the_command_line_reaches_the_engines_commands(
    pa_setup: ModuleType, ran: list[list[str]], monkeypatch: pytest.MonkeyPatch, tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    (tmp_path / "pa-environment.yaml").write_text("version: 1\n" + ENGINE)
    assert run_main(pa_setup, monkeypatch, tmp_path, "session", "sales", "analyst") == 0
    assert run_main(pa_setup, monkeypatch, tmp_path, "database", "create", "newone") == 0
    assert run_main(pa_setup, monkeypatch, tmp_path, "credentials", "--json") == 0
    assert ran == [ASKED, ["session", "other", "analyst"], ["database", "create", "tm1/newone"], ASKED]
    assert json.loads(capsys.readouterr().out)["port"] == 4500
    # What it can't run as asked is the file's kind of mistake: exit 2, and why
    assert run_main(pa_setup, monkeypatch, tmp_path, "database", "drop", "planning") == 2
    assert "one of the file's databases" in capsys.readouterr().err


def test_an_option_after_the_command_is_still_an_option(
    pa_setup: ModuleType, ran: list[list[str]], monkeypatch: pytest.MonkeyPatch, tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    # 'up -f other.yaml' must read other.yaml, not the default file with '-f' as a service
    (tmp_path / "pa-environment.yaml").write_text("version: 1\ntm1Engine: {port: 4444}\n")
    (tmp_path / "other.yaml").write_text("version: 1\ntm1Engine: {port: 4999}\n")
    assert run_main(pa_setup, monkeypatch, tmp_path, "urls", "-f", "other.yaml") == 0
    assert "http://localhost:4999/manage/v1/" in capsys.readouterr().out
    with pytest.raises(SystemExit):
        run_main(pa_setup, monkeypatch, tmp_path, "urls", "--bogus")
    with pytest.raises(SystemExit) as helped:
        run_main(pa_setup, monkeypatch, tmp_path, "down", "--help")
    assert helped.value.code == 0 and ran == []


@pytest.mark.parametrize(
    ("text", "message"),
    [
        ("tm1Engine: {users: abc}", "tm1Engine.users must be a list"),
        ("tm1Engine: {users: [{name: planner, groups: Planners}]}", "groups must be a list"),
        # The image's own form puts a group there; here it would be read as a password
        ("tm1Engine: {users: ['analyst:Analysts']}", "for a group, write analyst::Analysts"),
        ("tm1Engine: {users: [{name: planner, password: 123}]}", "password must be text"),
        ("tm1Engine: {databases: {2024: {}}}", "put it in quotes"),
        ("tm1Engine: {databases: {yes: {}}}", "put it in quotes"),
        ("tm1Engine: {databases: {planning: {instance: 0}}}", "instance names are letters"),
        ("tm1Engine: true\ntm1: {servers: {TM1-Engine: {}}}", "that name is the TM1 v12 engine's here"),
        ("tm1Engine: true\ntm1: {servers: {tm1-engine-mongo: {}}}", "that name is the TM1 v12 engine's here"),
    ],
)
def test_refuses_what_would_be_read_as_something_else(pa_setup: ModuleType, text: str, message: str) -> None:
    assert message in refused(pa_setup, text + "\n")


def test_a_server_may_have_the_engines_name_without_an_engine(pa_setup: ModuleType) -> None:
    assert "tm1-engine" in model(pa_setup, "tm1: {servers: {tm1-engine: {}}}\n")["tm1"]


@pytest.mark.parametrize("datastore", ["false", "true", "{}", "{port: }", "~"])
def test_the_datastore_is_published_only_with_a_port(pa_setup: ModuleType, datastore: str) -> None:
    engine = model(pa_setup, f"tm1Engine: {{datastore: {datastore}}}\n")["tm1Engine"]
    assert engine["datastorePort"] is None


def test_a_published_datastore_is_for_this_computer_only_and_says_how_to_log_in(
    pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    read = model(pa_setup, "tm1Engine: {datastore: {port: 27018}}\n")
    assert pa_setup.generate(read)["services"]["tm1-engine-mongo"]["ports"] == ["127.0.0.1:27018:27017"]
    pa_setup.urls(read)
    assert "mongodb://localhost:27018/   (tm1 / tm1-datastore)" in capsys.readouterr().out


def test_on_windows_mongodb_keeps_its_data_in_a_volume(pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch) -> None:
    # MongoDB's storage needs a Linux filesystem, which a Windows folder shared into Docker isn't
    monkeypatch.setattr(pa_setup, "HOST_DIR", "C:\\Users\\me\\project")
    generated = pa_setup.generate(model(pa_setup, "tm1Engine: true\n"))
    assert generated["services"]["tm1-engine-mongo"]["volumes"] == ["tm1-engine-datastore:/data/db"]
    assert "tm1-engine-datastore" in generated["volumes"]
    # The deployment and credentials stay in the folder, where they can be read
    assert "C:\\Users\\me\\project\\pa-files\\tm1-engine\\deployment:/tm1/deployment" in (
        generated["services"]["tm1-engine"]["volumes"]
    )


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
