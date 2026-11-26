# SPDX-License-Identifier: Apache-2.0

"""Reads pa-setup's ``pa-environment.yaml`` onto a :class:`PlanningAnalyticsEnvironment.Builder`:
Java's ``PaEnvironmentFile``. The checks and defaults are ``docker/pa-setup/pa_setup.py``'s
(``normalize``), in its order and with its messages, so a file one accepts the other does too, except
for what the environment can't run, which fails here with a message naming the setting."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

import yaml

from .cognos import Namespace, User
from .engine import Tm1EngineContainer
from .paw import PawContainer
from .tm1 import Tm1Seed

if TYPE_CHECKING:
    from .environment import PlanningAnalyticsEnvironment

_NAME = re.compile(r"[A-Za-z0-9_-]+")
_USER = re.compile(r"[A-Za-z0-9._-]+")
_SEEDS = ["empty", "sdata", "24retail"]
# The tm1-engine image's rule for a database's and an instance's name
_ENGINE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")


@dataclass(frozen=True)
class _Server:
    port: int
    seed: str
    cfg: dict[str, str]


@dataclass(frozen=True)
class _Cognos:
    port: int
    users: list[User]
    keycloak_port: int | None
    namespace: Namespace
    admins: list[str]
    memory: int


@dataclass(frozen=True)
class _EngineUser:
    name: str
    groups: list[str]


@dataclass(frozen=True)
class _Engine:
    # name -> instance, in the file's order
    databases: dict[str, str]
    users: list[_EngineUser]


def read(path: Path | str) -> PlanningAnalyticsEnvironment.Builder:
    """Reads and checks the file, and configures a new builder from it."""
    path = Path(path)
    reader = _Reader(path.name)
    try:
        with path.open(encoding="utf-8") as f:
            cfg = yaml.safe_load(f)
    except FileNotFoundError:
        raise reader.error(f"not found: {path.absolute()}") from None
    except OSError as e:
        raise reader.error(f"can't be read: {e}") from e
    except yaml.YAMLError as e:
        raise reader.error(f"isn't valid YAML: {e}") from e
    folder = Path(os.path.normpath(path.absolute().parent))
    return reader.apply({} if cfg is None else cfg, folder)


def _truthy(value: Any) -> bool:
    """Python's truth: None, False, 0, "" and empty collections are false."""
    return bool(value)


def _or(value: Any, fallback: Any) -> Any:
    """pa_setup's ``value or fallback``."""
    return value or fallback


def _is_int(value: Any) -> bool:
    """A YAML integer: Java's Integer, so not a boolean."""
    return isinstance(value, int) and not isinstance(value, bool)


class _Reader:
    def __init__(self, file: str) -> None:
        self._file = file

    def apply(self, root: Any, folder: Path) -> PlanningAnalyticsEnvironment.Builder:
        # pa_setup.normalize: the same checks, in the same order
        cfg = self._keys(root, ["version", "name", "ibm", "files", "images", "tm1", "tm1Web", "paw", "cognos", "tm1Engine"], "the file")
        version = cfg.get("version")
        if not _is_int(version) or version != 1:
            raise self.error("version: must be 1")
        ibm = self._host_path(cfg.get("ibm"), "ibm", folder) if cfg.get("ibm") else None
        self._host_path(_or(cfg.get("files"), "pa-files"), "files", folder)
        images = self._keys(_or(cfg.get("images"), {}), ["tm1Server", "tm1Web", "cognosServer", "keycloak", "tm1Engine", "mongo"], "images")

        tm1 = self._section(cfg, "tm1")
        servers: dict[str, _Server] = {}
        admin: int | None = None
        if tm1 is not None:
            self._keys(tm1, ["servers", "adminServer", "adminPort"], "tm1")
            named = tm1.get("servers")
            if not isinstance(named, dict) or not named:
                raise self.error("tm1.servers: name at least one server, e.g. tm1: {servers: {tm1: {}}}")
            used_ports: set[int] = set()
            lower: set[str] = set()
            for i, (name, value) in enumerate(named.items()):
                where = f"tm1.servers.{_text(name)}"
                if not isinstance(name, str) or not _NAME.fullmatch(name):
                    raise self.error(f"{where}: server names are letters, digits, _ and -")
                if name.lower() in lower:
                    raise self.error(f"{where}: two servers differ only in case")
                lower.add(name.lower())
                s = self._keys(_or(value, {}), ["port", "seed", "cfg"], where)
                p = self._port(s.get("port"), where, 8010 + i)
                if p in used_ports:
                    raise self.error(f"{where}: port {p} is used twice")
                used_ports.add(p)
                seed = _text(s.get("seed")) if s.get("seed") else "empty"
                if seed not in _SEEDS:
                    raise self.error(f"{where}.seed: one of {', '.join(_SEEDS)}")
                extra = self._mapping(_or(s.get("cfg"), {}), where + ".cfg")
                settings: dict[str, str] = {}
                for key, setting in extra.items():
                    key = _text(key)
                    if not _NAME.fullmatch(key):
                        raise self.error(f"{where}.cfg: '{key}' isn't a tm1s.cfg parameter name")
                    settings[key] = self._cfg_value(setting, f"{where}.cfg.{key}")
                servers[name] = _Server(p, seed, settings)
            wants_admin = tm1.get("adminServer")
            if wants_admin is True or (wants_admin is None and len(servers) > 1):
                admin = self._port(tm1.get("adminPort"), "tm1.adminPort", 5895)
            elif wants_admin is False and len(servers) > 1:
                raise self.error("tm1.adminServer: several servers need a shared Admin Server")

        web = self._section(cfg, "tm1Web")
        web_port: int | None = None
        heap: str | None = None
        if web is not None:
            self._keys(web, ["port", "heap"], "tm1Web")
            if not servers:
                raise self.error("tm1Web: needs a TM1 server (tm1.servers)")
            web_port = self._port(web.get("port"), "tm1Web", 9510)
            heap = _text(web.get("heap")) if web.get("heap") else "1g"

        cognos = self._section(cfg, "cognos")
        cam: _Cognos | None = None
        if cognos is not None:
            self._keys(cognos, ["port", "users", "oidc", "cam", "memory"], "cognos")
            listed = _or(cognos.get("users"), [])
            if not isinstance(listed, list):
                raise self.error("cognos.users: a list of users, each user:password[:group,group]")
            users = [self._user(entry, f"cognos.users[{u}]") for u, entry in enumerate(listed)]
            names = [u.name for u in users]
            if len(set(names)) != len(names):
                raise self.error("cognos.users: a user is listed twice")
            oidc = cognos.get("oidc")
            if oidc is True:
                oidc = {}
            keycloak_port: int | None = None
            # On with true or a mapping, an empty one too: only absent or false is off
            if oidc is not None and oidc is not False:
                o = self._keys(oidc, ["keycloakPort"], "cognos.oidc")
                if not users:
                    raise self.error("cognos.oidc: needs cognos.users")
                keycloak_port = self._port(o.get("keycloakPort"), "cognos.oidc", 8180)
            cam_section = self._keys(_or(cognos.get("cam"), {}), ["namespace", "admins"], "cognos.cam")
            namespace = (_text(cam_section.get("namespace")) if cam_section.get("namespace") else "ldap").lower()
            if namespace not in ("ldap", "oidc"):
                raise self.error("cognos.cam.namespace: ldap or oidc")
            if namespace == "oidc" and keycloak_port is None:
                raise self.error("cognos.cam.namespace: oidc needs cognos.oidc")
            admins: list[str] = []
            if cam_section.get("admins"):
                if not isinstance(cam_section.get("admins"), list):
                    raise self.error("cognos.cam.admins: a list of cognos.users")
                admins = [_text(a) for a in cam_section["admins"]]
            elif names:
                admins = names[:1]
            if servers and not users:
                raise self.error("cognos.users: TM1 in CAM security needs test users")
            for a in admins:
                if a not in names:
                    raise self.error(f"cognos.cam.admins: {a} isn't one of cognos.users")
            memory = _or(cognos.get("memory"), 6144)
            if not _is_int(memory) or memory < 2048:
                raise self.error("cognos.memory: the dispatcher heap in MB, at least 2048 (6144 recommended)")
            cognos_port = self._port(cognos.get("port"), "cognos", 9300)
            cam = _Cognos(cognos_port, users, keycloak_port, Namespace.OIDC if namespace == "oidc" else Namespace.LDAP, admins, memory)

        paw = self._section(cfg, "paw")
        paw_port: int | None = None
        paw_zip: str | None = None
        if paw is not None:
            self._keys(paw, ["port", "acceptLicense", "zip"], "paw")
            if not servers:
                raise self.error("paw: needs a TM1 server (tm1.servers)")
            if paw.get("acceptLicense") is not True:
                raise self.error(
                    "paw.acceptLicense: PAW starts only once you accept IBM's license (true); read it with: unzip -p ipa_workspace_local_*.zip licenses/LA_en"
                )
            if ibm is None:
                raise self.error("paw: needs ibm, the folder with the PAW Local zip")
            paw_port = self._port(paw.get("port"), "paw", 8800)
            paw_zip = _text(paw.get("zip")) if paw.get("zip") else None

        engine_section = self._section(cfg, "tm1Engine")
        engine: _Engine | None = None
        engine_port: int | None = None
        datastore_port: int | None = None
        if engine_section is not None:
            self._keys(engine_section, ["port", "databases", "users", "datastore"], "tm1Engine")
            databases: dict[str, str] = {}
            raw = engine_section.get("databases")
            if raw is None:
                databases[Tm1EngineContainer.DEFAULT_DATABASE] = Tm1EngineContainer.DEFAULT_INSTANCE
            elif not isinstance(raw, dict) or not raw:
                raise self.error("tm1Engine.databases: name at least one database, e.g. tm1Engine: {databases: {planning: {}}}")
            else:
                for name, value in raw.items():
                    where = f"tm1Engine.databases.{_text(name)}"
                    # No check for names that differ only in case, as tm1.servers has: Sales and
                    # sales are two databases to TM1 v12
                    if not isinstance(name, str):
                        raise self.error(f"{where}: YAML reads this name as a number or true/false: put it in quotes")
                    if not _ENGINE_NAME.fullmatch(name):
                        raise self.error(f"{where}: database names are letters, digits, _ and -, starting with a letter or digit")
                    if len(name) < 3:
                        raise self.error(f"{where}: TM1 needs database names of at least 3 characters")
                    d = self._keys(_or(value, {}), ["instance"], where)
                    instance = Tm1EngineContainer.DEFAULT_INSTANCE if d.get("instance") is None else d.get("instance")
                    if not isinstance(instance, str) or not _ENGINE_NAME.fullmatch(instance):
                        raise self.error(f"{where}.instance: instance names are letters, digits, _ and -, starting with a letter or digit")
                    databases[name] = instance
            listed_users = _or(engine_section.get("users"), [])
            if not isinstance(listed_users, list):
                raise self.error("tm1Engine.users must be a list, e.g. users: [analyst::Analysts]")
            # Every user is checked before any is found twice, as pa_setup does
            engine_users = [self._engine_user(entry, f"tm1Engine.users[{u}]") for u, entry in enumerate(listed_users)]
            engine_names = [user.name.lower() for user in engine_users]
            if len(set(engine_names)) != len(engine_names):
                raise self.error("tm1Engine.users: a user is listed twice")
            datastore = engine_section.get("datastore")
            ds = {} if datastore is None or isinstance(datastore, bool) else self._keys(datastore, ["port"], "tm1Engine.datastore")
            engine_port = self._port(engine_section.get("port"), "tm1Engine", 4444)
            # Published only when a port is given
            datastore_port = None if ds.get("port") is None else self._port(ds.get("port"), "tm1Engine.datastore", 27017)
            for name in servers:
                # The engine's two compose services in pa-setup: a server of that name would be
                # replaced by them
                if name.lower() in ("tm1-engine", "tm1-engine-mongo"):
                    raise self.error(f"tm1.servers.{name}: that name is the TM1 v12 engine's here; give the server another")
            engine = _Engine(databases, engine_users)

        if not servers and cam is None and engine is None:
            raise self.error("nothing to run: add tm1.servers, tm1Engine and/or cognos")
        ports = [p for p in (engine_port, datastore_port) if p is not None]
        ports += [s.port for s in servers.values()]
        ports += [p for p in (admin, web_port) if p is not None]
        if cam is not None:
            ports += [p for p in (cam.port, cam.keycloak_port) if p is not None]
        if paw_port is not None:
            ports.append(paw_port)
        dupes = sorted({p for p in ports if ports.count(p) > 1})
        if dupes:
            raise self.error(f"port(s) {', '.join(map(str, dupes))} used by more than one service")

        # What pa-setup runs and this environment can't
        if cam is not None and not servers:
            raise self.error("cognos: PlanningAnalyticsEnvironment needs a TM1 server too (tm1.servers); for Cognos alone, use CognosContainer")
        for name, server in servers.items():
            if server.seed == "24retail":
                raise self.error(f"tm1.servers.{name}.seed: 24retail isn't available in tm1-testcontainers (Tm1Seed has empty and sdata)")

        b = _configure(servers, admin is not None, heap, cam, paw_port is not None, ibm, paw_zip, images)
        if engine is not None:
            wanted = engine

            def customize(container: Tm1EngineContainer) -> None:
                for name, instance in wanted.databases.items():
                    container.with_database(instance, name)
                # TM1 v12 keeps no passwords: a user's is checked and not used
                for user in wanted.users:
                    container.with_user(user.name, *user.groups)

            b.tm1_engine(customize)
            b._tm1_engine_image = _image(images, "tm1Engine")
        return b

    def _keys(self, section: Any, allowed: list[str], where: str) -> dict[Any, Any]:
        """pa_setup.check_keys: a mapping with only the allowed settings."""
        mapping = self._mapping(section, where)
        unknown = sorted({_text(key) for key in mapping} - set(allowed))
        if unknown:
            raise self.error(f"{where}: unknown setting(s) {', '.join(unknown)} (allowed: {', '.join(allowed)})")
        return mapping

    def _mapping(self, section: Any, where: str) -> dict[Any, Any]:
        if not isinstance(section, dict):
            raise self.error(f"{where} must be a mapping")
        return section

    def _section(self, cfg: dict[Any, Any], key: str) -> dict[Any, Any] | None:
        """pa_setup.section: absent or false is off; true is on with defaults."""
        value = cfg.get(key)
        if value is None or value is False:
            return None
        if value is True:
            return {}
        return self._mapping(value, key)

    def _port(self, value: Any, where: str, fallback: int) -> int:
        p = fallback if value is None else value
        if not _is_int(p) or not 1 <= p <= 65535:
            raise self.error(f"{where}: port must be a number from 1 to 65535, not {_text(p)}")
        return int(p)

    def _host_path(self, value: Any, what: str, folder: Path) -> Path:
        if not isinstance(value, str) or not value.strip():
            raise self.error(f"{what} must be a path")
        path = value.strip()
        if path == "~" or path.startswith("~/") or path.startswith("~\\"):
            home = Path.home()
            return Path(os.path.normpath(home / path[2:] if len(path) > 1 else home))
        return Path(os.path.normpath(folder / path))

    def _user(self, entry: Any, where: str) -> User:
        name: Any
        password: Any
        groups: list[Any] = []
        if isinstance(entry, str):
            parts = entry.split(":")
            if len(parts) not in (2, 3):
                raise self.error(f"{where}: expected user:password[:group,group], not '{entry}'")
            name, password = parts[0], parts[1]
            if len(parts) == 3:
                groups = [g for g in parts[2].split(",") if g]
        elif isinstance(entry, dict):
            mapping = self._keys(entry, ["name", "password", "groups"], where)
            name = mapping.get("name")
            password = mapping.get("password")
            listed = _or(mapping.get("groups"), [])
            if not isinstance(listed, list):
                raise self.error(f"{where}: groups is a list")
            groups = list(listed)
        else:
            raise self.error(f"{where}: a user is user:password[:groups] or a mapping")
        # Strings only, as in pa_setup: a missing name or a number isn't a user name
        if not isinstance(name, str) or not _USER.fullmatch(name):
            raise self.error(f"{where}: user name '{_text(name)}' must be letters, digits, '.', '_' and '-'")
        if not isinstance(password, str) or not password or re.search(r"[:\s]", password):
            raise self.error(f"{where}: {name}'s password must be non-empty, without ':' or spaces")
        for g in groups:
            if not isinstance(g, str) or not _USER.fullmatch(g):
                raise self.error(f"{where}: group '{_text(g)}' must be letters, digits, '.', '_' and '-'")
        return User(name, password, tuple(groups))

    def _engine_user(self, entry: Any, where: str) -> _EngineUser:
        """pa_setup.parse_user with password_optional: an engine's user, whose password may be left
        out or empty, since TM1 v12 keeps none."""
        name: Any
        password: Any
        groups: list[Any] = []
        if isinstance(entry, str):
            parts = entry.split(":")
            if len(parts) == 2 and parts[1]:
                # The image's own form has groups there; here the second part is a password
                raise self.error(f"{where}: '{entry}' reads as a password, which TM1 v12 doesn't keep: for a group, write {parts[0]}::{parts[1]}")
            if len(parts) > 3:
                raise self.error(f"{where}: expected user, user::group,group or user:password:group,group, not '{entry}'")
            name = parts[0]
            password = parts[1] if len(parts) > 1 else ""
            if len(parts) == 3:
                groups = [g for g in parts[2].split(",") if g]
        elif isinstance(entry, dict):
            mapping = self._keys(entry, ["name", "password", "groups"], where)
            name = mapping.get("name")
            password = "" if mapping.get("password") is None else mapping.get("password")
            listed = _or(mapping.get("groups"), [])
            if not isinstance(listed, list):
                raise self.error(f"{where}: groups must be a list, e.g. groups: [Analysts]")
            groups = list(listed)
        else:
            raise self.error(f"{where}: a user is user:password[:groups] or a mapping")
        if not isinstance(name, str) or not _USER.fullmatch(name):
            raise self.error(f"{where}: user name '{_text(name)}' must be letters, digits, '.', '_' and '-'")
        if not isinstance(password, str) or re.search(r"[:\s]", password):
            raise self.error(f"{where}: {name}'s password must be text without ':' or spaces (quote a number)")
        for g in groups:
            if not isinstance(g, str) or not _USER.fullmatch(g):
                raise self.error(f"{where}: group '{_text(g)}' must be letters, digits, '.', '_' and '-'")
        return _EngineUser(name, [str(g) for g in groups])

    def _cfg_value(self, value: Any, where: str) -> str:
        """A tm1s.cfg value as pa_setup writes it, with str(): true is True."""
        if isinstance(value, (bool, int, float, str)):
            return str(value)
        raise self.error(f"{where}: a string, number or boolean")

    def error(self, message: str) -> ValueError:
        return ValueError(f"{self._file}: {message}")


def _text(value: Any) -> str:
    """A value as Java's String.valueOf writes it: None is null, booleans are lower case."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _configure(
    servers: dict[str, _Server],
    admin: bool,
    heap: str | None,
    cam: _Cognos | None,
    paw: bool,
    ibm: Path | None,
    paw_zip: str | None,
    images: dict[Any, Any],
) -> PlanningAnalyticsEnvironment.Builder:
    from .environment import PlanningAnalyticsEnvironment

    b = PlanningAnalyticsEnvironment.builder()
    for name, s in servers.items():
        b.tm1(name, _server(s))
    if admin:
        b.admin_server()
    if heap is not None:
        b.tm1_web(lambda web: web.with_env("TM1_WEB_HEAP", heap))
    if cam is not None:

        def cognos(container: Any) -> None:
            for u in cam.users:
                container.with_user(u.name, u.password, *u.groups)
            container.with_env("COGNOS_MAX_MEMORY", str(cam.memory))

        b.cognos(cognos)
        if cam.keycloak_port is not None:
            b.keycloak()
        b.cam_security(cam.namespace, *cam.admins)
    if paw:
        b.paw(PawContainer.accept_license)
    b._ibm = ibm
    b._paw_zip = paw_zip
    b._tm1_image = _image(images, "tm1Server")
    b._tm1_web_image = _image(images, "tm1Web")
    b._cognos_image = _image(images, "cognosServer")
    b._keycloak_image = _image(images, "keycloak")
    return b


def _server(s: _Server) -> Any:
    def customize(server: Any) -> None:
        server.with_seed(Tm1Seed.SDATA if s.seed == "sdata" else Tm1Seed.EMPTY)
        for key, value in s.cfg.items():
            server.with_env(f"TM1_CFG_{key}", value)

    return customize


def _image(images: dict[Any, Any], key: str) -> str | None:
    return _text(images.get(key)).strip() if images.get(key) else None
