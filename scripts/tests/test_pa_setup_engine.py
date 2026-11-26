# SPDX-License-Identifier: Apache-2.0

"""pa-setup's TM1 v12 engine (the tm1Engine section of pa-environment.yaml): what the file may say,
the compose services generated from it, what 'up' prints, and the engine's commands. The tool is
run for real, against TM1 v12, by hand: CI has no IBM software."""

from __future__ import annotations

import importlib.util
import json
import re
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
        "oauth2": None,
        "passthrough": None,
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
        # OAuth 2.0 is the oauth2 section, and pass-through the passthrough section
        ("tm1Engine: {login: oauth2}", "tm1Engine: unknown setting(s) login"),
        ("tm1Engine: {passthrough: {url: 'http://x'}}", "tm1Engine.passthrough: unknown setting(s) url"),
        ("tm1Engine: {passthrough: true}", "tm1Engine.passthrough: name the TM1 server that checks names and passwords"),
        ("tm1Engine: {passthrough: 1}", "tm1Engine.passthrough must be a mapping"),
        (
            "tm1: {servers: {legacy: {cfg: {UseSSL: T}}}}\ntm1Engine: {passthrough: {server: legacy}}",
            "legacy has cfg.UseSSL on, and the engine asks it over plain HTTP",
        ),
        (
            # The server's image takes the parameter's name in any case
            "tm1: {servers: {legacy: {cfg: {usessl: 'True'}}}}\ntm1Engine: {passthrough: {server: legacy}}",
            "legacy has cfg.UseSSL on",
        ),
        (
            "tm1: {servers: {legacy: {}}}\ntm1Engine: {users: ['analyst:apple'], passthrough: {server: legacy}}",
            "With passthrough the password is the one the user has on the TM1 server, and isn't written here: write analyst.",
        ),
        ("tm1Engine: {passthrough: {}}", "tm1Engine.passthrough.server: name one of tm1.servers"),
        ("tm1: {servers: {legacy: {}}}\ntm1Engine: {passthrough: {server: other}}", "name one of tm1.servers, whose users then log in to TM1 v12 with their name and password there (legacy)"),
        ("tm1: {servers: {legacy: {}}}\ntm1Engine: {passthrough: {server: Legacy}}", "tm1Engine.passthrough.server: name one of tm1.servers"),
        (
            "tm1: {servers: {legacy: {}}}\ntm1Engine: {users: ['a:pw:'], oauth2: true, passthrough: {server: legacy}}",
            "tm1Engine: oauth2 and passthrough can't both be on",
        ),
        ("tm1Engine: {oauth2: {port: 1}}", "tm1Engine.oauth2: unknown setting(s) port"),
        ("tm1Engine: {oauth2: true}", "tm1Engine.oauth2: needs a user with a password"),
        ("tm1Engine: {oauth2: true, users: [viewer, 'analyst::Analysts']}", "needs a user with a password"),
        ("tm1Engine: {oauth2: 0, users: ['a:pw']}", "tm1Engine.oauth2 must be a mapping"),
        ("tm1Engine: {oauth2: {keycloakPort: 4444}, users: ['a:pw']}", "port(s) 4444 used by more than one service"),
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


OAUTH = "tm1Engine: {port: 4500, users: ['analyst:an$pw:Analysts', viewer, 'boss:bosspw:ADMIN'], oauth2: OAUTH2}\n"


@pytest.mark.parametrize("on", ["true", "{}", "{keycloakPort: 8181}"])
def test_oauth2_adds_a_keycloak_of_the_engines_own(pa_setup: ModuleType, on: str) -> None:
    generated = pa_setup.generate(model(pa_setup, OAUTH.replace("OAUTH2", on)))
    keycloak, engine = generated["services"]["tm1-engine-keycloak"], generated["services"]["tm1-engine"]
    # Its hostname is its name on the network: a token names the issuer TM1 can reach,
    # whichever way it was fetched
    assert keycloak["environment"]["KC_HOSTNAME"] == "http://tm1-engine-keycloak:8080"
    assert keycloak["environment"]["KC_HOSTNAME_BACKCHANNEL_DYNAMIC"] == "true"
    assert keycloak["command"] == ["start-dev", "--import-realm"]
    # This computer only: the console's login isn't a secret
    assert keycloak["ports"] == ["127.0.0.1:8181:8080"]
    assert engine["environment"]["TM1_OAUTH2_ISSUER_URL"] == "http://tm1-engine-keycloak:8080/realms/tm1"
    assert engine["environment"]["TM1_OAUTH2_AUDIENCE"] == "tm1"
    assert engine["environment"]["TM1_OAUTH2_ID_CLAIMS"] == "preferred_username"
    assert engine["depends_on"]["tm1-engine-keycloak"] == {"condition": "service_healthy"}
    # TM1 still gets names and groups only
    assert engine["environment"]["TM1_USERS"] == "analyst:Analysts,viewer,boss:ADMIN"


def test_the_realm_has_the_audience_tm1_expects_and_the_users_with_a_password(pa_setup: ModuleType) -> None:
    generated = pa_setup.generate(model(pa_setup, OAUTH.replace("OAUTH2", "true")))
    content = generated["configs"]["tm1-engine-realm"]["content"]
    # compose would read a lone $ as a variable of its own
    assert "an$$pw" in content and "an$pw" not in content.replace("an$$pw", "")
    realm = json.loads(content.replace("$$", "$"))
    assert realm["realm"] == "tm1" and realm["enabled"] is True
    (client,) = realm["clients"]
    assert client["clientId"] == "tm1" and client["publicClient"] and client["directAccessGrantsEnabled"]
    (mapper,) = client["protocolMappers"]
    assert mapper["protocolMapper"] == "oidc-audience-mapper"
    assert mapper["config"]["included.custom.audience"] == "tm1" and mapper["config"]["access.token.claim"] == "true"
    # viewer has no password, so nothing to log in to Keycloak with
    assert [(u["username"], u["credentials"][0]["value"]) for u in realm["users"]] == [
        ("analyst", "an$pw"),
        ("boss", "bosspw"),
    ]
    # Not made from the name: Keycloak refuses some addresses a name would make
    assert [u["email"] for u in realm["users"]] == ["user1@example.com", "user2@example.com"]
    assert all(u["enabled"] and u["emailVerified"] and u["email"] for u in realm["users"])
    (mounted,) = generated["services"]["tm1-engine-keycloak"]["configs"]
    assert mounted == {"source": "tm1-engine-realm", "target": "/opt/keycloak/data/import/tm1-realm.json"}


@pytest.mark.parametrize("off", ["", ", oauth2: false", ", oauth2: null"])
def test_without_oauth2_the_engine_is_told_its_off(pa_setup: ModuleType, off: str) -> None:
    # The deployment is kept between runs, so leaving the setting out can't mean off
    generated = pa_setup.generate(model(pa_setup, f"tm1Engine: {{users: ['analyst:pw:']{off}}}\n"))
    assert generated["services"]["tm1-engine"]["environment"]["TM1_OAUTH2_ISSUER_URL"] == "none"
    assert "tm1-engine-keycloak" not in generated["services"] and "configs" not in generated
    assert "TM1_OAUTH2_AUDIENCE" not in generated["services"]["tm1-engine"]["environment"]


def test_with_oauth2_a_two_part_user_is_a_name_and_a_password(pa_setup: ModuleType) -> None:
    # Without OAuth 2.0 that form is refused: the image reads the second part as a group
    users = model(pa_setup, "tm1Engine: {users: ['analyst:analystpw'], oauth2: true}\n")["tm1Engine"]["users"]
    assert users == [{"name": "analyst", "password": "analystpw", "groups": []}]
    assert "for a group, write analyst::analystpw" in refused(pa_setup, "tm1Engine: {users: ['analyst:analystpw']}\n")


def test_prints_where_tokens_come_from_and_who_can_log_in(
    pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    pa_setup.urls(model(pa_setup, OAUTH.replace("OAUTH2", "{keycloakPort: 8191}")))
    out = capsys.readouterr().out
    # Who can log in, not their passwords
    assert "http://localhost:8191/realms/tm1   (client tm1; analyst, boss; passwords in pa-environment.yaml)" in out
    assert "an$pw" not in out and "bosspw" not in out
    assert "setup.sh token analyst" in out


def test_counts_the_keycloak_in_the_memory_it_needs(pa_setup: ModuleType) -> None:
    without = pa_setup.memory_needed(model(pa_setup, "tm1Engine: {users: ['a:pw:']}\n"))
    assert pa_setup.memory_needed(model(pa_setup, "tm1Engine: {users: ['a:pw'], oauth2: true}\n")) == pytest.approx(without + 0.7)


def test_token_asks_the_engines_keycloak_on_the_projects_network(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    asked: list[list[str]] = []

    def run(cmd: list[str], **kwargs: Any) -> Any:
        asked.append(cmd)
        return type("Done", (), {"returncode": 0, "stdout": json.dumps({"access_token": "the.token"}), "stderr": ""})()

    monkeypatch.setattr(pa_setup.subprocess, "run", run)
    read = model(pa_setup, "name: demo\n" + OAUTH.replace("OAUTH2", "true"))
    # Names are one whatever their case
    assert pa_setup.engine_main(read, "token", ["BOSS"]) == 0
    assert capsys.readouterr().out.strip() == "the.token"
    (cmd,) = asked
    # On the project's network, where Keycloak has the name its tokens carry
    assert cmd[cmd.index("--network") + 1] == "demo_default"
    assert cmd[-1] == "http://tm1-engine-keycloak:8080/realms/tm1/protocol/openid-connect/token"
    assert "username=boss" in cmd and "password=bosspw" in cmd and "client_id=tm1" in cmd
    # The first user with a password, when none is named
    assert pa_setup.engine_main(read, "token", []) == 0
    assert "username=analyst" in asked[-1] and "password=an$pw" in asked[-1]


@pytest.mark.parametrize(
    ("text", "args", "message"),
    [
        ("tm1Engine: {users: ['a:pw:']}", [], "add oauth2: true to tm1Engine"),
        ("tm1: {servers: {tm1: {}}}", [], "add oauth2: true to tm1Engine"),
        (OAUTH.replace("OAUTH2", "true"), ["viewer"], "viewer isn't one of tm1Engine.users with a password; they are: analyst, boss"),
        (OAUTH.replace("OAUTH2", "true"), ["a", "b"], "usage: token"),
    ],
)
def test_token_refuses_what_it_cant_give(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, text: str, args: list[str], message: str
) -> None:
    monkeypatch.setattr(pa_setup.subprocess, "run", lambda *a, **k: pytest.fail("nothing should run"))
    with pytest.raises(pa_setup.ConfigError) as error:
        pa_setup.engine_main(model(pa_setup, text.strip() + "\n"), "token", args)
    assert message in str(error.value)


@pytest.mark.parametrize(
    ("stdout", "stderr", "message"),
    [
        # Keycloak's own reason, when it answered
        ('{"error": "invalid_grant", "error_description": "Invalid user credentials"}', "", "Keycloak gave analyst no token: Invalid user credentials"),
        ("", "curl: (6) Could not resolve host: tm1-engine-keycloak", "Keycloak didn't answer; is the environment up? curl: (6)"),
        ("<html>", "", "Keycloak didn't answer"),
    ],
)
def test_token_says_why_there_is_none(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, stdout: str, stderr: str, message: str
) -> None:
    done = type("Done", (), {"returncode": 0, "stdout": stdout, "stderr": stderr})()
    monkeypatch.setattr(pa_setup.subprocess, "run", lambda *a, **k: done)
    with pytest.raises(pa_setup.ConfigError) as error:
        pa_setup.engine_main(model(pa_setup, OAUTH.replace("OAUTH2", "true")), "token", [])
    assert message in str(error.value)


def test_a_changed_user_or_password_makes_a_new_keycloak_and_engine(pa_setup: ModuleType) -> None:
    # Compose doesn't recreate a container for a config's new content, and Keycloak imports a realm once
    def marks(users: str) -> tuple[str, str]:
        services = pa_setup.generate(model(pa_setup, f"tm1Engine: {{users: {users}, oauth2: true}}\n"))["services"]
        return services["tm1-engine-keycloak"]["environment"]["PA_REALM"], services["tm1-engine"]["environment"]["PA_REALM"]

    first = marks("['analyst:pw1:Analysts']")
    assert first[0] == first[1] and first == marks("['analyst:pw1:Analysts']")
    assert marks("['analyst:pw2:Analysts']") != first
    assert marks("['analyst:pw1:Analysts', 'boss:bosspw:ADMIN']") != first
    # A user without a password isn't in the realm, and a group is TM1's
    assert marks("['analyst:pw1:Planners', viewer]") == first


def test_oauth2_false_keeps_the_passwords_with_it_off(pa_setup: ModuleType) -> None:
    read = model(pa_setup, "tm1Engine: {users: ['analyst:analystpw'], oauth2: false}\n")
    assert read["tm1Engine"]["oauth2"] is None
    assert read["tm1Engine"]["users"] == [{"name": "analyst", "password": "analystpw", "groups": []}]
    assert "oauth2: false keeps it, off" in refused(pa_setup, "tm1Engine: {users: ['analyst:analystpw']}\n")


def test_a_server_cant_take_the_keycloaks_name_with_oauth2(pa_setup: ModuleType) -> None:
    text = "tm1: {servers: {tm1-engine-keycloak: {}}}\ntm1Engine: {users: ['a:pw']OAUTH}\n"
    assert "that name is the TM1 v12 engine's here" in refused(pa_setup, text.replace("OAUTH", ", oauth2: true"))
    assert "tm1-engine-keycloak" in model(pa_setup, text.replace("OAUTH", ", oauth2: false"))["tm1"]


def test_a_dollar_in_a_cognos_password_reaches_cognos(pa_setup: ModuleType) -> None:
    generated = pa_setup.generate(model(pa_setup, "tm1: {servers: {tm1: {}}}\ncognos: {users: ['analyst:an$pw:Analysts']}\n"))
    # compose would read $pw as a variable of its own
    assert generated["services"]["cognos"]["environment"]["COGNOS_USERS"] == "analyst:an$$pw:Analysts"


def test_up_and_down_remove_what_was_taken_out_of_the_file(
    pa_setup: ModuleType, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    ran: list[list[str]] = []
    monkeypatch.setattr(pa_setup, "compose", lambda m, *args, check=True: ran.append(list(args)))
    monkeypatch.setattr(pa_setup, "check_memory", lambda m: None)
    (tmp_path / "pa-environment.yaml").write_text("version: 1\ntm1Engine: true\n")
    assert run_main(pa_setup, monkeypatch, tmp_path, "up") == 0
    assert run_main(pa_setup, monkeypatch, tmp_path, "down") == 0
    # Keycloak, say, once oauth2 is taken out: it mustn't keep running, or hold the network
    assert ran == [["up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "1800"], ["down", "--remove-orphans"]]


# The named server is the second: the first is what TM1 Web and PAW take, and mustn't be taken here
PASS = "tm1: {servers: {first: {}, Legacy: {seed: sdata, cfg: {UseSSL: F}}}}\ntm1Engine: {users: [analyst], passthrough: {server: Legacy}OAUTH}\n"


def test_passthrough_points_the_engine_at_the_tm1_11_server_and_waits_for_it(pa_setup: ModuleType) -> None:
    generated = pa_setup.generate(model(pa_setup, PASS.replace("OAUTH", "")))
    engine = generated["services"]["tm1-engine"]
    # The server's REST API as the engine reaches it: its service's name, and TM1's own port
    assert engine["environment"]["TM1_PASSTHROUGH_URL"] == "http://legacy:8010/api/v1/"
    assert engine["environment"]["TM1_OAUTH2_ISSUER_URL"] == "none"
    assert engine["depends_on"]["legacy"] == {"condition": "service_healthy"}
    assert "first" not in engine["depends_on"]
    # The image's defaults are a TM1 11 server's: nothing else to say
    assert not [name for name in engine["environment"] if name.startswith("TM1_PASSTHROUGH_") and name != "TM1_PASSTHROUGH_URL"]
    assert "tm1-engine-keycloak" not in generated["services"]


@pytest.mark.parametrize("off", ["", ", passthrough: false", ", passthrough: null"])
def test_without_passthrough_the_engine_is_told_its_off(pa_setup: ModuleType, off: str) -> None:
    # The deployment is kept between runs, so leaving the setting out can't mean off
    generated = pa_setup.generate(model(pa_setup, f"tm1: {{servers: {{legacy: {{}}}}}}\ntm1Engine: {{users: [analyst]{off}}}\n"))
    engine = generated["services"]["tm1-engine"]
    assert engine["environment"]["TM1_PASSTHROUGH_URL"] == "none"
    assert "legacy" not in engine["depends_on"]


def test_oauth2_false_beside_passthrough_is_fine(pa_setup: ModuleType) -> None:
    # Off is off: only the two both on are refused
    read = model(pa_setup, PASS.replace("OAUTH", ", oauth2: false"))
    assert read["tm1Engine"]["passthrough"] == {"server": "Legacy", "cam": False} and read["tm1Engine"]["oauth2"] is None


def test_prints_whose_users_log_in_and_how_to_try_it(pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]) -> None:
    pa_setup.urls(model(pa_setup, PASS.replace("OAUTH", "")))
    out = capsys.readouterr().out
    assert "users of TM1 Legacy log in with their name and password there" in out
    # The login the server's seed gives it
    assert "curl -u admin:apple \"http://localhost:4444/tm1/api/v1/Databases('tm1')/ActiveUser\"" in out
    pa_setup.urls(model(pa_setup, "tm1: {servers: {legacy: {}}}\ntm1Engine: {passthrough: {server: legacy}}\n"))
    out = capsys.readouterr().out
    assert "curl -u admin: " in out and "admin has no password on an empty TM1 database" in out


CAM = (
    "cognos: {users: ['admin:adminpw:Admins', 'analyst:analystpw:Analysts', 'viewer:viewerpw:Viewers']CAMSET}\n"
    "tm1: {servers: {legacy: {}}}\ntm1Engine: {users: [USERS], passthrough: {server: legacy}}\n"
)


def cam(pa_setup: ModuleType, users: str = "'analyst::Analysts'", cognos: str = "") -> dict[str, Any]:
    return model(pa_setup, CAM.replace("USERS", users).replace("CAMSET", cognos))


def test_passthrough_to_a_server_in_cam_security_names_the_engines_users_by_their_camids(pa_setup: ModuleType) -> None:
    # The TM1 server answers a login with the user's CAMID: the engine is told the namespace, and
    # makes its users under theirs. The TM1 servers' administrators are administrators here too
    environment = pa_setup.generate(cam(pa_setup))["services"]["tm1-engine"]["environment"]
    assert environment["TM1_CAM_NAMESPACE"] == "LDAP" and environment["TM1_CAM_NAMESPACE_TYPE"] == "ldap"
    assert environment["TM1_USERS"] == "analyst:Analysts,admin:ADMIN"
    assert environment["TM1_PASSTHROUGH_URL"] == "http://legacy:8010/api/v1/"


def test_the_oidc_namespace_and_its_administrators_are_passed_on(pa_setup: ModuleType) -> None:
    read = cam(pa_setup, "'analyst::Analysts', 'Viewer::Readers,admin', admin", ", oidc: true, cam: {namespace: oidc, admins: [viewer, admin]}")
    environment = pa_setup.generate(read)["services"]["tm1-engine"]["environment"]
    assert environment["TM1_CAM_NAMESPACE"] == "OIDC" and environment["TM1_CAM_NAMESPACE_TYPE"] == "oidc"
    # An administrator listed keeps its groups and gets ADMIN, once, whatever the case; and is
    # named as Cognos spells it, since the name goes into the CAMID
    assert environment["TM1_USERS"] == "analyst:Analysts,viewer:Readers:admin,admin:ADMIN"


def test_without_passthrough_cognos_changes_nothing_for_the_engine(pa_setup: ModuleType) -> None:
    text = "cognos: {users: ['admin:adminpw:Admins']}\ntm1: {servers: {legacy: {}}}\ntm1Engine: {users: [other]}\n"
    environment = pa_setup.generate(model(pa_setup, text))["services"]["tm1-engine"]["environment"]
    assert "TM1_CAM_NAMESPACE" not in environment and "TM1_CAM_NAMESPACE_TYPE" not in environment
    assert environment["TM1_USERS"] == "other"


NATIVE = (
    "cognos: {users: ['admin:adminpw:Admins']}\n"
    "tm1: {servers: {legacy: {seed: sdata, cfg: {integratedsecuritymode: 1}}}}\n"
    "tm1Engine: {users: [planner], passthrough: {server: legacy}}\n"
)


def test_a_server_whose_cfg_takes_it_out_of_cam_security_is_one_with_users_of_its_own(
    pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    # The image applies cfg last, in any case of the name: that server answers plain names to Basic
    read = model(pa_setup, NATIVE)
    environment = pa_setup.generate(read)["services"]["tm1-engine"]["environment"]
    assert "TM1_CAM_NAMESPACE" not in environment and environment["TM1_USERS"] == "planner"
    pa_setup.urls(read)
    out = capsys.readouterr().out
    assert "curl -u admin:apple" in out and "CAMNamespace" not in out


@pytest.mark.parametrize("mode", ["4", "5", "' 5 '"])
def test_a_cfg_that_names_a_cam_mode_keeps_the_server_in_cam_security(pa_setup: ModuleType, mode: str) -> None:
    read = model(pa_setup, NATIVE.replace("integratedsecuritymode: 1", f"IntegratedSecurityMode: {mode}").replace("[planner]", "[]"))
    assert pa_setup.generate(read)["services"]["tm1-engine"]["environment"]["TM1_CAM_NAMESPACE"] == "LDAP"


def test_a_user_cognos_doesnt_have_is_refused_with_passthrough_to_cam_security(pa_setup: ModuleType) -> None:
    with pytest.raises(pa_setup.ConfigError, match=re.escape("tm1Engine.users: stranger, other isn't in cognos.users")):
        cam(pa_setup, "analyst, stranger, other")


def test_says_how_a_cognos_user_logs_in_through_a_server_in_cam_security(
    pa_setup: ModuleType, capsys: pytest.CaptureFixture[str]
) -> None:
    # This row said "NOT TRIED", on the belief that TM1 v12 refused the CAMNamespace header. It
    # passes it on: tried with both namespaces
    pa_setup.urls(cam(pa_setup))
    out = capsys.readouterr().out
    assert "NOT TRIED" not in out and "curl -u" not in out
    assert "Cognos users log in as they do to TM1 legacy (CAMNamespace or CAMPassport)   (admin, analyst)" in out
    # Without the newline base64 puts in a long one
    assert "curl -H \"Authorization: CAMNamespace $(printf %s admin:PASSWORD:LDAP | base64 | tr -d '\\n')\"" in out
    assert "as admin, or a Cognos user by its CAMID" in out
    # One user written in two cases is listed once
    pa_setup.urls(cam(pa_setup, "'Analyst::Analysts', ADMIN"))
    assert "(CAMNamespace or CAMPassport)   (admin, analyst)" in capsys.readouterr().out


def test_the_schema_takes_a_server_or_false_for_passthrough_as_the_tool_does(pa_setup: ModuleType) -> None:
    described = json.loads((SETUP / "pa-environment.schema.json").read_text(encoding="utf-8"))
    off, on = described["properties"]["tm1Engine"]["properties"]["passthrough"]["oneOf"]
    # true and {} are refused by the tool: an editor checking the file must say so too
    assert off == {"const": False}
    assert on["required"] == ["server"] and on["type"] == "object"


def test_the_example_is_valid_and_fits_the_schema_it_documents(pa_setup: ModuleType) -> None:
    example = yaml.safe_load((SETUP / "examples" / "tm1-engine.yaml").read_text(encoding="utf-8"))
    engine = pa_setup.normalize(example)["tm1Engine"]
    assert list(engine["databases"]) == ["planning", "sales"]
    schema = json.loads((SETUP / "pa-environment.schema.json").read_text(encoding="utf-8"))
    described = schema["properties"]["tm1Engine"]["properties"]
    # Every setting the tool takes is in the schema, and nothing else
    assert sorted(described) == ["databases", "datastore", "oauth2", "passthrough", "port", "users"]
    assert set(example["tm1Engine"]) <= set(described)
    assert {"tm1Engine", "mongo"} <= set(schema["properties"]["images"]["properties"])
