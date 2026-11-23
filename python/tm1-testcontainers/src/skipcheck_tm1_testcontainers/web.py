"""TM1 Web in Docker: Java's ``Tm1WebContainer``."""

from __future__ import annotations

import atexit
import threading
from typing import Any, ClassVar

from typing_extensions import Self

from ._container import GenericContainer, Network, reuse_enabled, share_network, wait_for_http
from .sources import Tm1WebSource
from .tm1 import Tm1AdminServerContainer, Tm1ServerContainer, default_image

__all__ = ["Tm1WebContainer"]

NO_SOURCE_MESSAGE = (
    "No TM1 Web source configured. Set one of TM1_WEB_PRIVATE_IMAGE, TM1_WEB_HOME_DIR, TM1_WEB_INSTALL_VOLUME, "
    "TM1_WEB_BUNDLE_DIR or TM1_WEB_BUNDLE_URL, or put a tm1web bundle in TM1_BUNDLE_DIR (as an environment variable "
    "or system property) to run tests against TM1 Web."
)
"""Java's ``RequiresTm1Web.NO_SOURCE_MESSAGE``."""

_INSTALL_PATH = "/opt/ibm/cognos/tm1web"
_STARTUP_TIMEOUT = 5 * 60.0


class Tm1WebContainer(GenericContainer):
    """TM1 Web in Docker, from the ``tm1-web`` image and a :class:`Tm1WebSource`, listing the
    servers of a :class:`Tm1ServerContainer`'s Admin Server (:meth:`with_tm1_server`) or a shared
    one (:meth:`with_admin_server`)."""

    DEFAULT_IMAGE: ClassVar[str] = "ghcr.io/ajmyers/tm1-web"
    WEB_IMAGE: ClassVar[str] = "TM1_WEB_IMAGE"
    HTTP_PORT: ClassVar[int] = 9510

    _shared: ClassVar[Tm1WebContainer | None] = None
    _shared_lock: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, source: Tm1WebSource, image: str | None = None) -> None:
        if source is None:
            raise ValueError("source must not be None")
        super().__init__(image or default_image(source, self.DEFAULT_IMAGE, self.WEB_IMAGE))
        self._source = source
        self._tm1: Tm1ServerContainer | None = None
        self._admin_server: Tm1AdminServerContainer | None = None
        self.with_exposed_ports(self.HTTP_PORT)
        # TM1 Web's bundled Java only exists for x86-64. On Apple Silicon this runs under Rosetta.
        self.with_platform("linux/amd64")
        # The login page, as the image's healthcheck uses: 200 once both apps are up.
        self.waiting_for(wait_for_http("/tm1web/", self.HTTP_PORT, 200, timeout=_STARTUP_TIMEOUT))
        if isinstance(source, Tm1WebSource.InstallDir):
            self.with_volume_mapping(str(source.dir), _INSTALL_PATH, "ro")
        elif isinstance(source, Tm1WebSource.InstallVolume):
            self.with_volume_mapping(source.name, _INSTALL_PATH, "rw")
        elif isinstance(source, Tm1WebSource.Bundle):
            self.with_volume_mapping(str(source.dir), "/ibm", "ro")
            if source.file is not None:
                self.with_env("TM1_WEB_BUNDLE", source.file)
            self.with_volume_mapping(Tm1ServerContainer.CACHE_VOLUME, "/tm1-web/cache", "rw")
        elif isinstance(source, Tm1WebSource.BundleUrl):
            self.with_env("TM1_WEB_BUNDLE_URL", source.url)
            if source.sha256 is not None:
                self.with_env("TM1_WEB_BUNDLE_SHA256", source.sha256)
            self.with_volume_mapping(Tm1ServerContainer.CACHE_VOLUME, "/tm1-web/cache", "rw")
        # Tm1WebSource.PrivateImage: TM1 Web is already in the image

    @classmethod
    def shared(cls) -> Tm1WebContainer:
        """One TM1 Web for every test in this process, listing :meth:`Tm1ServerContainer.shared`."""
        with cls._shared_lock:
            if Tm1WebContainer._shared is None:
                source = Tm1WebSource.discover()
                if source is None:
                    raise RuntimeError(NO_SOURCE_MESSAGE)
                container = Tm1WebContainer(source).with_tm1_server(Tm1ServerContainer.shared()).with_reuse(True)
                container.start()
                Tm1WebContainer._shared = container
                if not reuse_enabled():
                    atexit.register(container.stop)
            return Tm1WebContainer._shared

    def with_tm1_server(self, tm1: Tm1ServerContainer) -> Self:
        """Lists the servers of this TM1 server's Admin Server, started first."""
        if tm1 is None:
            raise ValueError("tm1 must not be None")
        self._tm1 = tm1
        if not tm1.is_running and tm1.network is None:
            tm1.with_network(Network.new_network())
        self.depends_on(tm1)
        return self

    def with_admin_server(self, admin: Tm1AdminServerContainer) -> Self:
        """Lists the servers of a shared Admin Server instead."""
        if admin is None:
            raise ValueError("admin must not be None")
        self._admin_server = admin
        share_network(self, admin)
        self.depends_on(admin)
        return self

    @property
    def admin_server(self) -> Tm1AdminServerContainer | None:
        """The Admin Server set with :meth:`with_admin_server`, or None if none was set."""
        return self._admin_server

    @property
    def source(self) -> Tm1WebSource:
        """Where TM1 Web comes from."""
        return self._source

    @property
    def tm1_server(self) -> Tm1ServerContainer | None:
        """The TM1 server set with :meth:`with_tm1_server`, or None if none was set."""
        return self._tm1

    @property
    def url(self) -> str:
        """TM1 Web's address as seen from the host, such as ``http://localhost:54321/tm1web/``,
        ending in ``/``."""
        return f"http://{self.host}:{self.get_mapped_port(self.HTTP_PORT)}/tm1web/"

    @property
    def admin_host(self) -> str:
        """The Admin Server's host, as TM1 Web's logon takes it."""
        host = self.env.get("TM1_ADMIN_HOST")
        if host is None:
            raise RuntimeError("No Admin Server yet: set a TM1 server with with_tm1_server and start")
        return host

    def configure(self) -> None:
        """Called by testcontainers before the container is created: points TM1 Web at the Admin
        Server it lists servers from."""
        if self._admin_server is not None:
            self.with_env("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS)
            return
        tm1 = self._tm1
        if tm1 is None:
            return
        if not tm1.is_running:
            raise RuntimeError("The TM1 server isn't running; it should have started first")
        network = tm1.network
        address = _address_on(tm1, network)
        if network is not None:
            self.with_network(network)
        else:
            # TM1 registers with the Admin Server as http://<its hostname>:8010, and TM1 Web's
            # evalservice (universal reports) calls that URL. Docker's DNS resolves hostnames on
            # networks it creates, but not on the default bridge, so name it here.
            self.with_extra_host(tm1.container_info["Config"]["Hostname"], address)
        self.with_env("TM1_ADMIN_HOST", address)

    def container_is_started(self) -> None:
        """Called once the container is up: logs where it is and what it came from."""
        self.logger.info(
            "TM1 Web is up at %s (%s), listing servers from the Admin Server at %s",
            self.url,
            self._source.describe(),
            self.env.get("TM1_ADMIN_HOST", "(none)"),
        )


def _address_on(container: GenericContainer, network: Any, what: str = "TM1 Web") -> str:
    return _network_of(container, network, what)["IPAddress"]


def _network_of(container: GenericContainer, network: Any, what: str) -> dict[str, Any]:
    """The container's endpoint on a network (Docker's default bridge without one)."""
    networks: dict[str, Any] = container.container_info["NetworkSettings"]["Networks"] or {}
    found = None
    if network is not None:
        found = next((n for n in networks.values() if n.get("NetworkID") == network.id), None)
    else:
        found = networks.get("bridge")
    if not found or not found.get("IPAddress"):
        raise RuntimeError(
            f"{what} can't reach the TM1 server: it's on {sorted(networks)}. Give the TM1 server a Network (with_network), or leave it on Docker's default bridge network."
        )
    return dict(found)
