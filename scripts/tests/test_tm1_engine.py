# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-engine's setup tool (docker/engine.py), which runs inside the image: what it works out
from the environment, what it writes to TM1's configuration, and what it creates, against a
stand-in for TM1's gateway (CI has no IBM software). The image itself is tested by
docker/tm1-engine/ci-smoke-test.sh, and against real TM1 by hand."""

from __future__ import annotations

import base64
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

import pytest
from conftest import load

engine = load("docker/tm1-engine/docker/engine.py")


# ---- what to create -------------------------------------------------------------------


def test_plan_defaults_to_one_database_and_application() -> None:
    assert engine.plan({}) == {"instances": {"tm1": {"applications": ["tm1"], "databases": ["tm1"]}}, "users": []}


def test_plan_reads_databases_applications_and_users() -> None:
    plan = engine.plan({"TM1_DATABASES": "planning, finance/sales", "TM1_APPLICATIONS": "paw,finance/reports", "TM1_USERS": "analyst:Analysts:Readers, viewer"})
    assert plan["instances"] == {
        "tm1": {"applications": ["paw"], "databases": ["planning"]},
        "finance": {"applications": ["reports"], "databases": ["sales"]},
    }
    assert plan["users"] == [{"name": "analyst", "groups": ["Analysts", "Readers"]}, {"name": "viewer", "groups": []}]


def test_plan_gives_each_instance_an_application_by_default() -> None:
    plan = engine.plan({"TM1_DATABASES": "planning,finance/sales"})
    assert [entry["applications"] for entry in plan["instances"].values()] == [["tm1"], ["tm1"]]


def test_plan_with_no_databases_still_has_the_instance_and_its_application() -> None:
    assert engine.plan({"TM1_DATABASES": ""}) == {"instances": {"tm1": {"applications": ["tm1"], "databases": []}}, "users": []}


def test_plan_takes_names_as_tm1_does_and_an_empty_application_list_as_unset() -> None:
    # TM1's database names are case-sensitive; compose passes a variable that isn't set as empty
    plan = engine.plan({"TM1_DATABASES": "sales,Sales", "TM1_APPLICATIONS": " "})
    assert plan["instances"] == {"tm1": {"applications": ["tm1"], "databases": ["sales", "Sales"]}}


@pytest.mark.parametrize(
    ("env", "message"),
    [
        ({"TM1_DATABASES": "ab"}, "at least 3 characters"),
        ({"TM1_DATABASES": "sales,sales"}, "listed twice"),
        ({"TM1_DATABASES": "my db"}, "names are letters"),
        ({"TM1_DATABASES": "a/b/sales"}, "names are letters"),
        ({"TM1_USERS": "analyst:"}, "USER[:GROUP[:GROUP]]"),
        ({"TM1_USERS": "analyst,Analyst"}, "listed twice"),
    ],
)
def test_plan_refuses(env: dict[str, str], message: str) -> None:
    with pytest.raises(engine.Failure, match=re.escape(message)):
        engine.plan(env)


# ---- configuration ----------------------------------------------------------------------


def template() -> dict:
    return {
        "datastore": {"external": False, "protocol": "mongodb", "host": "127.0.0.1:27027", "authMechanism": "SCRAM-SHA-256", "authSource": "admin", "user": "", "pass": "", "replicaSet": "", "controlDatabase": "tm1-control-default", "tlsPath": ""},
        "services": {"tm1-gateway": {"host": "127.0.0.1", "port": "10000", "serverPort": "9999"}},
    }


def test_datastore_url_becomes_the_datastore_section() -> None:
    assert engine.parse_datastore_url("mongodb://root:p%40ss@a:27017,b:27017/?replicaSet=rs0&authSource=users&tls=true&tlsCAFile=/certs/ca.pem") == {
        "external": True, "protocol": "mongodb", "host": "a:27017,b:27017", "user": "root", "pass": "p%40ss",
        "authMechanism": "SCRAM-SHA-256", "authSource": "users", "replicaSet": "rs0", "tlsPath": "/certs/ca.pem",
    }
    assert engine.parse_datastore_url("mongodb+srv://cluster.example")["protocol"] == "mongodb+srv"
    assert engine.parse_datastore_url("mongodb://mongo:27017")["user"] == ""


@pytest.mark.parametrize("url", ["mongo:27017", "http://mongo:27017", "mongodb://user:pw@/"])
def test_datastore_url_refuses(url: str) -> None:
    with pytest.raises(engine.Failure):
        engine.parse_datastore_url(url)


def test_configure_fixes_the_port_and_leaves_tm1s_own_mongodb_alone() -> None:
    config = template()
    changes = engine.configure(config, {})
    assert config["services"]["tm1-gateway"]["serverPort"] == "4444"
    assert config["datastore"] == template()["datastore"]
    assert changes == ["services.tm1-gateway.serverPort: '9999' -> '4444'"]


def test_configure_points_tm1_at_a_mongodb_with_a_control_database_of_its_own() -> None:
    config = template()
    changes = engine.configure(config, {"TM1_DATASTORE_URL": "mongodb://root:secret@mongo:27017"})
    datastore = config["datastore"]
    assert (datastore["external"], datastore["host"], datastore["user"], datastore["pass"]) == (True, "mongo:27017", "root", "secret")
    assert re.fullmatch(r"tm1-control-[0-9a-f-]{36}", datastore["controlDatabase"])
    assert "secret" not in " ".join(changes)  # the password isn't logged

    # A restart keeps the control database: a new one would lose every instance
    before = datastore["controlDatabase"]
    assert engine.configure(config, {"TM1_DATASTORE_URL": "mongodb://root:secret@mongo:27017"}) == []
    assert config["datastore"]["controlDatabase"] == before

    # A changed password is applied, and neither the old nor the new one is logged
    changes = engine.configure(config, {"TM1_DATASTORE_URL": "mongodb://root:rotated@mongo:27017"})
    assert config["datastore"]["pass"] == "rotated"
    assert changes == ["datastore.pass: (changed)"]


# ---- setup, against a stand-in for TM1 --------------------------------------------------------


class FakeTm1:
    """Enough of TM1's gateway for setup: the management API, service-to-service logins, and each
    database's users and groups. Replies as TM1 12.6.7 was seen to."""

    def __init__(self) -> None:
        self.root = ("root-id", "root-secret")
        self.instances: dict[str, dict] = {}
        self.secrets = 0
        self.requests: list[tuple[str, str]] = []

    def application(self, instance: str, name: str) -> dict:
        self.secrets += 1
        app = {"Name": name, "ClientID": f"id-{self.secrets}", "ClientSecret": f"secret-{self.secrets}"}
        self.instances[instance]["applications"][name] = app
        return app

    def database(self, instance: str, name: str) -> dict:
        database = {"Name": name, "Replicas": 1, "users": {"Admin": ["ADMIN"]}, "groups": ["ADMIN", "DataAdmin"]}
        self.instances[instance]["databases"][name] = database
        return database

    def handle(self, method: str, path: str, auth: str | None, cookie: str | None, body: dict | None) -> tuple[int, object, dict]:
        self.requests.append((method, path))
        basic = tuple(base64.b64decode(auth.split()[1]).decode().split(":", 1)) if auth else None
        key = r"\('([^']*)'\)"
        if path.startswith("/manage/v1/"):
            if basic != self.root:
                return 401, {"error": {"message": "Authentication required"}}, {}
            rest = path[len("/manage/v1/") :]
            if rest == "Instances":
                if method == "GET":
                    return 200, {"value": [{"Name": name} for name in self.instances]}, {}
                if body["Name"] in self.instances:
                    return 400, {"error": {"message": "exists"}}, {}
                self.instances[body["Name"]] = {"applications": {}, "databases": {}}
                return 201, {"Name": body["Name"]}, {}
            match = re.fullmatch(rf"Instances{key}/(Applications|Databases)(?:{key})?", rest)
            assert match, rest
            instance, kind, name = self.instances[match[1]], match[2].lower(), match[3]
            if kind == "applications":
                if method == "GET":
                    return 200, {"value": [{"Name": app["Name"], "ClientID": app["ClientID"]} for app in instance[kind].values()]}, {}
                if method == "DELETE":
                    del instance[kind][name]
                    return 204, None, {}
                return 201, self.application(match[1], body["Name"]), {}
            if method == "POST":
                if body["Name"] in instance[kind]:
                    return 400, {"error": {"message": f"A database named {body['Name']} already exists."}}, {}
                self.database(match[1], body["Name"])
                return 201, {"Name": body["Name"]}, {}
            if name is None:
                return 200, {"value": [self.shown(database) for database in instance[kind].values()]}, {}
            if method == "DELETE":
                del instance[kind][name]
                return 204, None, {}
            if method == "PATCH":
                instance[kind][name]["Replicas"] = body["Replicas"]
            return 200, self.shown(instance[kind][name]), {}
        match = re.fullmatch(r"/([^/]+)/auth/v1/session", path)
        if match:
            apps = self.instances.get(match[1], {"applications": {}})["applications"].values()
            if not any(basic == (app["ClientID"], app["ClientSecret"]) for app in apps):
                return 401, {"error": "invalid_client"}, {}
            return 201, None, {"Set-Cookie": f"TM1SessionId={match[1]}:{body['User']}; Path=/; HttpOnly"}
        match = re.fullmatch(rf"/([^/]+)/api/v1/Databases{key}/(Users|Groups)(?:{key})?(?:\?.*)?", path)
        assert match, path
        if cookie != f"TM1SessionId={match[1]}:admin":
            return 401, {"error": {"message": "Authentication required"}}, {}
        database = self.instances[match[1]]["databases"][match[2]]
        if match[3] == "Groups":
            if method == "POST":
                database["groups"].append(body["Name"])
                return 201, {"Name": body["Name"]}, {}
            return 200, {"value": [{"Name": group} for group in database["groups"]]}, {}
        if method == "GET":
            return 200, {"value": [{"Name": user, "Groups": [{"Name": group} for group in groups]} for user, groups in database["users"].items()]}, {}
        groups = [re.fullmatch(rf"Groups{key}", bound)[1] for bound in body.get("Groups@odata.bind", [])]
        assert all(group in database["groups"] for group in groups), groups
        if method == "POST":
            database["users"][body["Name"]] = groups
            return 201, {"Name": body["Name"]}, {}
        database["users"][match[4]] = groups  # PATCH replaces the groups, as TM1 does
        return 200, {"Name": match[4]}, {}

    @staticmethod
    def shown(database: dict) -> dict:
        replicas = [{"ID": "0", "State": "ready"}] if database["Replicas"] else []
        return {"Name": database["Name"], "Replicas": database["Replicas"], "ActiveReplicas": replicas}


@pytest.fixture
def tm1(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    fake = FakeTm1()

    class Handler(BaseHTTPRequestHandler):
        def respond(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            status, payload, headers = fake.handle(self.command, unquote(self.path), self.headers.get("Authorization"), self.headers.get("Cookie"), body)
            raw = b"" if payload is None else json.dumps(payload).encode()
            self.send_response(status)
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = do_PATCH = do_DELETE = respond

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    deployment, credentials = tmp_path / "deployment", tmp_path / "credentials"
    deployment.mkdir()
    (deployment / "root-creds.json").write_text(json.dumps({"name": "Root", "clientID": fake.root[0], "clientSecret": fake.root[1]}))
    monkeypatch.setattr(engine, "BASE", f"http://127.0.0.1:{server.server_address[1]}")
    monkeypatch.setattr(engine, "DEPLOYMENT", str(deployment))
    monkeypatch.setattr(engine, "CREDENTIALS", str(credentials))
    monkeypatch.setattr(engine, "READY", str(tmp_path / "ready.json"))
    monkeypatch.setattr(engine, "STOPPED", str(deployment / ".tm1-engine-stopped.json"))
    monkeypatch.setattr(engine, "running_databases", list)
    for name in ("TM1_DATABASES", "TM1_APPLICATIONS", "TM1_USERS"):
        monkeypatch.delenv(name, raising=False)
    fake.credentials = credentials
    yield fake
    server.shutdown()


def test_setup_creates_what_was_asked_for(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TM1_DATABASES", "planning,finance/sales")
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts,viewer")
    engine.setup()

    assert set(tm1.instances) == {"tm1", "finance"}
    planning = tm1.instances["tm1"]["databases"]["planning"]
    assert planning["users"] == {"Admin": ["ADMIN"], "analyst": ["Analysts"], "viewer": []}
    assert "Analysts" in planning["groups"]
    assert tm1.instances["finance"]["databases"]["sales"]["users"]["analyst"] == ["Analysts"]

    saved = json.loads((tm1.credentials / "tm1" / "tm1.json").read_text())
    app = tm1.instances["tm1"]["applications"]["tm1"]
    assert saved == {"instance": "tm1", "name": "tm1", "clientId": app["ClientID"], "clientSecret": app["ClientSecret"]}
    assert (tm1.credentials / "tm1" / "tm1.json").stat().st_mode & 0o077 == 0
    assert json.loads((tm1.credentials / "root.json").read_text()) == {"clientId": "root-id", "clientSecret": "root-secret"}
    assert engine.command_health([]) == 0


def test_setup_again_changes_nothing(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts")
    engine.setup()
    secret = tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"]
    tm1.requests.clear()
    engine.setup()
    assert [request for request in tm1.requests if request[0] != "GET" and "/auth/" not in request[1]] == []
    assert tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"] == secret


def test_setup_puts_a_user_back_in_the_groups_asked_for(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts,viewer")
    engine.setup()
    users = tm1.instances["tm1"]["databases"]["tm1"]["users"]
    users["analyst"] = ["ADMIN"]
    users["viewer"] = ["ADMIN"]
    engine.setup()
    assert users["analyst"] == ["Analysts"]
    assert users["viewer"] == ["ADMIN"]  # no groups were asked for: left as it is


def test_setup_replaces_an_application_whose_secret_is_lost(tm1: FakeTm1) -> None:
    engine.setup()
    before = tm1.instances["tm1"]["applications"]["tm1"]["ClientID"]
    (tm1.credentials / "tm1" / "tm1.json").unlink()
    engine.setup()
    after = tm1.instances["tm1"]["applications"]["tm1"]
    assert after["ClientID"] != before
    assert json.loads((tm1.credentials / "tm1" / "tm1.json").read_text())["clientSecret"] == after["ClientSecret"]


def test_setup_keeps_the_administrator_an_administrator(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TM1_USERS", "admin:Analysts")
    engine.setup()
    assert sorted(tm1.instances["tm1"]["databases"]["tm1"]["users"]["Admin"]) == ["ADMIN", "Analysts"]


def test_stopping_scales_every_database_down_and_setup_brings_them_back(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    engine.setup()
    engine.command_database(["create", "scratch"])  # not one setup was asked for
    assert engine.command_stop_databases(["5"]) == 0
    databases = tm1.instances["tm1"]["databases"]
    assert {name: database["Replicas"] for name, database in databases.items()} == {"tm1": 0, "scratch": 0}

    # A second stop before anything came back (a failed start, say) keeps the first one's list
    assert engine.command_stop_databases(["5"]) == 0
    assert sorted(json.loads(Path(engine.STOPPED).read_text())) == [["tm1", "scratch"], ["tm1", "tm1"]]

    engine.setup()
    assert {name: database["Replicas"] for name, database in databases.items()} == {"tm1": 1, "scratch": 1}
    assert not Path(engine.STOPPED).exists()


def test_timeouts_that_arent_numbers_fall_back_with_a_warning(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setenv("TM1_ENGINE_START_TIMEOUT", "5m")
    engine.setup()
    assert "TM1_ENGINE_START_TIMEOUT='5m' isn't a whole number of seconds; using 300" in capsys.readouterr().err
    assert engine.command_stop_databases(["60s"]) == 0  # the databases are still stopped through TM1
    assert tm1.instances["tm1"]["databases"]["tm1"]["Replicas"] == 0


def test_setup_failure_says_what_tm1_logged(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    logs = Path(engine.DEPLOYMENT) / "logs"
    logs.mkdir()
    lines = [
        {"level": "info", "message": "Starting"},
        {"level": "error", "message": "Failed to connect to the datastore, aborting...", "error": "Authentication failed."},
        {"level": "error", "message": "Failed to connect to the datastore, aborting...", "error": "Authentication failed."},
    ]
    (logs / "tm1-gateway.jsonl").write_text("".join(json.dumps(line) + "\n" for line in lines) + "not json\n")
    monkeypatch.setattr(engine, "BASE", "http://127.0.0.1:9")  # nothing listens there
    with pytest.raises(engine.Failure) as failure:
        engine.wait_for_gateway(1)
    text = str(failure.value)
    assert "didn't answer on port 4444 within 1s" in text
    assert text.count("TM1's log: Failed to connect to the datastore, aborting... Authentication failed.") == 1


def test_stopping_when_tm1_isnt_answering_stops_nothing(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(engine, "BASE", "http://127.0.0.1:9")
    assert engine.command_stop_databases(["1"]) == 1
    assert not Path(engine.STOPPED).exists()


def test_health_waits_for_setup_and_for_every_database(tm1: FakeTm1) -> None:
    assert engine.command_health([]) == 1  # setup hasn't run
    engine.setup()
    assert engine.command_health([]) == 0
    tm1.instances["tm1"]["databases"]["tm1"]["Replicas"] = 0
    assert engine.command_health([]) == 1


def test_database_command_creates_with_the_users_and_drops(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts")
    engine.setup()
    capsys.readouterr()
    engine.command_database(["create", "scratch"])
    assert capsys.readouterr().out.strip() == "/tm1/api/v1/Databases('scratch')/"
    assert tm1.instances["tm1"]["databases"]["scratch"]["users"]["analyst"] == ["Analysts"]
    engine.command_database(["drop", "tm1"])
    assert "tm1" not in tm1.instances["tm1"]["databases"]
    assert engine.command_health([]) == 0  # health no longer waits for the dropped database


def test_session_logs_in_through_the_saved_application(tm1: FakeTm1, capsys: pytest.CaptureFixture[str]) -> None:
    engine.setup()
    capsys.readouterr()
    engine.command_session(["tm1", "analyst"])
    assert capsys.readouterr().out.strip() == "TM1SessionId=tm1:analyst"
    with pytest.raises(engine.Failure, match="no saved application credentials"):
        engine.session("other", "admin")


def test_session_skips_a_saved_application_tm1_no_longer_has(tm1: FakeTm1) -> None:
    engine.setup()
    (tm1.credentials / "tm1" / "aaa.json").write_text(json.dumps({"instance": "tm1", "name": "aaa", "clientId": "gone", "clientSecret": "gone"}))
    assert engine.session("tm1", "admin") == "TM1SessionId=tm1:admin"
    (tm1.credentials / "tm1" / "tm1.json").unlink()
    with pytest.raises(engine.Failure, match="logging in to instance 'tm1' as 'admin': 401"):
        engine.session("tm1", "admin")


def test_database_create_without_an_application_warns_and_still_creates(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst")
    engine.setup()
    engine.manage("POST", "Instances", {"Name": "bare"})
    capsys.readouterr()
    engine.command_database(["create", "bare/scratch"])
    captured = capsys.readouterr()
    assert captured.out.strip() == "/bare/api/v1/Databases('scratch')/"
    assert "no application in instance bare" in captured.err


def test_credentials_lists_everything_and_only_secrets_it_holds(tm1: FakeTm1, capsys: pytest.CaptureFixture[str]) -> None:
    engine.setup()
    tm1.application("tm1", "elsewhere")  # made outside this container: its secret isn't known here
    capsys.readouterr()
    engine.command_credentials(["--json"])
    shown = json.loads(capsys.readouterr().out)
    assert shown["port"] == 4444
    assert shown["root"] == {"clientId": "root-id", "clientSecret": "root-secret"}
    apps = {app["name"]: app for app in shown["instances"][0]["applications"]}
    assert apps["tm1"]["clientSecret"] == tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"]
    assert apps["elsewhere"]["clientSecret"] is None
    assert shown["instances"][0]["databases"] == [{"name": "tm1", "path": "/tm1/api/v1/Databases('tm1')/"}]
