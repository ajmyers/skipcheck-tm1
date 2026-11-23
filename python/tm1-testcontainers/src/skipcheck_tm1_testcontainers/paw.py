# SPDX-License-Identifier: Apache-2.0

"""Planning Analytics Workspace Local in Docker: Java's ``PawContainer`` and ``PawSession``.

IBM ships PAW Local as Docker images with compose files and scripts, in
``ipa_workspace_local_<release>.<build>.zip``. :class:`PawContainer` runs ``docker/paw/paw-local.sh``
(shipped in this package) in a small runner container, which brings those up as they are.
"""

from __future__ import annotations

import atexit
import hashlib
import io
import logging
import socket
import tarfile
import threading
import time
from http.cookies import SimpleCookie
from importlib import resources
from pathlib import Path
from typing import Any, ClassVar, Self
from urllib.parse import quote, quote_plus

import httpx2
from testcontainers.core.config import testcontainers_config as tc_config
from testcontainers.core.docker_client import DockerClient

from .sources import PawSource, setting
from .tm1 import Tm1AdminServerContainer, Tm1ServerContainer
from .web import _network_of

__all__ = ["PawContainer", "PawSession"]

NO_SOURCE_MESSAGE = (
    "No Planning Analytics Workspace source configured. Set PAW_ZIP to IBM's ipa_workspace_local_*.zip, or "
    "PAW_BUNDLE_DIR to its folder (as an environment variable or system property), to run tests against PAW."
)
"""Java's ``RequiresPaw.NO_SOURCE_MESSAGE``."""

NO_LICENSE_MESSAGE = (
    "IBM's Planning Analytics Workspace license hasn't been accepted. Read it (licenses/LA_en in the PAW Local zip), "
    "then set PAW_ACCEPT_LICENSE=true (as an environment variable or system property) to run tests against PAW."
)
"""Java's ``RequiresPaw.NO_LICENSE_MESSAGE``."""

_COMPOSE_PROJECT = "paw"
_RUNNER_BASE = "docker:cli"
_DEFAULT_STARTUP_TIMEOUT = 20 * 60.0
_READY_TIMEOUT = 10 * 60.0
_log = logging.getLogger("skipcheck_tm1_testcontainers.paw")


def script() -> bytes:
    """``paw-local.sh``: in the package, or in the repository for an editable install."""
    packaged = resources.files("skipcheck_tm1_testcontainers").joinpath("paw-local.sh")
    if packaged.is_file():
        return packaged.read_bytes()
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "docker" / "paw" / "paw-local.sh"
        if candidate.is_file():
            return candidate.read_bytes()
    raise RuntimeError("Missing paw-local.sh in the tm1-testcontainers package")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("", 0))
        return int(s.getsockname()[1])


class PawContainer:
    """PAW Local, logging users on to a :class:`Tm1ServerContainer` with TM1's own security.

    Not one container but IBM's compose project (``paw``), started by ``paw-local.sh`` and
    joined to the TM1 server's network. Starting needs IBM's license accepted:
    ``PAW_ACCEPT_LICENSE=true``, or :meth:`accept_license`.

    When the TM1 server runs in Cognos (CAM) security (:meth:`Tm1ServerContainer.with_cam_security`),
    PAW does too (``PAAuthMode=cam``), as pa-setup wires it: users log on through Cognos, in the TM1
    server's namespace. PAW checks logons with Cognos's dispatcher on the network
    (``http://cognos:9300``), and sends browsers to Cognos's address on the host to log on. Tests
    log on with a Cognos test user's name and password, which PAW checks with Cognos. A browser that
    logs on through Cognos isn't sent back to PAW, though: Cognos hands logons back only to the
    addresses in IBM's ``pmhub.html``, which :class:`CognosContainer` doesn't install (pa-setup
    does, with ``COGNOS_PMHUB_URLS``)."""

    ACCEPT_LICENSE: ClassVar[str] = "PAW_ACCEPT_LICENSE"
    HOME_VOLUME: ClassVar[str] = "tm1-paw-home"
    PAW_NETWORK: ClassVar[str] = "paw_default"
    ADMIN_PORT: ClassVar[int] = 5895

    _shared: ClassVar[PawContainer | None] = None
    _shared_failure: ClassVar[str | None] = None
    _shared_lock: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, source: PawSource) -> None:
        if source is None:
            raise ValueError("source must not be None")
        self._source = source
        self._settings: dict[str, str] = {}
        self._tm1: Tm1ServerContainer | None = None
        self._admin_server: Tm1AdminServerContainer | None = None
        self._license_accepted = PawContainer.license_accepted_by_setting()
        self._startup_timeout = _DEFAULT_STARTUP_TIMEOUT
        self._http_port = 0
        self._running = False
        self._lock = threading.RLock()

    @classmethod
    def shared(cls) -> PawContainer:
        """One PAW for every test in this process, logging on to :meth:`Tm1ServerContainer.shared`.
        A failed start isn't retried: later calls fail at once."""
        with cls._shared_lock:
            if PawContainer._shared_failure is not None:
                raise RuntimeError(f"The shared PAW failed to start earlier in this process: {PawContainer._shared_failure}")
            if PawContainer._shared is None:
                try:
                    source = PawSource.discover()
                    if source is None:
                        raise RuntimeError(NO_SOURCE_MESSAGE)
                    paw = PawContainer(source).with_tm1_server(Tm1ServerContainer.shared())
                    paw.start()
                    PawContainer._shared = paw
                    atexit.register(paw.stop)
                except Exception as e:
                    PawContainer._shared_failure = str(e)
                    raise
            return PawContainer._shared

    def with_tm1_server(self, tm1: Tm1ServerContainer) -> Self:
        """Logs PAW users on to ``tm1``, and lists servers from its Admin Server. Starts ``tm1``
        first if it isn't running."""
        if tm1 is None:
            raise ValueError("tm1 must not be None")
        self._tm1 = tm1
        return self

    def with_admin_server(self, admin: Tm1AdminServerContainer) -> Self:
        """Lists servers from a separate Admin Server (:meth:`Tm1ServerContainer.with_admin_server`)
        instead of the TM1 server's own. Users still log on through that TM1 server, which must be
        registered with this Admin Server and on its network."""
        if admin is None:
            raise ValueError("admin must not be None")
        self._admin_server = admin
        return self

    def accept_license(self) -> Self:
        """Accepts IBM's PAW license (``licenses/LA_en`` in the zip), as ``PAW_ACCEPT_LICENSE=true``."""
        self._license_accepted = True
        return self

    def _is_license_accepted(self) -> bool:
        """Whether :meth:`accept_license` or ``PAW_ACCEPT_LICENSE=true`` accepted IBM's license."""
        return self._license_accepted

    def with_setting(self, name: str, value: str) -> Self:
        """A ``paw-local.sh`` setting (``PAW_…``)."""
        if not name.startswith("PAW_"):
            raise ValueError(f"Not a paw-local.sh setting: {name}")
        if value is None:
            raise ValueError("value must not be None")
        self._settings[name] = value
        return self

    def with_startup_timeout(self, timeout: float) -> Self:
        """Seconds to wait for ``paw-local.sh up`` (default 20 minutes: the first start loads IBM's images)."""
        self._startup_timeout = float(timeout)
        return self

    @property
    def source(self) -> PawSource:
        """Where PAW comes from."""
        return self._source

    @property
    def tm1_server(self) -> Tm1ServerContainer | None:
        """The TM1 server set with :meth:`with_tm1_server`, or None if none was set."""
        return self._tm1

    @property
    def is_running(self) -> bool:
        """Whether PAW is started: true between :meth:`start` and :meth:`stop`."""
        return self._running

    @property
    def url(self) -> str:
        """PAW's address as seen from the host, such as ``http://localhost:54321/``, ending in
        ``/``. A RuntimeError if PAW isn't started."""
        if not self._running:
            raise RuntimeError("PAW isn't started")
        return f"http://{DockerClient().host()}:{self._http_port}/"

    def start(self) -> None:
        """Starts the TM1 server if needed, then PAW, and waits until a TM1 call through PAW works.
        A RuntimeError if the license isn't accepted or no TM1 server is set."""
        with self._lock:
            if self._running:
                return
            if not self._license_accepted:
                raise RuntimeError(NO_LICENSE_MESSAGE + " Or call accept_license().")
            server = self._tm1
            if server is None:
                raise RuntimeError("No TM1 server: set one with with_tm1_server")
            if not server.is_running:
                server.start()
            docker = DockerClient().client
            tm1_network = _network_of(server, server.network, "PAW")
            tm1_address = tm1_network["IPAddress"]
            admin_address = tm1_address
            admin = self._admin_server
            if admin is not None:
                if not admin.is_running:
                    admin.start()
                admin_network = _network_of(admin, admin.network, "PAW")
                if admin_network.get("NetworkID") != tm1_network.get("NetworkID"):
                    raise RuntimeError("PAW needs the Admin Server and the TM1 server on one network")
                admin_address = admin_network["IPAddress"]
            cognos = server.cognos
            self._http_port = _free_port()
            env = self._script_settings(tm1_address, admin_address, None if cognos is None else cognos.url)
            env["PAW_HTTP_PORT"] = str(self._http_port)
            env["PAW_HTTPS_PORT"] = str(_free_port())
            _log.info("Starting PAW from %s against the TM1 server at %s (the first start loads IBM's images)", self._source.describe(), tm1_address)
            self._run_script(docker, "up", env, self._startup_timeout)
            _connect_to_network(docker, tm1_network["NetworkID"])
            self._running = True
            self._await_ready(server)
            _log.info("PAW is up at %s, logging users on to the TM1 server at %s", self.url, tm1_address)

    def _script_settings(self, tm1_address: str, admin_address: str, cognos_url: str | None) -> dict[str, str]:
        """``paw-local.sh``'s settings for PAW against the TM1 server, without the ports: the
        caller's own, then this class's wiring. With the server in CAM security, PAW logs users on
        through its Cognos and namespace; ``cognos_url`` is Cognos as the host sees it
        (:attr:`CognosContainer.url`), where PAW sends browsers to log on."""
        server = self._tm1
        if server is None:
            raise RuntimeError("No TM1 server: set one with with_tm1_server")
        env = dict(self._settings)
        env["PAW_TM1_ADMIN_URL"] = f"http://{admin_address}:{self.ADMIN_PORT}"
        env["PAW_TM1_LOGIN_URL"] = f"http://{tm1_address}:{Tm1ServerContainer.REST_PORT}"
        env["PAW_NETWORK"] = self.PAW_NETWORK
        env["PAW_WAIT"] = "0"
        cognos, namespace = server.cognos, server.cam_namespace
        if cognos is None or namespace is None:
            env["PAW_AUTH_MODE"] = "tm1"
            return env
        if cognos_url is None:
            raise RuntimeError("PAW in CAM security needs Cognos's address on the host")
        # As pa-setup: the dispatcher on the network checks logons; the gateway, Cognos as
        # browsers reach it, is where PAW sends them to log on
        env["PAW_AUTH_MODE"] = "cam"
        env["PAW_CAM_SERVLET"] = f"{cognos.network_url}/p2pd/servlet/dispatch"
        env["PAW_CAM_GATEWAY"] = f"{cognos_url}v1/disp"
        env["PAW_CAM_NAMESPACE"] = namespace.id
        return env

    def stop(self) -> None:
        """Removes the PAW containers, keeping PAW's data."""
        with self._lock:
            if not self._running:
                return
            self._running = False
            try:
                self._run_script(DockerClient().client, "down", {}, 5 * 60.0)
            except Exception:  # noqa: BLE001 - best effort, as Java's
                _log.warning("Couldn't stop PAW; its containers may still be running", exc_info=True)

    def close(self) -> None:
        """The same as :meth:`stop`."""
        self.stop()

    def __enter__(self) -> Self:
        self.start()
        return self

    def __exit__(self, *exc: Any) -> None:
        self.stop()

    @staticmethod
    def license_accepted_by_setting() -> bool:
        """Whether IBM's PAW license is accepted by the ``PAW_ACCEPT_LICENSE`` setting: true if it's
        set to ``true``."""
        value = setting(PawContainer.ACCEPT_LICENSE)
        return value is not None and value.strip().lower() == "true"

    def _run_script(self, docker: Any, command: str, env: dict[str, str], timeout: float) -> None:
        home = _home_mountpoint(docker)
        zip_name = self._source.zip.name
        runner = docker.containers.run(
            _runner_image(docker),
            command=["paw-local.sh", command],
            environment={**env, "PAW_ACCEPT_LICENSE": "true", "PAW_ZIP": f"/zip/{zip_name}", "PAW_HOME": home},
            # The Docker socket, as Testcontainers' Ryuk mounts it, and PAW's home volume at its
            # path on the Docker host: IBM's compose files bind-mount files from it by that path,
            # which the daemon resolves
            volumes={
                str(self._source.zip): {"bind": f"/zip/{zip_name}", "mode": "ro"},
                tc_config.ryuk_docker_socket: {"bind": "/var/run/docker.sock", "mode": "rw"},
                self.HOME_VOLUME: {"bind": home, "mode": "rw"},
            },
            detach=True,
        )
        try:
            deadline = time.monotonic() + timeout
            for line in runner.logs(stream=True, follow=True):
                _log.info("paw-local: %s", line.decode(errors="replace").rstrip())
                if time.monotonic() > deadline:
                    break
            result = runner.wait(timeout=max(1.0, deadline - time.monotonic()))
            if result.get("StatusCode") != 0:
                raise RuntimeError(f"paw-local.sh {command} failed (exit {result.get('StatusCode')}): {runner.logs(tail=40).decode(errors='replace')}")
        finally:
            runner.remove(force=True)

    def _await_ready(self, server: Tm1ServerContainer) -> None:
        deadline = time.monotonic() + _READY_TIMEOUT
        last = "no answer yet"
        while time.monotonic() < deadline:
            try:
                session = PawSession(self.url).login(server.username, server.password)
                servers = session.servers()
                if servers:
                    version = session.tm1_get(servers[0], "Configuration/ProductVersion/$value")
                    if version.status_code == 200:
                        return
                    last = f"a TM1 call through PAW answered {version.status_code}: {version.text}"
                else:
                    last = "PAW lists no TM1 servers yet"
            except (OSError, httpx2.HTTPError) as e:
                last = str(e)
            time.sleep(5)
        raise RuntimeError(f"PAW wasn't ready within {_READY_TIMEOUT / 60:.0f} minutes: {last}")

    def __repr__(self) -> str:
        return f"PawContainer[{self._source.describe()}" + (f", {self.url}" if self._running else "") + "]"


def _home_mountpoint(docker: Any) -> str:
    import docker.errors as errors

    try:
        return str(docker.volumes.get(PawContainer.HOME_VOLUME).attrs["Mountpoint"])
    except errors.NotFound:
        return str(docker.volumes.create(name=PawContainer.HOME_VOLUME).attrs["Mountpoint"])


def _connect_to_network(docker: Any, network_id: str) -> None:
    network = docker.networks.get(network_id)
    for container in docker.containers.list(filters={"label": f"com.docker.compose.project={_COMPOSE_PROJECT}"}):
        networks = (container.attrs.get("NetworkSettings") or {}).get("Networks") or {}
        if not any(n.get("NetworkID") == network_id for n in networks.values()):
            network.connect(container.id)


def _runner_image(docker: Any) -> str:
    """``docker:cli`` with bash, curl and ``paw-local.sh``, tagged by the script's hash."""
    content = script()
    tag = "tm1-paw-runner:" + hashlib.sha256(content).hexdigest()[:12]
    import docker.errors as errors

    try:
        docker.images.get(tag)
        return tag
    except errors.ImageNotFound:
        pass
    dockerfile = (
        f"FROM {_RUNNER_BASE}\nRUN apk add --no-cache bash curl\nCOPY paw-local.sh /usr/local/bin/paw-local.sh\nRUN chmod 755 /usr/local/bin/paw-local.sh\n"
    ).encode()
    context = io.BytesIO()
    with tarfile.open(fileobj=context, mode="w") as tar:
        for name, data in (("Dockerfile", dockerfile), ("paw-local.sh", content)):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o755
            tar.addfile(info, io.BytesIO(data))
    context.seek(0)
    docker.images.build(fileobj=context, custom_context=True, tag=tag, rm=True)
    return tag


class PawSession:
    """A user logged on to PAW, calling it as the browser does, and TM1 through its gateway."""

    _NAME = r'"Name"\s*:\s*"((?:[^"\\]|\\.)*)"'

    def __init__(self, url: str) -> None:
        self._url = url
        self._cookies: dict[str, str] = {}
        # PAW's gateway is Apache over plain http: no h2c upgrade
        self._http = httpx2.Client(timeout=30.0, follow_redirects=False)

    def login(self, username: str, password: str) -> Self:
        """Logs on as a user PAW authenticates: a user of its TM1 server, or a Cognos user when PAW
        runs in CAM security. Raises :class:`OSError` when PAW refuses."""
        page = self._send("GET", "login")
        if page.status_code != 200:
            raise OSError(f"PAW's logon page answered {page.status_code}")
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        csrf = self._cookies.get("ba-sso-csrf")
        if csrf is not None:
            headers["ba-sso-authenticity"] = csrf
        form = f"mode=basic&username={quote_plus(username)}&password={quote_plus(password)}"
        response = self._send("POST", "login/form/", content=form, headers=headers)
        if response.status_code != 200 or 'error = "' in response.text:
            raise OSError(f"PAW refused the logon of {username} ({response.status_code})")
        return self

    def servers(self) -> list[str]:
        """The TM1 servers PAW lists."""
        import re

        response = self.get("tm1/api/v1/Servers")
        if response.status_code != 200:
            raise OSError(f"PAW couldn't list TM1 servers ({response.status_code}): {response.text}")
        return re.findall(self._NAME, response.text)

    def tm1_get(self, server: str, path: str) -> httpx2.Response:
        """A TM1 REST call through PAW's gateway."""
        return self.get(f"tm1/{quote(server, safe='')}/api/v1/{path}")

    def get(self, path: str) -> httpx2.Response:
        """A GET request on PAW, relative to its address: ``path`` has no leading ``/``."""
        return self._send("GET", path)

    def _send(self, method: str, path: str, **kwargs: Any) -> httpx2.Response:
        headers = dict(kwargs.pop("headers", {}))
        if self._cookies:
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self._cookies.items())
        response = self._http.request(method, self._url + path, headers=headers, **kwargs)
        for header in response.headers.get_list("set-cookie"):
            parsed: SimpleCookie = SimpleCookie()
            parsed.load(header)
            for name, morsel in parsed.items():
                self._cookies[name] = morsel.value
        return response
