# SPDX-License-Identifier: Apache-2.0

"""TM1 Server and the TM1 Admin Server in Docker: Java's ``Tm1ServerContainer``,
``Tm1AdminServerContainer`` and ``Tm1Seed``."""

from __future__ import annotations

import atexit
import base64
import enum
import threading
from importlib import metadata
from pathlib import Path
from typing import TYPE_CHECKING, Any, ClassVar, Self

import httpx2

from skipcheck_tm1 import Tm1Version

from ._container import GenericContainer, reuse_enabled, share_network, wait_for_http
from .sources import Tm1Source, setting

if TYPE_CHECKING:
    from .cognos import CognosContainer, Namespace

__all__ = ["Tm1AdminServerContainer", "Tm1Seed", "Tm1ServerContainer"]

_INSTALL_PATH = "/opt/ibm/cognos/tm1_64"
_STARTUP_TIMEOUT = 5 * 60.0
_REQUEST_TIMEOUT = 30.0

NO_SOURCE_MESSAGE = (
    "No TM1 source configured. Set one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or "
    "TM1_BUNDLE_URL (as an environment variable or system property) to run tests against TM1."
)
"""Java's ``RequiresTm1.NO_SOURCE_MESSAGE``."""


class Tm1Seed(enum.Enum):
    """The database a new TM1 server starts with (its ``value``, the image's ``TM1_SEED``), and
    its login."""

    EMPTY = "empty"
    """A new, empty database: ``admin`` with no password."""
    SDATA = "sdata"
    """IBM's SData sample: ``admin`` / ``apple``."""

    @property
    def username(self) -> str:
        """The user the seed's database logs in as."""
        return _SEED_LOGINS[self.value][0]

    @property
    def password(self) -> str:
        """That user's password, empty for none."""
        return _SEED_LOGINS[self.value][1]


_SEED_LOGINS = {"empty": ("admin", ""), "sdata": ("admin", "apple")}


def image_tag(library_version: str) -> str:
    """The image tag for a library version: ``edge`` for snapshots and development releases."""
    if library_version.endswith("-SNAPSHOT") or ".dev" in library_version:
        return "edge"
    return library_version


def library_version() -> str:
    try:
        return metadata.version("tm1-testcontainers")
    except metadata.PackageNotFoundError:
        return "0.0.0.dev0"


def default_image(source: Any, default: str, override_setting: str) -> str:
    """The image for a source, as Java's ``defaultImage``: the source's own, ``override_setting``,
    or ``default`` tagged with the library version."""
    image = getattr(source, "image", None)
    if image is not None and type(source).__name__ == "PrivateImage":
        return str(image)
    override = setting(override_setting)
    if override is not None and override.strip():
        return override.strip()
    return f"{default}:{image_tag(library_version())}"


def mount_source(source: Tm1Source, container: GenericContainer) -> None:
    """Mounts TM1 from a source, as the ``tm1-server`` image takes it."""
    if isinstance(source, Tm1Source.InstallDir):
        container.with_volume_mapping(str(source.dir), _INSTALL_PATH, "ro")
    elif isinstance(source, Tm1Source.InstallVolume):
        container.with_volume_mapping(source.name, _INSTALL_PATH, "rw")
    elif isinstance(source, Tm1Source.Bundle):
        container.with_volume_mapping(str(source.dir), "/ibm", "ro")
        if source.file is not None:
            container.with_env("TM1_BUNDLE", source.file)
        container.with_volume_mapping(Tm1ServerContainer.CACHE_VOLUME, "/tm1/cache", "rw")
    elif isinstance(source, Tm1Source.BundleUrl):
        container.with_env("TM1_BUNDLE_URL", source.url)
        if source.sha256 is not None:
            container.with_env("TM1_BUNDLE_SHA256", source.sha256)
        container.with_volume_mapping(Tm1ServerContainer.CACHE_VOLUME, "/tm1/cache", "rw")
    # Tm1Source.PrivateImage: TM1 is already in the image


class Tm1ServerContainer(GenericContainer):
    """A TM1 server in Docker, from the ``tm1-server`` image and a :class:`Tm1Source`.

    It starts with an empty database (:attr:`Tm1Seed.EMPTY`: ``admin``, no password), and is up
    once ``$metadata`` answers; it then reads the server's version (:attr:`tm1_version`)."""

    DEFAULT_IMAGE: ClassVar[str] = "ghcr.io/ajmyers/tm1-server"
    SERVER_IMAGE: ClassVar[str] = "TM1_SERVER_IMAGE"
    REST_PORT: ClassVar[int] = 8010
    CACHE_VOLUME: ClassVar[str] = "tm1-testcontainers-cache"

    _shared: ClassVar[Tm1ServerContainer | None] = None
    _shared_lock: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, source: Tm1Source, image: str | None = None) -> None:
        if source is None:
            raise ValueError("source must not be None")
        super().__init__(image or Tm1ServerContainer.default_image(source))
        self._source = source
        self._username = Tm1Seed.EMPTY.username
        self._password = Tm1Seed.EMPTY.password
        self._expected_version: Tm1Version | None = None
        self._version: Tm1Version | None = None
        self._cognos: CognosContainer | None = None
        self._cam_admins: list[str] = []
        self._cam_namespace: Namespace | None = None
        self._admin_server: Tm1AdminServerContainer | None = None
        # The engine that passes logins to this server: one, as they share a network
        self._pass_through_for: Any = None
        self.with_exposed_ports(self.REST_PORT)
        self.with_env("TM1_SEED", Tm1Seed.EMPTY.value)
        # TM1 Server only exists for x86-64. On Apple Silicon this runs under Rosetta.
        self.with_platform("linux/amd64")
        # TM1 serves $metadata without a login, so a 200 means the REST API is really up.
        # This is also the image's healthcheck.
        self.waiting_for(wait_for_http("/api/v1/$metadata", self.REST_PORT, 200, timeout=_STARTUP_TIMEOUT))
        mount_source(source, self)

    @staticmethod
    def default_image(source: Tm1Source) -> str:
        """The image a server from this source runs in: a private image as it is, else
        ``TM1_SERVER_IMAGE`` if set, else the published image at the tag for this library's version."""
        return default_image(source, Tm1ServerContainer.DEFAULT_IMAGE, Tm1ServerContainer.SERVER_IMAGE)

    @staticmethod
    def image_tag(library_version: str) -> str:
        """The image tag for a library version: ``edge`` for snapshots and development releases."""
        return image_tag(library_version)

    @staticmethod
    def library_version() -> str:
        """This library's version, which picks the image's tag."""
        return library_version()

    @classmethod
    def shared(cls) -> Tm1ServerContainer:
        """One server for every test in this process, from the settings (:meth:`Tm1Source.discover`),
        started on first use and marked for reuse. It's removed when the process ends, unless
        reuse is enabled (``testcontainers.reuse.enable=true``), when a later run reuses it."""
        with cls._shared_lock:
            if Tm1ServerContainer._shared is None:
                source = Tm1Source.discover()
                if source is None:
                    raise RuntimeError(NO_SOURCE_MESSAGE)
                container = Tm1ServerContainer(source).with_reuse(True)
                container.start()
                Tm1ServerContainer._shared = container
                if not reuse_enabled():
                    atexit.register(container.stop)
            return Tm1ServerContainer._shared

    def with_seed(self, seed: Tm1Seed) -> Self:
        """Starts from a seed database instead of an empty one, and logs in as its user."""
        self.with_env("TM1_SEED", seed.value)
        return self.with_credentials(seed.username, seed.password)

    def with_data(self, data_dir: Path | str) -> Self:
        """Copies a database folder in, as the server's data directory."""
        data_dir = Path(data_dir)
        if not data_dir.is_dir():
            raise ValueError(f"Not a folder: {data_dir}")
        return self.with_copy_into_container(data_dir, "/tm1/data/")

    def with_credentials(self, username: str, password: str) -> Self:
        """The login for the database the server starts with."""
        if username is None or password is None:
            raise ValueError("username and password must not be None")
        self._username = username
        self._password = password
        return self

    def with_cam_security(self, cognos: CognosContainer, *admins: Any) -> Self:
        """Runs TM1 in CAM security (mode 5) against a :class:`CognosContainer`, with the named
        test users as administrators, logging in as the first. ``with_cam_security(cognos,
        Namespace.OIDC, "admin")`` uses Keycloak's OIDC namespace instead of LDAP."""
        from .cognos import CognosContainer, Namespace

        namespace = Namespace.LDAP
        if admins and isinstance(admins[0], Namespace):
            namespace, admins = admins[0], admins[1:]
        if not isinstance(cognos, CognosContainer):
            raise ValueError("cognos must be a CognosContainer")
        if not admins:
            raise ValueError("Name at least one of the Cognos test users as a TM1 administrator")
        self._cam_namespace = namespace
        self._cognos = cognos
        self._cam_admins = [str(a) for a in admins]
        share_network(self, cognos)
        self.depends_on(cognos)
        return self

    def with_admin_server(self, admin: Tm1AdminServerContainer) -> Self:
        """Registers with a shared Admin Server instead of the server's own."""
        if admin is None:
            raise ValueError("admin must not be None")
        self._admin_server = admin
        share_network(self, admin)
        self.depends_on(admin)
        self.with_env("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS)
        return self

    def with_expected_version(self, version: Tm1Version | str) -> Self:
        """Fails to start unless the server reports this version."""
        self._expected_version = Tm1Version.parse(version) if isinstance(version, str) else version
        return self

    @property
    def admin_server(self) -> Tm1AdminServerContainer | None:
        """The Admin Server set with :meth:`with_admin_server`, or None if this container runs its
        own."""
        return self._admin_server

    @property
    def cognos(self) -> CognosContainer | None:
        """The Cognos set with :meth:`with_cam_security`, or None if TM1 uses its own (native)
        security."""
        return self._cognos

    @property
    def cam_namespace(self) -> Namespace | None:
        """The Cognos namespace TM1's users log on through, set with :meth:`with_cam_security`, or
        None if TM1 uses its own (native) security."""
        return None if self._cognos is None else self._cam_namespace

    def _in_cam_security(self) -> bool:
        """Whether the server ends up in CAM security: set with :meth:`with_cam_security`, unless a
        ``tm1s.cfg`` parameter given as ``TM1_CFG_IntegratedSecurityMode`` says another mode than 4
        or 5. The image applies those last, and takes a parameter's name in any case."""
        if self._cognos is None:
            return False
        mode = self._cfg("IntegratedSecurityMode")
        return mode is None or mode.strip() in ("4", "5")

    def _uses_ssl(self) -> bool:
        """Whether a ``tm1s.cfg`` parameter given as ``TM1_CFG_UseSSL`` turns TLS on."""
        ssl = self._cfg("UseSSL")
        return ssl is not None and ssl.strip().lower() not in ("f", "false")

    def _cfg(self, parameter: str) -> str | None:
        """A tm1s.cfg parameter given in the environment, whatever the case of its name."""
        wanted = f"TM1_CFG_{parameter}".lower()
        for name, value in self.env.items():
            if name.lower() == wanted:
                return str(value)
        return None

    @property
    def authorization(self) -> str:
        """The ``Authorization`` header to log in with: ``Basic``, or ``CAMNamespace`` in CAM
        security."""
        if self._in_cam_security() and self._cam_namespace is not None:
            from .cognos import CognosContainer

            return CognosContainer.cam_authorization_for(self._username, self._password, self._cam_namespace.id)
        return "Basic " + base64.b64encode(f"{self._username}:{self._password}".encode()).decode("ascii")

    @property
    def source(self) -> Tm1Source:
        """Where TM1 comes from."""
        return self._source

    @property
    def rest_url(self) -> str:
        """The REST API's root, such as ``http://localhost:54321/api/v1/``."""
        return f"http://{self.host}:{self.get_mapped_port(self.REST_PORT)}/api/v1/"

    @property
    def username(self) -> str:
        """The TM1 user name tests should log in as; ``admin`` unless set."""
        return self._username

    @property
    def password(self) -> str:
        """The password for :attr:`username`; empty unless set."""
        return self._password

    @property
    def tm1_version(self) -> Tm1Version:
        """The version the running server reports. Only available once started: a RuntimeError
        before."""
        if self._version is None:
            raise RuntimeError("The container isn't started yet")
        return self._version

    def configure(self) -> None:
        """Called by testcontainers before the container is created: in CAM security, passes
        Cognos's address, the namespace and the administrators to the image."""
        cognos = self._cognos
        if cognos is None or self._cam_namespace is None:
            return
        from .cognos import Namespace

        # Here rather than in with_cam_security, so users added to Cognos later still count
        admin = cognos.get_user(self._cam_admins[0])
        for name in self._cam_admins:
            cognos.get_user(name)
        if self._in_cam_security():
            # A tm1s.cfg parameter may take the server out of CAM security again: it then keeps
            # the login it has, its seed's
            self.with_credentials(admin.name, admin.password)
        self.with_env("TM1_CAM_URI", cognos.network_url)
        if self._cam_namespace is Namespace.OIDC and cognos.keycloak is None:
            raise RuntimeError("CAM security through OIDC needs the Cognos to have with_keycloak")
        self.with_env("TM1_CAM_NAMESPACE", self._cam_namespace.id)
        self.with_env("TM1_CAM_NAMESPACE_TYPE", self._cam_namespace.type)
        self.with_env("TM1_CAM_ADMINS", " ".join(self._cam_admins))
        if cognos.groups:
            self.with_env("TM1_CAM_GROUPS", " ".join(cognos.groups))

    def container_is_started(self) -> None:
        """Called once the container is up: reads the server's version, and fails the start if it
        isn't the expected one."""
        self._version = self._read_version()
        if self._expected_version is not None and self._expected_version != self._version:
            raise RuntimeError(f"Expected TM1 {self._expected_version} but the server from {self._source.describe()} reports {self._version}")
        self.logger.info("TM1 %s is up at %s (%s)", self._version, self.rest_url, self._source.describe())

    def _read_version(self) -> Tm1Version:
        url = self.rest_url
        try:
            with httpx2.Client(timeout=_REQUEST_TIMEOUT) as http:
                response = http.get(url + "Configuration/ProductVersion/$value", headers={"Authorization": self.authorization})
                if response.status_code != 200:
                    raise RuntimeError(
                        f"TM1 refused the login '{self._username}' (HTTP {response.status_code}): {response.text}. Set the database's login with with_credentials."
                    )
                session = response.cookies.get("TM1SessionId")
                if session is not None:
                    self._close_session(http, session)
                return Tm1Version.parse(response.text)
        except httpx2.HTTPError as e:
            raise RuntimeError(f"Couldn't read the TM1 version from {url}") from e

    def _close_session(self, http: httpx2.Client, session: str) -> None:
        try:
            # TM1 rejects actions without a JSON content type, even with no parameters
            http.post(
                self.rest_url + "ActiveSession/tm1.Close",
                headers={"Cookie": f"TM1SessionId={session}", "Content-Type": "application/json"},
                content="{}",
            )
        except httpx2.HTTPError:
            self.logger.debug("Couldn't close the version-check session; TM1 will time it out", exc_info=True)


class Tm1AdminServerContainer(GenericContainer):
    """A TM1 Admin Server that several :class:`Tm1ServerContainer` register with
    (:meth:`Tm1ServerContainer.with_admin_server`), from the same image in ``admin`` mode."""

    REST_PORT: ClassVar[int] = 5895
    NETWORK_ALIAS: ClassVar[str] = "tm1-admin"

    def __init__(self, source: Tm1Source, image: str | None = None) -> None:
        if source is None:
            raise ValueError("source must not be None")
        super().__init__(image or Tm1ServerContainer.default_image(source))
        self._source = source
        self.with_command(["admin"])
        self.with_exposed_ports(self.REST_PORT)
        self.with_network_aliases(self.NETWORK_ALIAS)
        # TM1 only exists for x86-64. On Apple Silicon this runs under Rosetta.
        self.with_platform("linux/amd64")
        # The image's healthcheck in admin mode: the server list answers without a login
        self.waiting_for(wait_for_http("/api/v1/Servers", self.REST_PORT, 200, timeout=_STARTUP_TIMEOUT))
        mount_source(source, self)

    @property
    def source(self) -> Tm1Source:
        """Where TM1 comes from."""
        return self._source

    @property
    def rest_url(self) -> str:
        """The Admin Server's REST API as seen from the host, such as
        ``http://localhost:54321/api/v1/``, ending in ``/``. ``Servers`` lists the registered TM1
        servers."""
        return f"http://{self.host}:{self.get_mapped_port(self.REST_PORT)}/api/v1/"

    def container_is_started(self) -> None:
        """Called once the container is up: logs where it is and what it came from."""
        self.logger.info("TM1 Admin Server is up at %s (%s)", self.rest_url, self._source.describe())
