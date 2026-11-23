"""What Testcontainers for Java's ``GenericContainer`` gives the Java classes and
testcontainers-python's ``DockerContainer`` lacks: configuring without Docker (a container is
set up before it connects, so wiring can be tested anywhere), dependencies started first,
Java's ``configure`` and ``containerIsStarted`` hooks, reuse, extra hosts, and a lazily created
network.
"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import os
import shlex
import tarfile
import threading
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

from testcontainers.core.config import testcontainers_config as tc_config
from testcontainers.core.container import DockerContainer, Reaper
from testcontainers.core.docker_client import DockerClient, get_docker_host
from testcontainers.core.labels import LABEL_SESSION_ID, create_labels
from testcontainers.core.network import Network as _TcNetwork
from testcontainers.core.transferable import Transferable, build_transfer_tar
from testcontainers.core.wait_strategies import HttpWaitStrategy
from typing_extensions import Self

__all__ = ["GenericContainer", "Network", "docker_available", "reuse_enabled"]

REUSE_HASH_LABEL = "net.ajmyers.tm1.testcontainers.hash"


class Network(_TcNetwork):
    """A Docker network, created when a container first starts on it, as Java's
    ``Network.newNetwork()``; testcontainers-python's own :class:`~testcontainers.core.network.Network`
    works too."""

    def __init__(self, docker_client_kw: dict[str, Any] | None = None, docker_network_kw: dict[str, Any] | None = None) -> None:
        # testcontainers-python's connects to Docker here; Java's waits until it's used
        self.name = str(uuid.uuid4())
        self._docker_client_kw = docker_client_kw or {}
        self._lazy_docker: DockerClient | None = None
        self._docker_network_kw = docker_network_kw or {}
        self._network = None

    @property  # type: ignore[override]
    def _docker(self) -> DockerClient:
        if self._lazy_docker is None:
            self._lazy_docker = DockerClient(**self._docker_client_kw)
        return self._lazy_docker

    @staticmethod
    def new_network() -> Network:
        return Network()

    def close(self) -> None:
        """Removes the network, if it was created."""
        if self._network is not None:
            try:
                self.remove()
            finally:
                self._network = None


def _ensure_created(network: _TcNetwork) -> None:
    if getattr(network, "_network", None) is None:
        network.create()


def reuse_enabled() -> bool:
    """Whether reusable containers are reused, as in Java: ``testcontainers.reuse.enable=true``
    in ``~/.testcontainers.properties``, or ``TESTCONTAINERS_REUSE_ENABLE=true``."""
    value = os.environ.get("TESTCONTAINERS_REUSE_ENABLE") or tc_config.tc_properties.get("testcontainers.reuse.enable", "")
    return value.strip().lower() == "true"


def docker_available() -> bool:
    """Whether Docker answers, as Java's ``DockerClientFactory.isDockerAvailable()``."""
    try:
        client = DockerClient()
        try:
            return bool(client.client.ping())
        finally:
            client.client.close()
    except Exception:  # noqa: BLE001 - no Docker, however it shows
        return False


class _HttpWait(HttpWaitStrategy):
    """Java's ``Wait.forHttp``: and, unlike testcontainers-python's, it stops waiting when the
    container has exited."""

    def __init__(self, port: int, path: str) -> None:
        super().__init__(port, path)
        self._container: Any = None

    def wait_until_ready(self, container: Any) -> None:
        self._container = container
        super().wait_until_ready(container)

    def _try_http_request(self, url: str, headers: dict[str, str], ssl_context: Any) -> bool:
        if self._container is not None:
            self._container.reload()
            if self._container.status in ("exited", "dead"):
                out, err = self._container.get_logs()
                raise RuntimeError(f"The container stopped while starting:\n{(out + err).decode(errors='replace')[-4000:]}")
        return super()._try_http_request(url, headers, ssl_context)


def wait_for_http(path: str, port: int, *codes: int, timeout: float, accept: Callable[[int], bool] | None = None) -> HttpWaitStrategy:
    strategy = _HttpWait(port, path).with_startup_timeout(int(timeout))
    strategy._status_codes = set(codes) or {200}
    if accept is not None:
        strategy.for_status_code_matching(accept)
    return strategy


class GenericContainer(DockerContainer):
    """A container, as Testcontainers for Java's ``GenericContainer``: configured without Docker,
    started with its dependencies, and reusable."""

    _log = logging.getLogger("tm1_testcontainers")

    def __init__(self, image: str, docker_client_kw: dict[str, Any] | None = None) -> None:
        # DockerContainer's attributes, without its connecting to Docker: Java's containers are
        # set up before anything talks to Docker
        self.env: dict[str, str] = {}
        self.ports: dict[str, int | None] = {}
        self.volumes: dict[str, Any] = {}
        self.tmpfs: dict[str, str] = {}
        self.image = tc_config.hub_image_name_prefix + image
        self._docker_client_kw = docker_client_kw or {}
        self._lazy_docker: DockerClient | None = None
        self._container: Any = None
        self._command: str | list[str] | None = None
        self._name: str | None = None
        self._network: _TcNetwork | None = None
        self._network_aliases: list[str] | None = None
        self._kwargs: dict[str, Any] = {}
        self._wait_strategy: Any = None
        self._cached_container_info: Any = None
        self._transferable_specs: list[Any] = []
        self._dependencies: list[Any] = []
        self._reuse = False
        self._labels: dict[str, str] = {}
        self._extra_hosts: dict[str, str] = {}
        self._lock = threading.RLock()

    @property  # type: ignore[override]
    def _docker(self) -> DockerClient:
        if self._lazy_docker is None:
            self._lazy_docker = DockerClient(**self._docker_client_kw)
        return self._lazy_docker

    # --- Configuration ---------------------------------------------------------------------

    def with_network_aliases(self, *aliases: str) -> Self:
        """Adds aliases, as Java does (testcontainers-python's replaces them)."""
        self._network_aliases = [*(self._network_aliases or []), *aliases]
        return self

    def with_kwargs(self, **kwargs: Any) -> Self:
        """Adds ``docker create`` arguments (testcontainers-python's replaces them)."""
        self._kwargs.update(kwargs)
        return self

    def with_platform(self, platform: str) -> Self:
        self._kwargs["platform"] = platform
        return self

    def with_extra_host(self, hostname: str, address: str) -> Self:
        self._extra_hosts[hostname] = address
        return self

    def with_label(self, key: str, value: str) -> Self:
        self._labels[key] = value
        return self

    def with_reuse(self, reuse: bool) -> Self:
        """Keeps the container for later runs, when reuse is enabled (:func:`reuse_enabled`):
        a later start with the same settings finds it and uses it."""
        self._reuse = reuse
        return self

    def depends_on(self, *containers: Any) -> Self:
        """Containers started before this one."""
        for container in containers:
            if container not in self._dependencies:
                self._dependencies.append(container)
        return self

    @property
    def dependencies(self) -> list[Any]:
        return list(self._dependencies)

    @property
    def network(self) -> _TcNetwork | None:
        return self._network

    @property
    def network_aliases(self) -> list[str]:
        return list(self._network_aliases or [])

    @property
    def env_map(self) -> dict[str, str]:
        return self.env

    @property
    def command_parts(self) -> list[str]:
        if self._command is None:
            return []
        return shlex.split(self._command) if isinstance(self._command, str) else list(self._command)

    @property
    def should_be_reused(self) -> bool:
        return self._reuse

    def configure(self) -> None:
        """Last settings before the container is created: Java's ``configure()``."""

    def container_is_started(self) -> None:
        """Once the container is up: Java's ``containerIsStarted``."""

    # --- Running ---------------------------------------------------------------------------

    @property
    def is_running(self) -> bool:
        if self._container is None:
            return False
        try:
            self._container.reload()
        except Exception:  # noqa: BLE001 - gone
            return False
        return bool(self._container.status == "running")

    @property
    def host(self) -> str:
        return self.get_container_host_ip()

    def get_mapped_port(self, port: int) -> int:
        return int(self.get_exposed_port(port))

    @property
    def container_id(self) -> str:
        return self.get_container_id()

    @property
    def container_info(self) -> dict[str, Any]:
        """``docker inspect`` of the container: Java's ``getContainerInfo()``."""
        if self._container is None:
            raise RuntimeError("The container isn't started yet")
        self._container.reload()
        return dict(self._container.attrs)

    @property
    def logger(self) -> logging.Logger:
        return self._log

    def start(self) -> Self:
        with self._lock:
            if self._container is not None and self.is_running:
                return self
            for dependency in self._dependencies:
                if not dependency.is_running:
                    dependency.start()
            self.configure()
            if self._network is not None:
                _ensure_created(self._network)
            reuse = self._reuse and reuse_enabled()
            if not reuse and not tc_config.ryuk_disabled:
                Reaper.get_instance()
            client = self.get_docker_client()
            kwargs: dict[str, Any] = dict(self._kwargs)
            if self._network is not None:
                from docker import version
                from docker.types import EndpointConfig

                kwargs["network"] = self._network.name
                kwargs["networking_config"] = {self._network.name: EndpointConfig(version.__version__, aliases=self._network_aliases)}
            if self._extra_hosts:
                kwargs["extra_hosts"] = {**kwargs.get("extra_hosts", {}), **self._extra_hosts}
            if self.volumes:
                kwargs["volumes"] = {host: {"bind": m["bind"], "mode": m["mode"]} for host, m in self.volumes.items()}
            if self.tmpfs:
                kwargs["tmpfs"] = self.tmpfs
            if "network" not in kwargs and not get_docker_host():
                host_network = client.find_host_network()
                if host_network:
                    kwargs["network"] = host_network
            labels = create_labels(self.image, self._labels)
            existing = None
            if reuse:
                # Not reaped at the end of the session, and found again by its settings
                labels.pop(LABEL_SESSION_ID, None)
                labels[REUSE_HASH_LABEL] = self._hash(kwargs)
                found = client.client.containers.list(filters={"label": f"{REUSE_HASH_LABEL}={labels[REUSE_HASH_LABEL]}", "status": "running"})
                existing = found[0] if found else None
            self._cached_container_info = None
            if existing is not None:
                self._log.info("Reusing container %s (%s)", existing.short_id, self.image)
                self._container = existing
            else:
                self._log.info("Starting a container of %s", self.image)
                import docker.errors

                try:
                    client.client.images.get(self.image)
                except docker.errors.ImageNotFound:
                    client.client.images.pull(self.image, platform=kwargs.get("platform"))
                self._container = client.client.containers.create(
                    self.image,
                    command=self._command,
                    environment=self.env,
                    ports=self.ports,
                    name=self._name,
                    labels=labels,
                    **kwargs,
                )
                for spec in self._transferable_specs:
                    self._transfer_into_container(*spec)
                self._container.start()
            try:
                if self._wait_strategy is not None:
                    self._wait_strategy.wait_until_ready(self)
                self.container_is_started()
            except BaseException:
                if existing is None:
                    self.stop()
                raise
            return self

    def stop(self, force: bool = True, delete_volume: bool = True) -> None:
        with self._lock:
            if self._container is not None:
                import docker.errors

                try:
                    self._container.remove(force=force, v=delete_volume)
                except docker.errors.NotFound:
                    pass
            self._container = None
            self._cached_container_info = None

    def close(self) -> None:
        self.stop()

    def __exit__(self, *exc: Any) -> None:
        self.stop()

    def _hash(self, kwargs: dict[str, Any]) -> str:
        config = {
            "image": self.image,
            "command": self._command,
            "env": self.env,
            "ports": sorted(self.ports),
            "labels": self._labels,
            "kwargs": {k: v for k, v in kwargs.items() if k != "networking_config"},
            "aliases": self._network_aliases,
            "copies": [(str(t), dest) for t, dest, *_ in self._transferable_specs],
        }
        return hashlib.sha256(json.dumps(config, sort_keys=True, default=str).encode()).hexdigest()

    def _transfer_into_container(self, transferable: Transferable, destination_in_container: str, mode: int) -> None:
        # Java's MountableFile: a folder copied to a path is that path, so its contents go in it
        if isinstance(transferable, Path) and transferable.is_dir():
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode="w") as tar:
                for child in sorted(transferable.iterdir()):
                    tar.add(str(child), arcname=f"{destination_in_container.strip('/')}/{child.name}")
            data = buf.getvalue()
        else:
            data = build_transfer_tar(transferable, destination_in_container, mode)
        if not self._container.put_archive(path="/", data=data):
            raise OSError(f"Failed to put archive into container at {destination_in_container}")


def share_network(a: GenericContainer, b: GenericContainer) -> None:
    """Puts two containers that work together on one network: whichever either has, or a new one."""
    network = a.network if a.network is not None else b.network
    if network is None:
        network = Network.new_network()
    for container in (a, b):
        if container.network is None:
            container.with_network(network)
        elif container.network is not network:
            raise RuntimeError(
                f"Containers that work together must share one Network, but {type(container).__name__} is on another. Give them the same Network."
            )
