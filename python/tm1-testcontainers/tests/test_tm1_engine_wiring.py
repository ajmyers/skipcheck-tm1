# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineWiringTest. Wiring only: nothing here starts a container."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import Network, RequiresTm1Engine, Tm1EngineContainer, Tm1EngineSource, plugin
from skipcheck_tm1_testcontainers.engine import database_name

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
