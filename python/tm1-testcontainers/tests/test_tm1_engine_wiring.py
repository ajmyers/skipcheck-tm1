# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineWiringTest. Wiring only: nothing here starts a container."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import KeycloakContainer, Network, RequiresTm1Engine, Tm1EngineContainer, Tm1EngineSource, plugin
from skipcheck_tm1_testcontainers.engine import Tm1Login, database_name

SOURCE = Tm1EngineSource.PrivateImage("tm1-engine:test")


def binds(engine: Tm1EngineContainer) -> list[str]:
    """What the source has mounted, as Docker writes a bind."""
    return [f"{host}:{mount['bind']}:{mount['mode']}" for host, mount in engine.volumes.items()]


def test_has_one_database_unless_some_are_named() -> None:
    engine = Tm1EngineContainer(SOURCE)
    engine.configure()
    assert engine.env_map["TM1_DATABASES"] == "tm1/tm1"
    assert "TM1_USERS" not in engine.env_map
    assert engine.database("tm1").instance == "tm1"

    named = Tm1EngineContainer(SOURCE).with_database("planning").with_database("other", "sales")
    named.configure()
    assert named.env_map["TM1_DATABASES"] == "tm1/planning,other/sales"
    assert named.database("other", "sales").name == "sales"
    with pytest.raises(ValueError, match="No database tm1/tm1 was added"):
        named.database("tm1")


def test_passes_the_users_and_their_groups_to_the_image() -> None:
    engine = Tm1EngineContainer(SOURCE).with_user("analyst", "Analysts", "Planners").with_user("viewer")
    engine.configure()
    assert engine.env_map["TM1_USERS"] == "analyst:Analysts:Planners,viewer"


def test_refuses_names_the_image_cant_take() -> None:
    engine = Tm1EngineContainer(SOURCE)
    # TM1 wants at least three characters
    with pytest.raises(ValueError):
        engine.with_database("ab")
    with pytest.raises(ValueError):
        engine.with_database("two words")
    with pytest.raises(ValueError):
        engine.with_database("a/b", "sales")
    with pytest.raises(ValueError):
        engine.with_user("two words")
    with pytest.raises(ValueError):
        engine.with_user("analyst", "a:b")
    engine.with_user("analyst")
    with pytest.raises(ValueError, match="already"):
        engine.with_user("analyst", "Again")


def test_runs_its_datastore_on_its_own_network() -> None:
    engine = Tm1EngineContainer(SOURCE)
    engine.configure()
    datastore = engine.datastore
    assert datastore.image == "mongo:8.0"
    assert engine.network is not None
    assert engine.network is datastore.network
    assert Tm1EngineContainer.DATASTORE_ALIAS in datastore.network_aliases
    assert datastore in engine.dependencies
    assert engine.env_map["TM1_DATASTORE_URL"].startswith("mongodb://")
    assert engine.env_map["TM1_DATASTORE_URL"].endswith("@tm1-datastore:27017")
    # Both are this library's, like every container it starts
    assert engine._labels["io.skipcheck.tm1.testcontainers"] == "python"
    assert datastore._labels["io.skipcheck.tm1.testcontainers"] == "python"


def test_takes_o_auth2_tokens_from_a_keycloak_on_its_network() -> None:
    keycloak = KeycloakContainer()
    engine = Tm1EngineContainer(SOURCE).with_oauth2(keycloak)
    # Keycloak's address on the network is what its tokens name, and what TM1 can reach
    assert engine.env_map["TM1_OAUTH2_ISSUER_URL"] == "http://keycloak:8080/realms/tm1"
    assert engine.env_map["TM1_OAUTH2_AUDIENCE"] == "tm1"
    assert engine.env_map["TM1_OAUTH2_ID_CLAIMS"] == "preferred_username"
    assert keycloak.network is not None
    assert keycloak.network is engine.network
    assert keycloak.network is engine.datastore.network
    assert keycloak in engine.dependencies and engine.datastore in engine.dependencies
    assert engine.keycloak is keycloak
    assert Tm1EngineContainer(SOURCE).keycloak is None

    # Moved off the Keycloak's network, TM1 couldn't reach its issuer
    with pytest.raises(RuntimeError, match="set the network before with_oauth2"):
        engine.with_network(Network.new_network())
    assert engine.network is keycloak.network and engine.datastore.network is keycloak.network
    assert engine.with_network(keycloak.network) is engine

    # A Keycloak that has a network already is joined there, the datastore too
    network = Network.new_network()
    placed = KeycloakContainer().with_network(network)
    joining = Tm1EngineContainer(SOURCE).with_oauth2(placed)
    assert joining.network is network
    assert joining.datastore.network is network


def test_gives_keycloak_a_realm_with_the_users_who_have_a_password() -> None:
    keycloak = KeycloakContainer()
    engine = (
        Tm1EngineContainer(SOURCE)
        .with_oauth2(keycloak)
        # After with_oauth2: the realm is asked for when Keycloak starts
        .with_user("analyst", "Analysts")
        .with_user("viewer")
        .with_login("analyst", "an$pw")
        .with_login("Admin", "adminpw")
    )
    realm = json.loads(engine._realm())
    assert realm["realm"] == "tm1"
    client = realm["clients"][0]
    assert client["clientId"] == "tm1"
    assert client["publicClient"] is True
    assert client["directAccessGrantsEnabled"] is True
    mapper = client["protocolMappers"][0]
    assert mapper["protocolMapper"] == "oidc-audience-mapper"
    assert mapper["config"]["included.custom.audience"] == "tm1"
    # viewer has no password, so nothing to log in to Keycloak with; admin is always a user
    assert len(realm["users"]) == 2
    assert realm["users"][0]["username"] == "analyst"
    assert realm["users"][0]["credentials"][0]["value"] == "an$pw"
    assert realm["users"][0]["email"] == "user1@example.com"
    assert realm["users"][1]["username"] == "Admin"
    # TM1 gets names and groups only
    engine.configure()
    assert engine.env_map["TM1_USERS"] == "analyst:Analysts,viewer"


def test_refuses_an_o_auth2_login_it_cant_give() -> None:
    engine = Tm1EngineContainer(SOURCE).with_user("analyst").with_login("analyst", "pw")
    database = engine.database("tm1")
    with pytest.raises(RuntimeError, match="give it a Keycloak with with_oauth2"):
        database.oauth2_login("analyst")
    engine.with_oauth2(KeycloakContainer())
    with pytest.raises(ValueError, match="viewer has no password to log in with"):
        database.oauth2_login("viewer")
    # A name is one whatever its case: to log in as, and a second password replaces the first
    assert database.oauth2_login("ANALYST").user == "analyst"
    engine.with_login("Analyst", "second")
    assert database.oauth2_login("analyst").password == "second"
    assert len(json.loads(engine._realm())["users"]) == 1
    engine.with_login("analyst", "pw")
    assert database.oauth2_login("analyst").is_oauth2
    assert database.oauth2_login("analyst").password == "pw"
    assert database.oauth2_login("analyst").client_id == "tm1"
    with pytest.raises(ValueError):
        engine.with_login("analyst", "")
    with pytest.raises(ValueError):
        engine.with_login("two words", "pw")
    # A password for someone who isn't a user is a mistake, found when the realm is made
    stray = Tm1EngineContainer(SOURCE).with_login("nobody", "pw")
    with pytest.raises(RuntimeError, match="isn't a user of the engine"):
        stray._realm()


def test_its_datastore_is_on_its_network_before_anything_starts() -> None:
    # Not only once the engine starts or is configured: an environment starts what the engine
    # depends on first, and TM1 couldn't reach a datastore left off the network
    engine = Tm1EngineContainer(SOURCE)
    assert engine.network is not None
    assert engine.network is engine.datastore.network
    network = Network.new_network()
    engine.with_network(network)
    assert engine.datastore.network is network


def test_joins_a_network_it_is_given() -> None:
    network = Network.new_network()
    engine = Tm1EngineContainer(SOURCE).with_network(network)
    engine.configure()
    assert engine.network is network
    assert engine.datastore.network is network


def test_mounts_each_source_where_the_image_looks(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(Tm1EngineContainer.ENGINE_IMAGE, raising=False)
    install = tmp_path / "install"
    (install / "bin").mkdir(parents=True)
    (install / "bin" / "tm1sd.exe").touch()
    assert binds(Tm1EngineContainer(Tm1EngineSource.InstallDir(install))) == [f"{install}:/opt/ibm/tm1-engine:ro"]
    assert binds(Tm1EngineContainer(Tm1EngineSource.InstallVolume("tm1-engine"))) == ["tm1-engine:/opt/ibm/tm1-engine:rw"]

    (tmp_path / "tm1-v12.6.7-standalone-linux.tgz").touch()
    bundle = Tm1EngineContainer(Tm1EngineSource.Bundle(tmp_path, "tm1-v12.6.7-standalone-linux.tgz"))
    assert binds(bundle) == [f"{tmp_path}:/ibm:ro", "tm1-engine-testcontainers-cache:/tm1/cache:rw"]
    assert bundle.env_map["TM1_ENGINE_BUNDLE"] == "tm1-v12.6.7-standalone-linux.tgz"

    url = Tm1EngineContainer(Tm1EngineSource.BundleUrl("https://example.com/p.tgz", "abc"))
    assert binds(url) == ["tm1-engine-testcontainers-cache:/tm1/cache:rw"]
    assert url.env_map["TM1_ENGINE_BUNDLE_URL"] == "https://example.com/p.tgz"
    assert url.env_map["TM1_ENGINE_BUNDLE_SHA256"] == "abc"

    assert binds(Tm1EngineContainer(SOURCE)) == []
    assert Tm1EngineContainer(SOURCE).image == "tm1-engine:test"


def test_names_a_classes_database_after_the_class() -> None:
    # Here the class is the test module: test_cubes.py's database
    assert database_name("CubeTest", "k3f9") == "cubetest_k3f9"
    assert database_name("test_cubes", "k3f9") == "testcubes_k3f9"
    # A short suffix is padded, so the name is always long enough for TM1
    assert database_name("T", "7") == "t_0007"
    assert database_name("$$", "ab") == "test_00ab"
    assert database_name("AVeryLongTestClassNameThatGoesOnAndOn", "zzzz") == "averylongtestclassnameth_zzzz"


# Python's own: what Java's types and JUnit give, and here is code


def test_an_instances_name_has_no_least_length() -> None:
    # Where a database's has three characters
    engine = Tm1EngineContainer(SOURCE)
    assert engine.with_database("e", "sales").database("e", "sales").instance == "e"
    with pytest.raises(ValueError, match="A TM1 instance's name is letters, digits, '_' and '-'"):
        engine.with_database("_eu", "sales")
    with pytest.raises(ValueError, match="A TM1 database's name is at least three"):
        engine.with_database("eu", "sa")


def test_stops_its_datastore_and_closes_its_network_when_removing_the_engine_fails() -> None:
    class Unremovable:
        def remove(self, **_: object) -> None:
            raise OSError("the Docker daemon went away")

    class Recording(Network):
        closed = 0

        def close(self) -> None:
            self.closed += 1

    engine = Tm1EngineContainer(SOURCE)
    own = Recording()
    engine._own_network = own
    engine.with_network(own)
    stopped: list[bool] = []
    engine.datastore.stop = lambda *_, **__: stopped.append(True)  # type: ignore[method-assign]
    engine._container = Unremovable()
    with pytest.raises(OSError, match="went away"):
        engine.stop()
    assert stopped == [True]
    assert own.closed == 1

    # And a datastore that can't be removed still leaves the network closed; one the engine was
    # given stays
    def failing(*_: object, **__: object) -> None:
        raise OSError("the datastore is stuck")

    engine.datastore.stop = failing  # type: ignore[method-assign]
    engine._container = None
    with pytest.raises(OSError, match="stuck"):
        engine.stop()
    assert own.closed == 2
    given = Recording()
    engine.with_network(given)
    with pytest.raises(OSError, match="stuck"):
        engine.stop()
    assert given.closed == 0 and own.closed == 2


def test_the_realm_is_exactly_what_keycloak_imports_as_java_writes_it() -> None:
    engine = Tm1EngineContainer(SOURCE).with_user("analyst").with_login("analyst", "an$pw")
    assert json.loads(engine._realm()) == {
        "realm": "tm1",
        "enabled": True,
        "clients": [
            {
                "clientId": "tm1",
                "publicClient": True,
                "directAccessGrantsEnabled": True,
                "standardFlowEnabled": False,
                "protocolMappers": [
                    {
                        "name": "tm1-audience",
                        "protocol": "openid-connect",
                        "protocolMapper": "oidc-audience-mapper",
                        "config": {"included.custom.audience": "tm1", "access.token.claim": "true", "id.token.claim": "false"},
                    }
                ],
            }
        ],
        "users": [
            {
                "username": "analyst",
                "enabled": True,
                "emailVerified": True,
                "email": "user1@example.com",
                "firstName": "analyst",
                "lastName": "Test",
                "credentials": [{"type": "password", "value": "an$pw", "temporary": False}],
            }
        ],
    }


def test_keycloak_imports_the_realm_as_it_is_when_keycloak_starts() -> None:
    keycloak = KeycloakContainer()
    assert keycloak.command_parts == ["start-dev"]
    keycloak.configure()
    assert keycloak.command_parts == ["start-dev"] and keycloak._transferable_specs == []
    # One issuer for the engine and the tests, whichever address they use
    assert keycloak.env_map["KC_HOSTNAME"] == "http://keycloak:8080"

    engine = Tm1EngineContainer(SOURCE).with_oauth2(keycloak)
    keycloak.configure()
    engine.with_user("analyst").with_login("analyst", "pw")
    # Configured again, as when it starts: the realm of now, once
    keycloak.configure()
    assert keycloak.command_parts == ["start-dev", "--import-realm"]
    ((copied, destination, mode),) = keycloak._transferable_specs
    assert destination == "/opt/keycloak/data/import/tm1-realm.json"
    assert mode == 0o644
    assert json.loads(copied)["users"][0]["username"] == "analyst"


def test_an_o_auth2_login_asks_for_addresses_only_when_it_needs_them() -> None:
    engine = Tm1EngineContainer(SOURCE).with_user("analyst").with_login("analyst", "pw").with_oauth2(KeycloakContainer())
    # Made before the engine has started
    login = engine.database("tm1").oauth2_login("analyst")
    assert (login.instance, login.user, login.client_secret) == ("tm1", "analyst", "")
    assert repr(login) == "Tm1Login[analyst in tm1]"
    with pytest.raises(RuntimeError, match="An OAuth 2.0 login has no session to ask for"):
        login.session("Bearer x")
    # And a login through the application has no token to send on a request
    application = Tm1Login("http://localhost:1/tm1/auth/v1/", "tm1", "id", "secret", "analyst")
    assert not application.is_oauth2 and application.password == ""
    assert application.token_url == "http://localhost:1/tm1/auth/v1/token"
    with pytest.raises(RuntimeError, match="TM1 takes its own token only where a session is asked for"):
        application.authorize_with_token({})


def test_leaves_its_network_under_a_keycloak_that_is_still_running() -> None:
    class Recording(Network):
        closed = 0

        def close(self) -> None:
            self.closed += 1

    keycloak = KeycloakContainer()
    engine = Tm1EngineContainer(SOURCE)
    own = Recording()
    engine._own_network = own
    engine.with_network(own).with_oauth2(keycloak)
    assert keycloak.network is own
    # The Keycloak is the caller's to stop, after the engine: Docker wouldn't remove the network
    # from under it
    keycloak._container = object()
    engine.stop()
    assert own.closed == 0
    keycloak._container = None
    engine.stop()
    assert own.closed == 1


def test_picks_the_image_as_tm1_server_does(monkeypatch: pytest.MonkeyPatch) -> None:
    volume = Tm1EngineSource.InstallVolume("tm1-engine")
    monkeypatch.delenv(Tm1EngineContainer.ENGINE_IMAGE, raising=False)
    assert Tm1EngineContainer(volume).image == "ghcr.io/ajmyers/tm1-engine:edge"
    assert Tm1EngineContainer(volume, "me/engine:1").image == "me/engine:1"
    monkeypatch.setenv(Tm1EngineContainer.ENGINE_IMAGE, " tm1-engine:local ")
    assert Tm1EngineContainer(volume).image == "tm1-engine:local"
    assert Tm1EngineContainer(SOURCE).image == "tm1-engine:test"


def test_sets_the_engine_and_its_datastore_up_as_the_image_takes_them() -> None:
    engine = Tm1EngineContainer(SOURCE)
    assert engine._kwargs["platform"] == "linux/amd64"
    assert list(engine.ports) == ["4444"]
    assert not engine.should_be_reused
    assert engine.env_map["TM1_DATASTORE_URL"] == "mongodb://root:tm1datastore@tm1-datastore:27017"
    assert engine.datastore.env_map == {"MONGO_INITDB_ROOT_USERNAME": "root", "MONGO_INITDB_ROOT_PASSWORD": "tm1datastore"}
    assert engine.datastore.command_parts == ["--wiredTigerCacheSizeGB", "0.5"]
    # On the network before anything starts: the datastore is started ahead of configure()
    engine._join_network()
    assert engine.datastore.network is engine.network


def test_names_a_random_database_after_the_module() -> None:
    assert re.fullmatch(r"testdatabaseperclassexam_[0-9a-z]{4}", database_name("test_database_per_class_example"))


def test_a_database_is_its_engines_instance_and_name() -> None:
    engine = Tm1EngineContainer(SOURCE).with_database("planning")
    planning = engine.database("planning")
    assert planning == engine.database("tm1", "planning") and hash(planning) == hash(engine.database("planning"))
    assert planning != Tm1EngineContainer(SOURCE).with_database("planning").database("planning")
    assert repr(planning) == "Tm1Database[tm1/planning]"
    assert planning.engine is engine
    with pytest.raises(RuntimeError, match="isn't started"):
        _ = planning.version
    with pytest.raises(RuntimeError, match="isn't started"):
        engine.create_database("scratch")


class _Node:
    """What the plugin asks a test for: its requires_tm1_engine markers, the nearest first."""

    def __init__(self, *markers: pytest.MarkDecorator | pytest.Mark) -> None:
        self._marks = [marker.mark if isinstance(marker, pytest.MarkDecorator) else marker for marker in markers]

    def iter_markers(self, name: str) -> list[pytest.Mark]:
        assert name == "requires_tm1_engine"
        return self._marks


def test_reads_the_nearest_markers_options() -> None:
    assert plugin._engine_options(_Node()) == (False, "")
    assert plugin._engine_options(_Node(RequiresTm1Engine.mark)) == (RequiresTm1Engine.share_database, RequiresTm1Engine.min_version)
    assert plugin._engine_options(_Node(pytest.mark.requires_tm1_engine(share_database=True, min_version="12.6"))) == (True, "12.6")
    # The nearest marker that sets an option wins
    near = pytest.mark.requires_tm1_engine(share_database=False, min_version="12.7")
    assert plugin._engine_options(_Node(near, pytest.mark.requires_tm1_engine(share_database=True, min_version="12.6"))) == (False, "12.7")
    # A misspelt option fails rather than being ignored, on a wider marker too
    with pytest.raises(pytest.UsageError, match="share_databse"):
        plugin._engine_options(_Node(pytest.mark.requires_tm1_engine(share_databse=True)))
    with pytest.raises(pytest.UsageError, match="share_databse"):
        plugin._engine_options(_Node(RequiresTm1Engine.mark, pytest.mark.requires_tm1_engine(share_databse=True)))
    with pytest.raises(pytest.UsageError, match="12.6"):
        plugin._engine_options(_Node(pytest.mark.requires_tm1_engine("12.6")))


@pytest.mark.requires_tm1_engine(share_database=True, min_version="12.6")
class _Base:
    pass


class _Sub(_Base):
    pass


def test_a_subclass_keeps_the_annotations_options() -> None:
    # Java's @Inherited. pytest gives a subclass its base's markers, and a test the markers of
    # every scope around it: options set at a wider scope have to come with them, when a nearer
    # marker doesn't set them
    inherited = [mark for mark in _Sub.pytestmark if mark.name == "requires_tm1_engine"]  # type: ignore[attr-defined]
    assert plugin._engine_options(_Node(*inherited)) == (True, "12.6")
    assert plugin._engine_options(_Node(RequiresTm1Engine.mark, *inherited)) == (True, "12.6")
    assert plugin._engine_options(_Node(pytest.mark.requires_tm1_engine(min_version="12.7"), *inherited)) == (True, "12.7")
    assert plugin._engine_options(_Node(pytest.mark.requires_tm1_engine(share_database=False), *inherited)) == (False, "12.6")


class _Exec:
    def __init__(self, exit_code: int, stdout: bytes = b"", stderr: bytes = b"") -> None:
        self.exit_code, self.output = exit_code, (stdout or None, stderr or None)


class _Container:
    """A running container whose tm1-engine command answers as told: (exit code, stdout, stderr) by sub-command."""

    status = "running"

    def __init__(self, answers: dict[str, _Exec]) -> None:
        self.answers = answers
        self.commands: list[list[str]] = []

    def reload(self) -> None:
        pass

    def exec_run(self, command: list[str], demux: bool = False) -> _Exec:
        assert demux
        self.commands.append(command)
        return self.answers[command[-2] if command[3] == "database" else command[3]]


def test_logs_in_through_the_first_application_whose_secret_it_has(monkeypatch: pytest.MonkeyPatch) -> None:
    credentials = (
        b'{"root":{"clientId":"r","clientSecret":"rs"},"instances":['
        b'{"name":"tm1","applications":[{"name":"lost","clientId":"a","clientSecret":null},{"name":"tm1","clientId":"b","clientSecret":"bs"}],"databases":[{"name":"tm1"}]},'
        b'{"name":"other","applications":[{"name":"lost","clientId":"c","clientSecret":null}],"databases":[]}]}'
    )
    engine = Tm1EngineContainer(SOURCE)
    engine._container = _Container({"credentials": _Exec(0, credentials)})
    monkeypatch.setattr(Tm1EngineContainer, "url", "http://localhost:1/")
    login = engine._login("tm1", "analyst")
    assert (login.client_id, login.client_secret, login.user, login.auth_url) == ("b", "bs", "analyst", "http://localhost:1/tm1/auth/v1/")
    with pytest.raises(RuntimeError, match="The instance other has no application to log in through"):
        engine._login("other", "admin")
    with pytest.raises(RuntimeError, match="The instance missing has no application to log in through"):
        engine._login("missing", "admin")
    engine._container = None


def test_gives_a_create_longer_than_the_image_waits_and_drops_what_a_failed_one_left() -> None:
    engine = Tm1EngineContainer(SOURCE)
    container = _Container({"create": _Exec(0, b"/tm1/api/v1/Databases('scratch')\n")})
    engine._container = container
    made = engine.create_database("scratch")
    assert (made.instance, made.name) == ("tm1", "scratch")
    assert container.commands == [["timeout", "330", "tm1-engine", "database", "create", "tm1/scratch"]]

    # A create that fails: the half-made database is dropped, and the create's error is the one raised
    for drop in (_Exec(0), _Exec(1, stderr=b"no such database")):
        container = _Container({"create": _Exec(124, stderr=b"timed out"), "drop": drop})
        engine._container = container
        with pytest.raises(RuntimeError, match=r"tm1-engine database create other/scratch failed \(exit 124\): timed out"):
            engine.create_database("other", "scratch")
        assert container.commands == [
            ["timeout", "330", "tm1-engine", "database", "create", "other/scratch"],
            ["timeout", "180", "tm1-engine", "database", "drop", "other/scratch"],
        ]
    engine._container = None
