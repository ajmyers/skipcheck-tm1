"""A TM1 server in Docker for the live tests, as the Java client's tm1-testcontainers gives its
``@RequiresTm1`` tests: the same TM1 sources, read from the same settings, in the same order, and
the same ``tm1-server`` image, mounts and cache volume (so a bundle unpacked for the Java tests is
reused here, and the other way round).

The ``tm1-server`` image has no IBM software in it, so one of these supplies TM1:

1. ``TM1_PRIVATE_IMAGE``: an image with TM1 built in;
2. ``TM1_HOME_DIR``: an installed ``tm1_64`` folder;
3. ``TM1_INSTALL_VOLUME``: a Docker volume holding an install;
4. ``TM1_BUNDLE_DIR`` (with ``TM1_BUNDLE`` to choose the file): a folder holding IBM's bundle;
5. ``TM1_BUNDLE_URL`` (with ``TM1_BUNDLE_SHA256``): a download link for the bundle.

``TM1_SERVER_IMAGE`` replaces the ``tm1-server`` image itself (default
``ghcr.io/ajmyers/tm1-server:edge``). ``TM1_KEEP_CONTAINER=true`` leaves the container running
after the tests, for a look around.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable, Mapping

DEFAULT_IMAGE = "ghcr.io/ajmyers/tm1-server:edge"
REST_PORT = 8010
CACHE_VOLUME = "tm1-testcontainers-cache"
INSTALL_PATH = "/opt/ibm/cognos/tm1_64"
STARTUP_TIMEOUT = 300.0

NO_SOURCE_MESSAGE = (
    "No TM1 source configured. Set one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or "
    "TM1_BUNDLE_URL to run tests against TM1 (or TM1_URL for a server that's already running)."
)


@dataclass(frozen=True)
class Source:
    """Where the container gets TM1 from: the kind (Java's Tm1Source records) and its settings."""

    kind: str
    value: str
    extra: str | None = None

    def describe(self) -> str:
        if self.kind == "bundle-url":
            return "bundle " + self.value.split("?", 1)[0]  # never the query string (a signed URL's secret)
        return {"private-image": "image", "install-dir": "install", "install-volume": "install volume", "bundle": "bundle in"}[self.kind] + " " + self.value


def discover(lookup: Callable[[str], str | None] = os.environ.get) -> Source | None:
    """The first source set, in Java's order (``Tm1Source.discover``); None if none is."""

    def get(name: str) -> str | None:
        value = lookup(name)
        return value.strip() if value and value.strip() else None

    if image := get("TM1_PRIVATE_IMAGE"):
        return Source("private-image", image)
    if home := get("TM1_HOME_DIR"):
        if not os.path.isdir(home):
            raise ValueError(f"TM1_HOME_DIR isn't a folder: {home}")
        return Source("install-dir", os.path.abspath(home))
    if volume := get("TM1_INSTALL_VOLUME"):
        return Source("install-volume", volume)
    if bundle_dir := get("TM1_BUNDLE_DIR"):
        if not os.path.isdir(bundle_dir):
            raise ValueError(f"TM1_BUNDLE_DIR isn't a folder: {bundle_dir}")
        return Source("bundle", os.path.abspath(bundle_dir), get("TM1_BUNDLE"))
    if url := get("TM1_BUNDLE_URL"):
        return Source("bundle-url", url, get("TM1_BUNDLE_SHA256"))
    return None


def run_arguments(source: Source, lookup: Callable[[str], str | None] = os.environ.get) -> list[str]:
    """The ``docker run`` arguments for a source, as Java's ``Tm1ServerContainer.mountSource``."""
    image = source.value if source.kind == "private-image" else (lookup("TM1_SERVER_IMAGE") or "").strip() or DEFAULT_IMAGE
    args = ["run", "-d", "--platform", "linux/amd64", "-p", f"127.0.0.1::{REST_PORT}", "-e", "TM1_SEED=empty"]
    if source.kind == "install-dir":
        args += ["-v", f"{source.value}:{INSTALL_PATH}:ro"]
    elif source.kind == "install-volume":
        args += ["-v", f"{source.value}:{INSTALL_PATH}"]
    elif source.kind == "bundle":
        args += ["-v", f"{source.value}:/ibm:ro", "-v", f"{CACHE_VOLUME}:/tm1/cache"]
        if source.extra:
            args += ["-e", f"TM1_BUNDLE={source.extra}"]
    elif source.kind == "bundle-url":
        args += ["-e", f"TM1_BUNDLE_URL={source.value}", "-v", f"{CACHE_VOLUME}:/tm1/cache"]
        if source.extra:
            args += ["-e", f"TM1_BUNDLE_SHA256={source.extra}"]
    return args + ["--label", "net.ajmyers.tm1.tests=python", image]


def docker_available() -> bool:
    if shutil.which("docker") is None:
        return False
    try:
        return subprocess.run(["docker", "info"], capture_output=True, timeout=30).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


class Tm1Container:
    """A started TM1 server: its REST root, and the empty seed's login (``admin``, no password)."""

    username = "admin"
    password = ""

    def __init__(self, source: Source, env: Mapping[str, str] | None = None):
        self.source = source
        self._env = env if env is not None else os.environ
        self.id: str | None = None
        self.url: str | None = None

    def start(self) -> Tm1Container:
        args = run_arguments(self.source, self._env.get)
        started = subprocess.run(["docker", *args], capture_output=True, text=True)
        if started.returncode != 0:
            raise RuntimeError(f"Couldn't start TM1 ({self.source.describe()}): {started.stderr.strip()}")
        self.id = started.stdout.strip()
        port = subprocess.run(["docker", "port", self.id, str(REST_PORT)], capture_output=True, text=True, check=True).stdout
        host_port = port.strip().splitlines()[0].rsplit(":", 1)[1]
        self.url = f"http://127.0.0.1:{host_port}/api/v1/"
        self._wait()
        return self

    def _wait(self) -> None:
        # TM1 serves $metadata without a login, so a 200 means the REST API is really up
        deadline = time.monotonic() + STARTUP_TIMEOUT
        while time.monotonic() < deadline:
            if not self._running():
                raise RuntimeError(f"TM1's container stopped while starting:\n{self.logs()}")
            try:
                with urllib.request.urlopen(self.url + "$metadata", timeout=10) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, OSError):
                pass
            time.sleep(2)
        raise RuntimeError(f"TM1 wasn't up within {STARTUP_TIMEOUT:.0f} s:\n{self.logs()}")

    def _running(self) -> bool:
        state = subprocess.run(["docker", "inspect", "-f", "{{json .State}}", self.id or ""], capture_output=True, text=True)
        return state.returncode == 0 and json.loads(state.stdout).get("Running", False)

    def logs(self) -> str:
        out = subprocess.run(["docker", "logs", "--tail", "60", self.id or ""], capture_output=True, text=True)
        return out.stdout + out.stderr

    def stop(self) -> None:
        if self.id and (self._env.get("TM1_KEEP_CONTAINER") or "").lower() != "true":
            subprocess.run(["docker", "rm", "-f", self.id], capture_output=True)
        self.id = None
