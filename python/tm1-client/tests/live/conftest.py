# SPDX-License-Identifier: Apache-2.0

"""Tests against a real TM1: the Java client's ``*Tm1Test`` and ``*BehaviorTest`` classes, one file
each (``CubeServiceTm1Test`` is test_cube_service.py, ``Tm1SessionBehaviorTest`` is
test_tm1_session_behavior.py), with each test under its Java name in snake case. The offline
tests/test_java_live_parity.py checks that against the Java sources. Tests only this client has
are in test_python_only.py. The server is either:

* started in Docker for the run by tm1-testcontainers (``python/tm1-testcontainers``), as Java's
  ``@RequiresTm1`` tests start theirs: :meth:`Tm1ServerContainer.shared`, from the same TM1
  sources (``TM1_PRIVATE_IMAGE``, ``TM1_HOME_DIR``, ``TM1_INSTALL_VOLUME``, ``TM1_BUNDLE_DIR``,
  ``TM1_BUNDLE_URL``), one container for the whole run, logging in as ``admin`` with no password;
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
from collections.abc import Iterator
from typing import TYPE_CHECKING, Any

import pytest

from skipcheck_tm1 import Tm1Client, Tm1Dialect, Tm1Session, Tm1Version

if TYPE_CHECKING:
    from .raw_tm1 import RawTm1

_RUNNING = os.environ.get("TM1_URL")
_ALREADY_RUNNING = " Or set TM1_URL for a server that's already running."

try:
    from skipcheck_tm1_testcontainers import RequiresTm1, Tm1EngineContainer, Tm1EngineSource, Tm1ServerContainer, Tm1Source, docker_available
    from skipcheck_tm1_testcontainers.engine import NO_SOURCE_MESSAGE as ENGINE_NO_SOURCE_MESSAGE
    from skipcheck_tm1_testcontainers.plugin import NO_DOCKER_MESSAGE
except ImportError:  # tm1-testcontainers isn't installed: only TM1_URL
    _SOURCE = None
    _ENGINE_SOURCE = None
    CONFIGURED = bool(_RUNNING)
    REASON = "Install tm1-testcontainers (pip install -e python/tm1-testcontainers) to start TM1 in Docker." + _ALREADY_RUNNING
else:
    _SOURCE = Tm1Source.discover()  # raises if a source is set but wrong, as Java's does
    _ENGINE_SOURCE = None if _SOURCE is not None else Tm1EngineSource.discover()
    CONFIGURED = bool(_RUNNING) or ((_SOURCE is not None or _ENGINE_SOURCE is not None) and docker_available())
    """Whether there's a TM1 to test against: the tests skip without one."""
    REASON = (
        f"{RequiresTm1.NO_SOURCE_MESSAGE} {ENGINE_NO_SOURCE_MESSAGE}{_ALREADY_RUNNING}" if _SOURCE is None and _ENGINE_SOURCE is None else NO_DOCKER_MESSAGE
    )

IS_ENGINE: bool = not _RUNNING and _SOURCE is None and _ENGINE_SOURCE is not None
"""Whether the live tests are running against a TM1 v12 engine container."""

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

INSTALL: str | None = None
"""Whether the TM1 the tests run against can write to its own install, as Java's
ServerAssumptions.Install names it: "readOnly" for an installed folder (TM1_HOME_DIR or
TM1_ENGINE_HOME_DIR, which tm1-testcontainers mounts read-only), "writable" for any other source,
and None for a server the suite didn't start (TM1_URL)."""
if not _RUNNING and _SOURCE is not None:
    INSTALL = "readOnly" if isinstance(_SOURCE, Tm1Source.InstallDir) else "writable"
elif not _RUNNING and _ENGINE_SOURCE is not None:
    INSTALL = "readOnly" if isinstance(_ENGINE_SOURCE, Tm1EngineSource.InstallDir) else "writable"


def target() -> tuple[str, str, str]:
    """The REST root and login of the TM1 to test against, starting its container the first time
    (one for the run, removed at its end by tm1-testcontainers' pytest plugin)."""
    if _RUNNING:
        return _RUNNING, os.environ.get("TM1_USER", "admin"), os.environ.get("TM1_PASSWORD", "apple")
    if not CONFIGURED or (_SOURCE is None and _ENGINE_SOURCE is None):
        pytest.skip(REASON)
    if IS_ENGINE:
        db = Tm1EngineContainer.shared().database(Tm1EngineContainer.DEFAULT_DATABASE)
        return db.rest_url, db.login().user, ""
    container = Tm1ServerContainer.shared()
    return container.rest_url, container.username, container.password


def _builder(builder: Any) -> Any:
    from skipcheck_tm1 import auth

    if IS_ENGINE:
        db = Tm1EngineContainer.shared().database(Tm1EngineContainer.DEFAULT_DATABASE)
        login = db.login()
        return (
            builder.base_url(db.rest_url)
            .dialect(Tm1Dialect.v12(db.instance, db.name))
            .authenticator(auth.application(login.client_id, login.client_secret, login.user))
        )
    url, user, password = target()
    namespace = os.environ.get("TM1_NAMESPACE") if _RUNNING else None
    builder = builder.base_url(url).verify(os.environ.get("TM1_VERIFY", "true").lower() != "false")
    return builder.authenticator(auth.cam_namespace(user, password, namespace) if namespace else auth.basic(user, password))


def client() -> Tm1Client:
    """A new client of the configured TM1."""
    return _builder(Tm1Client.builder()).build()


def async_client() -> Any:
    """A new asyncio client of the configured TM1."""
    from skipcheck_tm1.aio import AsyncTm1Client

    return _builder(AsyncTm1Client.builder()).build()


def session(**settings: Any) -> Tm1Session:
    """A new session of the configured TM1, with these builder settings (``session_context``,
    ``impersonate``...)."""
    builder = _builder(Tm1Session.builder())
    for setting, value in settings.items():
        builder = getattr(builder, setting)(value)
    return builder.build()


ADMIN = os.environ.get("TM1_USER", "admin")
"""The user the tests log in as."""


def authenticator(user: str, password: str) -> Any:
    """An authenticator for a named user: application auth on TM1 v12, Basic on TM1 11."""
    from skipcheck_tm1 import auth

    if IS_ENGINE:
        db = Tm1EngineContainer.shared().database(Tm1EngineContainer.DEFAULT_DATABASE)
        login = db.login(user)
        return auth.application(login.client_id, login.client_secret, login.user)
    return auth.basic(user, password)


def other_client(**settings: Any) -> Tm1Client:
    """Another client of the configured TM1: as the admin, or as ``user``/``password``."""
    from skipcheck_tm1 import auth

    builder = _builder(Tm1Client.builder())
    if "user" in settings:
        if IS_ENGINE:
            db = Tm1EngineContainer.shared().database(Tm1EngineContainer.DEFAULT_DATABASE)
            login = db.login(settings["user"])
            builder = builder.authenticator(auth.application(login.client_id, login.client_secret, login.user))
        else:
            builder = builder.authenticator(auth.basic(settings["user"], settings["password"]))
    if "session_context" in settings:
        builder = builder.session_context(settings["session_context"])
    return builder.build()


def started_version() -> Tm1Version | None:
    """The version the TM1 container read as it started, as Java's ``getTm1Version``; None for a
    server at ``TM1_URL``, which has no container to ask."""
    if _RUNNING:
        return None
    target()
    if IS_ENGINE:
        return Tm1EngineContainer.shared().tm1_version
    return Tm1ServerContainer.shared().tm1_version


@pytest.fixture(scope="session")
def tm1() -> Iterator[Tm1Client]:
    if not CONFIGURED:
        pytest.skip(REASON)
    with client() as c:
        yield c


@pytest.fixture
def name() -> str:
    return "tm1py_test_" + uuid.uuid4().hex[:8]


@pytest.fixture
def raw() -> Iterator[RawTm1]:
    """Bare HTTP to the configured TM1."""
    from .raw_tm1 import RawTm1

    if not CONFIGURED:
        pytest.skip(REASON)
    tm1 = RawTm1()
    try:
        yield tm1
    finally:
        tm1.shutdown()


@pytest.fixture
def admin(raw: RawTm1) -> Iterator[str]:
    """The cookie of an administrator's session, opened over bare HTTP and closed afterwards."""
    cookie = raw.login()
    try:
        yield cookie
    finally:
        raw.close(cookie)


@pytest.fixture
def elements_dim(tm1: Tm1Client, name: str) -> Iterator[str]:
    """ElementServiceTm1Test's and EdgeServiceTm1Test's dimension: Total = North + South, East,
    and Label, a string element."""
    from .support import consolidated, dimension_of, edge, made, numeric, string

    with made(
        tm1,
        name,
        dimension_of(
            name,
            [consolidated("Total"), numeric("North"), numeric("South"), numeric("East"), string("Label")],
            [edge("Total", "North"), edge("Total", "South")],
        ),
    ) as d:
        yield d


@pytest.fixture
def world(tm1: Tm1Client, name: str) -> Iterator[str]:
    """LevelAndMemberServiceTm1Test's and MemberServiceTm1Test's dimension: World over Europe
    (France, Spain) and Asia (Japan)."""
    from .support import consolidated, dimension_of, edge, made, numeric

    with made(
        tm1,
        name,
        dimension_of(
            name,
            [consolidated("World"), consolidated("Europe"), consolidated("Asia"), numeric("France"), numeric("Spain"), numeric("Japan")],
            [edge("World", "Europe"), edge("World", "Asia"), edge("Europe", "France"), edge("Europe", "Spain"), edge("Asia", "Japan")],
        ),
    ) as d:
        yield d
