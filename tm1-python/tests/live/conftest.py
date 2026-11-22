"""Tests against a real TM1, as the Java client's ``*Tm1Test`` classes. The server is either:

* started in Docker for the run, from the same TM1 sources the Java tests use
  (``TM1_PRIVATE_IMAGE``, ``TM1_HOME_DIR``, ``TM1_INSTALL_VOLUME``, ``TM1_BUNDLE_DIR``,
  ``TM1_BUNDLE_URL``: see ``tm1_container.py``); its login is ``admin`` with no password;
* or one already running: ``TM1_URL``, the REST API's root, such as
  ``https://localhost:8010/api/v1``, with ``TM1_USER`` and ``TM1_PASSWORD`` (default ``admin``
  and ``apple``), ``TM1_NAMESPACE`` for CAM, and ``TM1_VERIFY=false`` to accept a self-signed
  certificate.

Without either (or without Docker for the first), they're skipped. A source that's set but wrong
fails, rather than skipping. They make objects named ``tm1py_test_…`` and delete them afterwards.
"""

from __future__ import annotations

import os
import uuid
from typing import Any, Iterator

import pytest

from tm1_client import Tm1Client

from . import tm1_container

_SOURCE = tm1_container.discover()  # raises if a source is set but wrong, as Java's does
_RUNNING = os.environ.get("TM1_URL")

CONFIGURED = bool(_RUNNING) or (_SOURCE is not None and tm1_container.docker_available())
"""Whether there's a TM1 to test against: the tests skip without one."""

REASON = tm1_container.NO_SOURCE_MESSAGE if _SOURCE is None else "Docker isn't available, so there's no TM1 to test against"

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

_container: tm1_container.Tm1Container | None = None


def target() -> tuple[str, str, str]:
    """The REST root and login of the TM1 to test against, starting its container the first time."""
    global _container
    if _RUNNING:
        return _RUNNING, os.environ.get("TM1_USER", "admin"), os.environ.get("TM1_PASSWORD", "apple")
    if not CONFIGURED or _SOURCE is None:
        pytest.skip(REASON)
    if _container is None:
        _container = tm1_container.Tm1Container(_SOURCE).start()
    assert _container.url is not None
    return _container.url, _container.username, _container.password


def pytest_sessionfinish(session: Any, exitstatus: int) -> None:
    if _container is not None:
        _container.stop()


def _builder(builder: Any) -> Any:
    from tm1_client import auth

    url, user, password = target()
    namespace = os.environ.get("TM1_NAMESPACE") if _RUNNING else None
    builder = builder.base_url(url).verify(os.environ.get("TM1_VERIFY", "true").lower() != "false")
    return builder.authenticator(auth.cam_namespace(user, password, namespace) if namespace else auth.basic(user, password))


def client() -> Tm1Client:
    """A new client of the configured TM1."""
    return _builder(Tm1Client.builder()).build()


def async_client() -> Any:
    """A new asyncio client of the configured TM1."""
    from tm1_client.aio import AsyncTm1Client

    return _builder(AsyncTm1Client.builder()).build()


@pytest.fixture(scope="session")
def tm1() -> Iterator[Tm1Client]:
    if not CONFIGURED:
        pytest.skip(REASON)
    with client() as c:
        yield c


@pytest.fixture
def name() -> str:
    return "tm1py_test_" + uuid.uuid4().hex[:8]
