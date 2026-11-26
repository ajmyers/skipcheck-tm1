# SPDX-License-Identifier: Apache-2.0

"""A TM1 v12 engine in Docker: Java's ``Tm1EngineContainer``, ``Tm1Database`` and ``Tm1Login``."""

from __future__ import annotations

import atexit
import base64
import json
import re
import secrets
import threading
import time
from collections.abc import MutableMapping
from typing import TYPE_CHECKING, Any, ClassVar, Self, TypeVar

import httpx2

from skipcheck_tm1 import Tm1Version

from ._container import GenericContainer, Network, wait_for_http
from .sources import Tm1EngineSource
from .tm1 import default_image

if TYPE_CHECKING:
    from .cognos import KeycloakContainer

__all__ = ["Tm1Database", "Tm1EngineContainer", "Tm1Login"]

_INSTALL_PATH = "/opt/ibm/tm1-engine"
_OTHER_NETWORK = (
    "The engine takes tokens from a Keycloak on another network, which TM1 couldn't reach from this one: "
    "set the network before with_oauth2, or give the Keycloak the same"
)
_STARTUP_TIMEOUT = 5 * 60.0
_COMMAND_TIMEOUT = 3 * 60
# The image waits up to five minutes for a new database to be ready: longer than that, so its
# own message is the one a slow create ends with
_CREATE_TIMEOUT = 330
_REQUEST_TIMEOUT = 30.0

# The image's own rule: at least three characters, and nothing the commands or a URL would need
# quoting for
_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{2,}")
# An instance's name has no least length: only a database's does
_INSTANCE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")
_USER = re.compile(r"[A-Za-z0-9._-]+")

# The datastore is this engine's alone and goes with it: nothing else is given the password
_DATASTORE_USER = "root"
_DATASTORE_PASSWORD = "tm1datastore"

NO_SOURCE_MESSAGE = (
    "No TM1 v12 source configured. Set one of TM1_ENGINE_PRIVATE_IMAGE, TM1_ENGINE_HOME_DIR, TM1_ENGINE_INSTALL_VOLUME, "
    "TM1_ENGINE_BUNDLE_DIR or TM1_ENGINE_BUNDLE_URL, or put a tm1-v*-standalone-linux.tgz in TM1_BUNDLE_DIR "
    "(as an environment variable or system property) to run tests against TM1 v12."
)
"""Java's ``RequiresTm1Engine.NO_SOURCE_MESSAGE``."""

_Request = TypeVar("_Request")


def database_name(unit: str, suffix: str | None = None) -> str:
    """The name of a test module's own database, as Java's ``Tm1EngineExtension.databaseName``: the
    unit's name, as far as a database's name allows, and a few random characters."""
    if suffix is None:
        number = secrets.randbelow(36**4)
        suffix = ""
        while number:
            number, digit = divmod(number, 36)
            suffix = "0123456789abcdefghijklmnopqrstuvwxyz"[digit] + suffix
    letters = re.sub(r"[^a-z0-9]", "", unit.lower())
    # A short suffix is padded, so the name is always long enough for TM1
    return f"{letters[:24] or 'test'}_{suffix.rjust(4, '0')}"


def _require_instance(name: str) -> str:
    if not isinstance(name, str) or _INSTANCE.fullmatch(name) is None:
        raise ValueError(f"A TM1 instance's name is letters, digits, '_' and '-', starting with a letter or digit: {name}")
    return name


def _require_name(name: str, what: str) -> str:
    if not isinstance(name, str) or _NAME.fullmatch(name) is None:
        raise ValueError(f"A TM1 {what}'s name is at least three letters, digits, '_' and '-', starting with a letter or digit: {name}")
    return name


class Tm1EngineContainer(GenericContainer):
    """A TM1 v12 engine (Planning Analytics 3.x) in Docker, for tests: the ``tm1-engine`` image with
    TM1 from a :class:`Tm1EngineSource`, and the MongoDB it keeps its metadata in, on a network of
    their own. For TM1 11, use :class:`Tm1ServerContainer`.

    One engine holds **instances**, each holding **databases**. Most tests use the one instance,
    ``tm1``, and a database of their own: the ``tm1_database`` fixture gives each test module one. A
    container you make yourself has the databases you name with :meth:`with_database`, or one
    called ``tm1``.

    TM1 v12 has no passwords of its own. A test logs in through the instance's application
    (:class:`Tm1Login`), which this container creates and keeps the credentials of.

    Starting takes about 40 seconds, and a database about 10 more to create
    (:meth:`create_database`). The image exists for x86-64 only, as TM1 does; on Apple Silicon it
    runs under Rosetta."""

    DEFAULT_IMAGE: ClassVar[str] = "ghcr.io/ajmyers/tm1-engine"
    """The ``tm1-engine`` image, without a tag."""
    ENGINE_IMAGE: ClassVar[str] = "TM1_ENGINE_IMAGE"
    """The setting that overrides the ``tm1-engine`` image (not used with
    :class:`Tm1EngineSource.PrivateImage`), e.g. ``tm1-engine:local`` for a local build."""
    DATASTORE_IMAGE: ClassVar[str] = "mongo:8.0"
    """The MongoDB image the engine's datastore runs."""
    PORT: ClassVar[int] = 4444
    """The port of TM1's gateway inside the container: the REST API, logins and management."""
    DEFAULT_INSTANCE: ClassVar[str] = "tm1"
    """The instance databases are in unless one is named."""
    DEFAULT_DATABASE: ClassVar[str] = "tm1"
    """The database a container has when none is named."""
    DATASTORE_ALIAS: ClassVar[str] = "tm1-datastore"
    """The datastore's name on the engine's network."""
    CACHE_VOLUME: ClassVar[str] = "tm1-engine-testcontainers-cache"
    """The volume package installs are cached in, shared by every engine container on this Docker
    host. Only the first container to see a package installs it. Remove the volume to clear it."""

    OAUTH2_REALM: ClassVar[str] = "tm1"
    """The Keycloak realm an engine with :meth:`with_oauth2` takes tokens from."""
    OAUTH2_CLIENT: ClassVar[str] = "tm1"
    """The client of that realm a test asks for tokens as, and the audience its tokens carry."""

    _shared: ClassVar[Tm1EngineContainer | None] = None
    _shared_lock: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, source: Tm1EngineSource, image: str | None = None) -> None:
        if source is None:
            raise ValueError("source must not be None")
        super().__init__(image or default_image(source, Tm1EngineContainer.DEFAULT_IMAGE, Tm1EngineContainer.ENGINE_IMAGE))
        self._source = source
        # instance/database, in the order they were added
        self._databases: list[str] = []
        self._users: dict[str, list[str]] = {}
        # The users who can log in through OAuth 2.0, and what with
        self._passwords: dict[str, str] = {}
        self._keycloak: KeycloakContainer | None = None
        self._credentials: dict[str, Any] | None = None
        self._credentials_lock = threading.Lock()
        self._version: Tm1Version | None = None
        self.with_exposed_ports(self.PORT)
        # TM1 only exists for x86-64. On Apple Silicon this runs under Rosetta.
        self.with_platform("linux/amd64")
        # The gateway refuses a request without a login (401) once it's up. That isn't ready yet:
        # container_is_started then waits for the image to finish setting TM1 up. It stops waiting
        # if the container exits, as it does when TM1 can't start.
        self.waiting_for(wait_for_http("/", self.PORT, 401, 200, timeout=_STARTUP_TIMEOUT))
        self._datastore = (
            GenericContainer(self.DATASTORE_IMAGE)
            .with_network_aliases(self.DATASTORE_ALIAS)
            .with_env("MONGO_INITDB_ROOT_USERNAME", _DATASTORE_USER)
            .with_env("MONGO_INITDB_ROOT_PASSWORD", _DATASTORE_PASSWORD)
            # Enough for TM1's metadata; MongoDB would otherwise take half the machine's memory
            .with_command(["--wiredTigerCacheSizeGB", "0.5"])
        )
        self.with_env("TM1_DATASTORE_URL", f"mongodb://{_DATASTORE_USER}:{_DATASTORE_PASSWORD}@{self.DATASTORE_ALIAS}:27017")
        self.depends_on(self._datastore)
        # A network of their own unless one is given. It's made in Docker only when used.
        self._own_network = Network.new_network()
        self.with_network(self._own_network)
        self._mount_source()

    def with_network(self, network: Any) -> Self:
        """Puts the engine, and its datastore with it, on a network. They have one of their own
        unless this is called; a network that's given is never removed by the engine. A RuntimeError
        if the engine takes tokens from a Keycloak (:meth:`with_oauth2`) that's on another."""
        joined = getattr(self, "_keycloak", None)
        if joined is not None and joined.network is not network:
            # The Keycloak is the caller's, and may serve others: it isn't moved from here
            raise RuntimeError(_OTHER_NETWORK)
        super().with_network(network)
        # At once, not when the engine starts: whatever starts the engine may start what it
        # depends on first, as an environment does, and the datastore must be here by then
        self._datastore.with_network(network)
        return self

    @classmethod
    def shared(cls) -> Tm1EngineContainer:
        """The engine most tests share: one per test process, started on first use with the source
        :meth:`Tm1EngineSource.discover` finds, and stopped when the session or the process ends. It
        has one database, ``tm1``; the ``tm1_database`` fixture creates one more for each test module.

        It isn't marked reusable: an engine and its datastore belong together, and a kept engine
        would keep every database tests left in it. A RuntimeError if no source is configured."""
        with cls._shared_lock:
            if Tm1EngineContainer._shared is None:
                source = Tm1EngineSource.discover()
                if source is None:
                    raise RuntimeError(NO_SOURCE_MESSAGE)
                container = Tm1EngineContainer(source)
                container.start()
                Tm1EngineContainer._shared = container
                atexit.register(container.stop)
            return Tm1EngineContainer._shared

    def with_database(self, instance_or_name: str, name: str | None = None) -> Self:
        """Adds a database: ``with_database(name)`` in the instance ``tm1``, or
        ``with_database(instance, name)`` in a named instance, which is created with an application
        of its own. An instance's users, sessions and applications are separate from another's.
        Without any, the container has one database called ``tm1``. A database's name is at least
        three letters, digits, ``_`` or ``-``, and an instance's at least one; ``Sales`` and ``sales``
        are two databases."""
        instance, name = _instance_and_name(instance_or_name, name)
        entry = _require_instance(instance) + "/" + _require_name(name, "database")
        if entry not in self._databases:
            self._databases.append(entry)
        return self

    def with_user(self, name: str, *groups: str) -> Self:
        """Adds a user to every database, in exactly these groups (created if missing). A new
        database has one user already: ``admin``, in the ``ADMIN`` group."""
        if not isinstance(name, str) or _USER.fullmatch(name) is None:
            raise ValueError(f"A user's name is letters, digits, '.', '_' and '-': {name}")
        for group in groups:
            if not isinstance(group, str) or _USER.fullmatch(group) is None:
                raise ValueError(f"A group's name is letters, digits, '.', '_' and '-': {group}")
        if name in self._users:
            raise ValueError(f"The user {name} is already added")
        self._users[name] = list(groups)
        return self

    def with_oauth2(self, keycloak: KeycloakContainer) -> Self:
        """Takes OAuth 2.0 logins: tokens from a Keycloak, which gets a realm (``tm1``) with the
        users given a password by :meth:`with_login`. A test then logs in with
        :meth:`Tm1Database.oauth2_login`. Logins through the engine's application
        (:meth:`Tm1Database.login`) keep working beside it.

        The Keycloak joins the engine's network, unless it has one already, which the engine then
        joins. It's yours to stop: the engine only starts it first, so give it one that isn't
        started yet, and stop it after the engine."""
        if keycloak is None:
            raise ValueError("keycloak must not be None")
        if self._keycloak is keycloak:
            return self
        if self._keycloak is not None:
            raise RuntimeError("The engine already takes tokens from another Keycloak, which would start beside this one under the same name: give it one")
        if keycloak.is_running:
            # It gets its realm, and its place on the network, when it starts
            raise RuntimeError(
                "This Keycloak is already running, without the engine's realm: give with_oauth2 one that isn't started, and the engine starts it"
            )
        # Asked for when Keycloak starts, so users added after this call are in it. First: it
        # refuses a Keycloak that another engine uses
        keycloak._with_realm(self.OAUTH2_REALM, self, self._realm)
        if keycloak.network is not None:
            self.with_network(keycloak.network)
        else:
            # The engine's own, or a new one if it was taken off it
            self._join_network()
            network = self.network
            assert network is not None
            keycloak.with_network(network)
        self._keycloak = keycloak
        self.depends_on(keycloak)
        # Keycloak's address on the network is what its tokens name, however they're fetched, and
        # TM1 has to reach the issuer a token names
        self.with_env("TM1_OAUTH2_ISSUER_URL", f"{keycloak.network_url}/realms/{self.OAUTH2_REALM}")
        self.with_env("TM1_OAUTH2_AUDIENCE", self.OAUTH2_CLIENT)
        self.with_env("TM1_OAUTH2_ID_CLAIMS", "preferred_username")
        return self

    def with_login(self, user: str, password: str) -> Self:
        """Gives a user a password to log in with through OAuth 2.0 (:meth:`with_oauth2`). TM1 v12
        keeps no passwords itself: it's the Keycloak's. The user is one added with
        :meth:`with_user`, or ``admin``."""
        if not isinstance(user, str) or _USER.fullmatch(user) is None:
            raise ValueError(f"A user's name is letters, digits, '.', '_' and '-': {user}")
        if not isinstance(password, str) or not password:
            raise ValueError(f"The password of {user} must not be empty")
        # A name is one whatever its case, to Keycloak and to TM1: a second password for it
        # replaces the first
        for known in [name for name in self._passwords if name.lower() == user.lower()]:
            del self._passwords[known]
        self._passwords[user] = password
        return self

    @property
    def keycloak(self) -> KeycloakContainer | None:
        """The Keycloak the engine takes OAuth 2.0 tokens from, or None without
        :meth:`with_oauth2`."""
        return self._keycloak

    @property
    def source(self) -> Tm1EngineSource:
        """Where TM1 comes from."""
        return self._source

    @property
    def datastore(self) -> GenericContainer:
        """The MongoDB the engine keeps its metadata in, started and stopped with it."""
        return self._datastore

    @property
    def url(self) -> str:
        """The gateway as seen from the host, e.g. ``http://localhost:54321/``."""
        return f"http://{self.host}:{self.get_mapped_port(self.PORT)}/"

    @property
    def management_url(self) -> str:
        """The management API, which lists and creates instances, applications and databases, e.g.
        ``http://localhost:54321/manage/v1/``. It takes the root credentials as Basic authentication
        (:attr:`root_client_id`, :attr:`root_client_secret`)."""
        return self.url + "manage/v1/"

    @property
    def root_client_id(self) -> str:
        """The client ID of the engine's root credentials, for the management API."""
        return str((self._read_credentials().get("root") or {}).get("clientId", ""))

    @property
    def root_client_secret(self) -> str:
        """The client secret of the engine's root credentials."""
        return str((self._read_credentials().get("root") or {}).get("clientSecret", ""))

    @property
    def tm1_version(self) -> Tm1Version:
        """The version of TM1 the engine runs. Only available once started: a RuntimeError before."""
        if self._version is None:
            raise RuntimeError("The container isn't started yet")
        return self._version

    def databases(self) -> list[Tm1Database]:
        """The databases the engine has now, in every instance."""
        # Read again: create_database and drop_database change them
        self._credentials = None
        found = []
        for instance in self._read_credentials().get("instances") or []:
            for database in instance.get("databases") or []:
                found.append(Tm1Database(self, str(instance.get("name", "")), str(database.get("name", ""))))
        return found

    def database(self, instance_or_name: str, name: str | None = None) -> Tm1Database:
        """A database: ``database(name)`` of the instance ``tm1``, or ``database(instance, name)`` of a
        named instance. A ValueError if there's no such database."""
        instance, name = _instance_and_name(instance_or_name, name)
        wanted = Tm1Database(self, instance, name)
        if self.is_running:
            there = self.databases()
            if wanted not in there:
                raise ValueError(f"No database {instance}/{name} in this engine. It has {there}")
        elif f"{instance}/{name}" not in self._configured():
            raise ValueError(f"No database {instance}/{name} was added (with_database). Added: [{', '.join(self._configured())}]")
        return wanted

    def create_database(self, instance_or_name: str, name: str | None = None) -> Tm1Database:
        """Creates a database in the running engine, with the users of :meth:`with_user`:
        ``create_database(name)`` in the instance ``tm1``, or ``create_database(instance, name)`` in a
        named instance, which must exist. Takes about 10 seconds."""
        instance, name = _instance_and_name(instance_or_name, name)
        path = _require_instance(instance) + "/" + _require_name(name, "database")
        try:
            self._engine_command("database", "create", path, timeout=_CREATE_TIMEOUT)
        except RuntimeError:
            # Not left half-made, to fail the next create of the name; the create's error is the one to show
            try:
                self._engine_command("database", "drop", path)
            except Exception:  # noqa: BLE001 - whatever the drop says, the create's error stands
                self.logger.debug("Couldn't drop the half-made database %s", path, exc_info=True)
            finally:
                self._credentials = None
            raise
        self._credentials = None
        return Tm1Database(self, instance, name)

    def drop_database(self, database: Tm1Database) -> None:
        """Deletes a database of the running engine, with everything in it. Takes about 7 seconds."""
        self._engine_command("database", "drop", f"{database.instance}/{database.name}")
        self._credentials = None

    def _login(self, instance: str, user: str) -> Tm1Login:
        """A login to an instance, through its first application whose secret the image holds."""
        for found in self._read_credentials().get("instances") or []:
            if found.get("name") != instance:
                continue
            for application in found.get("applications") or []:
                # The image lists an application it doesn't hold the secret of, with a null secret
                if application.get("clientSecret"):
                    return Tm1Login(f"{self.url}{instance}/auth/v1/", instance, str(application.get("clientId", "")), str(application["clientSecret"]), user)
        raise RuntimeError(f"The instance {instance} has no application to log in through")

    def _oauth2_login(self, database: Tm1Database, user: str) -> Tm1Login:
        """A login through OAuth 2.0, as a user given a password with with_login."""
        keycloak = self._keycloak
        if keycloak is None:
            raise RuntimeError("This engine takes no OAuth 2.0 logins: give it a Keycloak with with_oauth2")
        # A name is one whatever its case, to Keycloak and to TM1
        for name, password in self._passwords.items():
            if name.lower() == str(user).lower():
                return Tm1Login._oauth2(keycloak, database, name, password)
        raise ValueError(f"{user} has no password to log in with: give one with with_login. Those who have: [{', '.join(self._passwords)}]")

    def _realm(self) -> str:
        """The realm the Keycloak imports: a client whose tokens carry the audience TM1 expects,
        and the users who have a password."""
        known = {name.lower() for name in self._users}
        for user in self._passwords:
            if user.lower() != "admin" and user.lower() not in known:
                raise RuntimeError(f"{user} has a password (with_login) but isn't a user of the engine: add them with with_user")
        audience = {
            "name": "tm1-audience",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": self.OAUTH2_CLIENT, "access.token.claim": "true", "id.token.claim": "false"},
        }
        client = {
            "clientId": self.OAUTH2_CLIENT,
            "publicClient": True,
            "directAccessGrantsEnabled": True,
            "standardFlowEnabled": False,
            "protocolMappers": [audience],
        }
        # Keycloak wants a profile that's complete before it gives a user a token. The address
        # isn't made from the name: Keycloak refuses some that a name may be
        users = [
            {
                "username": name,
                "enabled": True,
                "emailVerified": True,
                "email": f"user{number}@example.com",
                "firstName": name,
                "lastName": "Test",
                "credentials": [{"type": "password", "value": password, "temporary": False}],
            }
            for number, (name, password) in enumerate(self._passwords.items(), start=1)
        ]
        return json.dumps({"realm": self.OAUTH2_REALM, "enabled": True, "clients": [client], "users": users}, indent=2, ensure_ascii=False)

    def _configured(self) -> list[str]:
        """What's passed to the image as TM1_DATABASES: what was added, or the default."""
        return list(self._databases) or [f"{self.DEFAULT_INSTANCE}/{self.DEFAULT_DATABASE}"]

    def start(self) -> Self:
        """Starts the datastore, then the engine, and waits until TM1 is set up."""
        # Before anything starts: the datastore, which this depends on, is started ahead of
        # configure()
        self._join_network()
        try:
            return super().start()
        except BaseException:
            self.stop()
            raise

    def _join_network(self) -> None:
        """Puts the engine and its datastore on one network: the one given, or their own. They
        are on it since it was set (with_network); this holds it if either was moved since. A
        RuntimeError if the engine's Keycloak was moved to another since with_oauth2: TM1 couldn't
        reach its issuer."""
        self.with_network(self.network if self.network is not None else self._own_network)

    def configure(self) -> None:
        """Called before the container is created: passes the databases and the users to the image."""
        self._join_network()
        self.with_env("TM1_DATABASES", ",".join(self._configured()))
        if self._users:
            self.with_env("TM1_USERS", ",".join(":".join([name, *groups]) for name, groups in self._users.items()))

    def container_is_started(self) -> None:
        """Called once the gateway answers: waits for the image to set TM1 up, and reads its version."""
        self._await_setup()
        self._version = self._read_version()
        self.logger.info("TM1 %s is up at %s (%s), with %s", self._version, self.url, self._source.describe(), self.databases())

    def stop(self, force: bool = True, delete_volume: bool = True) -> None:
        """Stops the engine, then its datastore, and removes the network if the engine made it."""
        try:
            super().stop(force, delete_volume)
        finally:
            # Whatever removing the engine said: its datastore and its network aren't left behind
            try:
                # Not a dependent: what this depends on is started for it, and stopping is left to us
                self._datastore.stop(force, delete_volume)
            finally:
                self._credentials = None
                self._version = None
                # Only its own: a network it was given is whoever gave it's to remove. And not from
                # under a Keycloak that's still on it, which is the caller's to stop: Docker would
                # refuse, and the network then goes with the test session, as Keycloak may
                keycloak = self._keycloak
                if self.network is self._own_network and (keycloak is None or keycloak._container is None):
                    self._own_network.close()

    def _await_setup(self) -> None:
        """Waits until the image has created every instance, application, database and user, and
        every database is ready: what its health check reports."""
        import docker.errors

        container = self.get_wrapped_container()
        deadline = time.monotonic() + _STARTUP_TIMEOUT
        while True:
            container.reload()
            state = container.attrs.get("State") or {}
            if not state.get("Running"):
                # Both streams in the order they were written, as the HTTP wait's
                tail = container.logs(stdout=True, stderr=True, tail=30).decode(errors="replace").rstrip("\n")
                raise RuntimeError(f"The container exited while starting, with code {state.get('ExitCode')}. The end of its log:\n{tail}")
            try:
                healthy = container.exec_run(["tm1-engine", "health"]).exit_code == 0
            except docker.errors.APIError as e:
                container.reload()
                if not (container.attrs.get("State") or {}).get("Running"):
                    continue  # it exited meanwhile: say so, with its log
                raise RuntimeError("Couldn't ask the container whether TM1 is set up") from e
            if healthy:
                return
            if time.monotonic() > deadline:
                logs = container.logs(stdout=True, stderr=True).decode(errors="replace")
                raise RuntimeError(f"Timed out after {int(_STARTUP_TIMEOUT)} s waiting for TM1 to be set up. {logs}")
            time.sleep(1)

    def _engine_command(self, *arguments: str, timeout: int = _COMMAND_TIMEOUT) -> str:
        """Runs the image's tm1-engine command, and returns what it printed."""
        import docker.errors

        if not self.is_running:
            raise RuntimeError("The container isn't started yet")
        try:
            result = self.get_wrapped_container().exec_run(["timeout", str(timeout), "tm1-engine", *arguments], demux=True)
        except docker.errors.APIError as e:
            raise RuntimeError(f"Couldn't run tm1-engine {arguments[0]} in the container") from e
        # demux: (stdout, stderr), either None when the command wrote nothing to it
        output: Any = result.output
        stdout, stderr = (b"" if part is None else bytes(part) for part in output)
        if result.exit_code != 0:
            said = (stderr + stdout).decode(errors="replace").strip()
            raise RuntimeError(f"tm1-engine {' '.join(arguments)} failed (exit {result.exit_code}): {said}")
        return str(stdout.decode(errors="replace"))

    def _read_credentials(self) -> dict[str, Any]:
        """The engine's addresses and credentials, as the image reports them."""
        with self._credentials_lock:
            credentials = self._credentials
            if credentials is None:
                credentials = json.loads(self._engine_command("credentials", "--json"))
                if not isinstance(credentials, dict):
                    raise RuntimeError(f"tm1-engine credentials --json didn't print an object: {credentials}")
                self._credentials = credentials
            return credentials

    def _read_version(self) -> Tm1Version:
        """Reads the version from the first database, logged in as its administrator. Plain HTTP
        rather than tm1-client, so a client bug can't break the harness that tests the client."""
        databases = self.databases()
        if not databases:
            raise RuntimeError("The engine has no database to read its version from")
        first = databases[0]
        headers = first.login().authorize({})
        try:
            response = httpx2.get(first.rest_url + "Configuration/ProductVersion/$value", headers=headers, timeout=_REQUEST_TIMEOUT)
        except httpx2.HTTPError as e:
            raise RuntimeError(f"Couldn't read the TM1 version from {first.rest_url}") from e
        if response.status_code != 200:
            raise RuntimeError(f"TM1 didn't give its version (HTTP {response.status_code}): {response.text}")
        return Tm1Version.parse(response.text)

    def _mount_source(self) -> None:
        source = self._source
        if isinstance(source, Tm1EngineSource.InstallDir):
            self.with_volume_mapping(str(source.dir), _INSTALL_PATH, "ro")
        elif isinstance(source, Tm1EngineSource.InstallVolume):
            self.with_volume_mapping(source.name, _INSTALL_PATH, "rw")
        elif isinstance(source, Tm1EngineSource.Bundle):
            self.with_volume_mapping(str(source.dir), "/ibm", "ro")
            if source.file is not None:
                self.with_env("TM1_ENGINE_BUNDLE", source.file)
            self.with_volume_mapping(self.CACHE_VOLUME, "/tm1/cache", "rw")
        elif isinstance(source, Tm1EngineSource.BundleUrl):
            self.with_env("TM1_ENGINE_BUNDLE_URL", source.url)
            if source.sha256 is not None:
                self.with_env("TM1_ENGINE_BUNDLE_SHA256", source.sha256)
            self.with_volume_mapping(self.CACHE_VOLUME, "/tm1/cache", "rw")
        # Tm1EngineSource.PrivateImage: TM1 is already in the image


def _instance_and_name(instance_or_name: str, name: str | None) -> tuple[str, str]:
    """Java's overloads: (name), in the default instance, or (instance, name)."""
    if name is None:
        return Tm1EngineContainer.DEFAULT_INSTANCE, instance_or_name
    return instance_or_name, name


class Tm1Database:
    """A database of a :class:`Tm1EngineContainer`: what a test of TM1 v12 uses.

    An engine holds instances, and an instance holds databases. The REST API of one database is at
    :attr:`rest_url`; a user logs in to its instance (:meth:`login`), and the session serves every
    database there. Get one from the ``tm1_database`` fixture, or from the engine
    (:meth:`Tm1EngineContainer.database`)."""

    def __init__(self, engine: Tm1EngineContainer, instance: str, name: str) -> None:
        self._engine = engine
        self._instance = instance
        self._name = name

    @property
    def engine(self) -> Tm1EngineContainer:
        """The engine this database is in."""
        return self._engine

    @property
    def instance(self) -> str:
        """The instance this database is in; ``tm1`` unless one was named."""
        return self._instance

    @property
    def name(self) -> str:
        """The database's name."""
        return self._name

    @property
    def rest_url(self) -> str:
        """The REST API's service root as seen from the host, ending in ``/``, e.g.
        ``http://localhost:54321/tm1/api/v1/Databases('planning')/``."""
        quoted = self._name.replace("'", "''")
        return f"{self._engine.url}{self._instance}/api/v1/Databases('{quoted}')/"

    @property
    def version(self) -> Tm1Version:
        """The version of TM1 the engine runs. Only available once it has started."""
        return self._engine.tm1_version

    def login(self, user: str = "admin") -> Tm1Login:
        """A login as a user: ``admin``, the administrator every database has, unless one is named.
        TM1 makes a session for any name; a user who isn't in this database is then refused here
        (403)."""
        return self._engine._login(self._instance, user)

    def oauth2_login(self, user: str) -> Tm1Login:
        """A login through OAuth 2.0: as a user the engine's Keycloak knows the password of
        (:meth:`Tm1EngineContainer.with_login`). The login gets a token from Keycloak with that
        password and sends it to TM1, as a program acting for the user would. A RuntimeError if the
        engine has no :meth:`Tm1EngineContainer.with_oauth2`, and a ValueError if the user has no
        password."""
        return self._engine._oauth2_login(self, user)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Tm1Database) and self._engine is other._engine and self._instance == other._instance and self._name == other._name

    def __hash__(self) -> int:
        return hash((id(self._engine), self._instance, self._name))

    def __repr__(self) -> str:
        return f"Tm1Database[{self._instance}/{self._name}]"


class Tm1Login:
    """A user's login to a TM1 v12 instance, through one of the instance's applications
    ("service-to-service"): TM1 v12 has no passwords of its own, so the application's client ID and
    secret vouch for the user's name.

    A login belongs to an **instance**: its session serves every database there. Whether the user
    may do anything in a database depends on the user existing in it.

    Get one from :meth:`Tm1Database.login`, then either let it log in for you (:meth:`authorize` or
    :meth:`cookie`), or hand :attr:`client_id`, :attr:`client_secret` and :attr:`user` to a client
    that logs in itself.

    A login from :meth:`Tm1Database.oauth2_login` is through OAuth 2.0 instead
    (:attr:`is_oauth2`): its tokens come from the engine's Keycloak, for the user's
    :attr:`password` there, and TM1 takes one on any request (:meth:`authorize_with_token`)."""

    def __init__(self, auth_url: str, instance: str, client_id: str, client_secret: str, user: str) -> None:
        if user is None:
            raise ValueError("user must not be None")
        self._auth_url = auth_url
        self._instance = instance
        self._client_id = client_id
        self._client_secret = client_secret
        self._user = user
        self._cookie: str | None = None
        self._lock = threading.Lock()
        # An OAuth 2.0 login: where its tokens come from, the user's password there, and a
        # database's address to turn a token into a session at
        self._keycloak: KeycloakContainer | None = None
        self._password = ""
        self._database: Tm1Database | None = None

    @classmethod
    def _oauth2(cls, keycloak: KeycloakContainer, database: Tm1Database, user: str, password: str) -> Tm1Login:
        """A login through OAuth 2.0, with tokens from the engine's Keycloak."""
        # Addresses are asked of the database when they're needed: a login can be made before the
        # engine has started
        login = cls("", database.instance, Tm1EngineContainer.OAUTH2_CLIENT, "", user)
        login._keycloak = keycloak
        login._password = password
        login._database = database
        return login

    @property
    def is_oauth2(self) -> bool:
        """Whether this is a login through OAuth 2.0 (:meth:`Tm1Database.oauth2_login`), rather than
        through the instance's application."""
        return self._keycloak is not None

    @property
    def password(self) -> str:
        """The user's password at the identity provider, for an OAuth 2.0 login; empty otherwise,
        as TM1 v12 keeps none."""
        return self._password

    @property
    def token_url(self) -> str:
        """Where a token is asked for: the identity provider's token endpoint for an OAuth 2.0
        login, as seen from the host, and TM1's own otherwise."""
        keycloak = self._keycloak
        if keycloak is None:
            return self._auth_url + "token"
        return f"{keycloak.url}/realms/{Tm1EngineContainer.OAUTH2_REALM}/protocol/openid-connect/token"

    @property
    def instance(self) -> str:
        """The instance this login is for."""
        return self._instance

    @property
    def user(self) -> str:
        """The TM1 user this logs in as."""
        return self._user

    @property
    def client_id(self) -> str:
        """The application's client ID."""
        return self._client_id

    @property
    def client_secret(self) -> str:
        """The application's client secret."""
        return self._client_secret

    @property
    def auth_url(self) -> str:
        """Where a session or a token is asked for, as seen from the host, ending in ``/``, e.g.
        ``http://localhost:54321/tm1/auth/v1/``."""
        database = self._database
        return self._auth_url if database is None else f"{database.engine.url}{self._instance}/auth/v1/"

    def cookie(self) -> str:
        """The session cookie, ``TM1SessionId=...``, for a ``Cookie`` header. Logs in the first time,
        with the application's credentials as Basic authentication, or for an OAuth 2.0 login with
        a token from the provider, and keeps the session. A RuntimeError if TM1 refuses the login."""
        with self._lock:
            if self._cookie is None and self._database is not None:
                # TM1 gives a session to the first request that carries the provider's token
                anywhere = self._database.rest_url + "ActiveUser?$select=Name"
                try:
                    response = httpx2.get(anywhere, headers={"Authorization": "Bearer " + self.token()}, timeout=_REQUEST_TIMEOUT)
                except httpx2.HTTPError as e:
                    raise RuntimeError(f"Couldn't reach TM1 at {self.auth_url}") from e
                self._cookie = self._session_cookie(response)
            if self._cookie is None:
                basic = base64.b64encode(f"{self._client_id}:{self._client_secret}".encode()).decode("ascii")
                self._cookie = self.session("Basic " + basic)
            return self._cookie

    def token(self) -> str:
        """A bearer token for the application, a JWT, from TM1's token endpoint (the OAuth 2.0
        client-credentials grant). It names no user: send it as ``Authorization: Bearer`` with the
        user's name to get a session, as :meth:`session` does. A RuntimeError if TM1 refuses the
        application's credentials.

        For an OAuth 2.0 login, an access token for the user from the provider, by the password
        grant: a new one each time."""
        keycloak = self._keycloak
        if keycloak is not None:
            return keycloak._access_token(Tm1EngineContainer.OAUTH2_REALM, self._client_id, self._user, self._password)
        body = {"grant_type": "client_credentials", "client_id": self._client_id, "client_secret": self._client_secret}
        response = self._post("token", body, {})
        token = None
        try:
            answer = response.json()
            if isinstance(answer, dict) and isinstance(answer.get("access_token"), str):
                token = answer["access_token"]
        except ValueError:
            pass
        if response.status_code // 100 != 2 or not token:
            raise RuntimeError(f"TM1 gave no token for the application of instance '{self._instance}' (HTTP {response.status_code}): {response.text}")
        return str(token)

    def session(self, authorization: str) -> str:
        """Logs the user in with an ``Authorization`` header of your own, such as
        ``"Bearer " + login.token()``, and returns the new session's cookie, ``TM1SessionId=...``. Each
        call makes a session; :meth:`cookie` keeps one. A RuntimeError if TM1 refuses the login, or
        for an OAuth 2.0 login, which has no session endpoint to ask."""
        if self._keycloak is not None:
            raise RuntimeError(
                "An OAuth 2.0 login has no session to ask for: TM1 takes its token on any request (authorize_with_token), and cookie() keeps the session that gives"
            )
        if authorization is None:
            raise ValueError("authorization must not be None")
        return self._session_cookie(self._post("session", {"User": self._user}, {"Authorization": authorization}))

    def _session_cookie(self, response: httpx2.Response) -> str:
        cookies = (value.split(";", 1)[0].strip() for value in response.headers.get_list("set-cookie"))
        found = next((cookie for cookie in cookies if cookie.startswith("TM1SessionId=")), None)
        if response.status_code // 100 != 2 or found is None:
            raise RuntimeError(f"TM1 refused the login of '{self._user}' to instance '{self._instance}' (HTTP {response.status_code}): {response.text}")
        return found

    def authorize(self, request: _Request) -> _Request:
        """Adds this login's session cookie to a request: its headers, as a dict
        (``httpx2.get(url, headers=login.authorize({}))``), or an ``httpx2.Request``. Returns what it
        was given."""
        headers: MutableMapping[str, str] = getattr(request, "headers", request)  # type: ignore[assignment]
        headers["Cookie"] = self.cookie()
        return request

    def authorize_with_token(self, request: _Request) -> _Request:
        """Adds this login's token to a request as ``Authorization: Bearer``, as a program with a
        token of its own would send it: to its headers, as a dict, or to an ``httpx2.Request``. For
        an OAuth 2.0 login, a new token from the provider each time; TM1 answers with a session
        cookie. A RuntimeError for a login through the application, whose token TM1 takes only at
        its session endpoint (:meth:`session`)."""
        if self._keycloak is None:
            raise RuntimeError('TM1 takes its own token only where a session is asked for: use session("Bearer " + token()), or authorize for the cookie')
        headers: MutableMapping[str, str] = getattr(request, "headers", request)  # type: ignore[assignment]
        # Set, not added: headers used again carry the new token alone
        headers["Authorization"] = "Bearer " + self.token()
        return request

    def __repr__(self) -> str:
        # Never the secret
        return f"Tm1Login[{self._user} in {self._instance}]"

    def _post(self, path: str, body: dict[str, str], headers: dict[str, str]) -> httpx2.Response:
        try:
            return httpx2.post(
                self.auth_url + path,
                content=json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode(),
                headers={**headers, "Content-Type": "application/json"},
                timeout=_REQUEST_TIMEOUT,
            )
        except httpx2.HTTPError as e:
            raise RuntimeError(f"Couldn't reach TM1 at {self.auth_url}") from e
