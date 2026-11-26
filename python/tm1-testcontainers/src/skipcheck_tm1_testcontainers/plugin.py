# SPDX-License-Identifier: Apache-2.0

"""The pytest plugin: Java's ``@RequiresTm1``, ``@RequiresTm1Engine``, ``@RequiresCognos``,
``@RequiresTm1Web`` and ``@RequiresPaw`` as markers, and their parameters as fixtures.

* ``@pytest.mark.requires_tm1`` skips a test when Docker isn't running or no TM1 source is
  configured; a source that's configured but wrong fails it instead. ``requires_cognos``,
  ``requires_tm1_web`` and ``requires_paw`` need TM1 too, and their own source (and, for PAW,
  IBM's license accepted).
* The fixtures ``tm1_server`` (:meth:`Tm1ServerContainer.shared`), ``tm1_web``
  (:meth:`Tm1WebContainer.shared`), ``paw`` (:meth:`PawContainer.shared`) and ``cognos`` (the
  configured :class:`CognosSource`, as Java's ``@RequiresCognos`` injects) skip and fail the same
  way, so a test needs only the fixture.
* ``@pytest.mark.requires_tm1_engine`` does the same for TM1 v12, with a :class:`Tm1EngineSource`.
  ``tm1_engine`` is the shared engine (:meth:`Tm1EngineContainer.shared`), and ``tm1_database`` a
  database of the test module's own, created when the module first asks and dropped when it's
  done: Java's database per test class. ``requires_tm1_engine(share_database=True)`` gives the
  engine's one database, ``tm1``, instead, and ``min_version="12.6"`` skips on an older engine.

The shared containers are removed when the session ends, unless reuse is enabled
(``testcontainers.reuse.enable=true`` in ``~/.testcontainers.properties``). The shared engine is
always removed: it isn't reusable.
"""

from __future__ import annotations

import functools
from collections.abc import Callable, Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1Version

from ._container import docker_available, reuse_enabled
from .cognos import NO_SOURCE_MESSAGE as NO_COGNOS_MESSAGE
from .engine import NO_SOURCE_MESSAGE as NO_TM1_ENGINE_MESSAGE
from .engine import Tm1Database, Tm1EngineContainer, database_name
from .paw import NO_LICENSE_MESSAGE as NO_PAW_LICENSE_MESSAGE
from .paw import NO_SOURCE_MESSAGE as NO_PAW_MESSAGE
from .paw import PawContainer
from .sources import CognosSource, PawSource, Tm1EngineSource, Tm1Source, Tm1WebSource
from .tm1 import NO_SOURCE_MESSAGE as NO_TM1_MESSAGE
from .tm1 import Tm1ServerContainer
from .web import NO_SOURCE_MESSAGE as NO_TM1_WEB_MESSAGE
from .web import Tm1WebContainer

NO_DOCKER_MESSAGE = "Docker isn't available, so there's no TM1 to test against"

_MARKERS = {
    "requires_tm1": "needs a TM1 server in Docker: skipped without Docker or a TM1 source (Java's @RequiresTm1)",
    "requires_tm1_engine": "needs a TM1 v12 engine in Docker: skipped without Docker or a TM1 v12 source; share_database=True uses the engine's one "
    "database rather than the module's own, and min_version skips on an older engine (Java's @RequiresTm1Engine)",
    "requires_cognos": "needs TM1 and a Cognos source (Java's @RequiresCognos)",
    "requires_tm1_web": "needs TM1 and a TM1 Web source (Java's @RequiresTm1Web)",
    "requires_paw": "needs TM1, a PAW Local zip and its license accepted (Java's @RequiresPaw)",
}


@functools.cache
def _docker() -> bool:
    return docker_available()


def tm1_skip_reason() -> str | None:
    """Why a TM1 test is skipped, or None when it runs; raises when a source is set but wrong."""
    if not _docker():
        return NO_DOCKER_MESSAGE
    # Raises if a source is set but invalid: a wrong setting should fail, not skip
    return None if Tm1Source.discover() is not None else NO_TM1_MESSAGE


def tm1_engine_skip_reason() -> str | None:
    """Why a TM1 v12 test is skipped, or None when it runs; raises when a source is set but wrong."""
    if not _docker():
        return NO_DOCKER_MESSAGE
    # Raises if a source is set but invalid: a wrong setting should fail, not skip
    return None if Tm1EngineSource.discover() is not None else NO_TM1_ENGINE_MESSAGE


def tm1_engine_version_skip_reason(minimum: str) -> str | None:
    """Why a test that needs TM1 ``minimum`` or later is skipped, or None when it runs: as for TM1
    v12, or the shared engine, which this starts, is older."""
    reason = tm1_engine_skip_reason()
    if reason is not None or not minimum.strip():
        return reason
    engine = Tm1EngineContainer.shared()
    running = engine.tm1_version
    if running.is_before(Tm1Version.parse(minimum)):
        return f"Needs TM1 {minimum} or later; the engine from {engine.source.describe()} is {running}"
    return None


def _engine_options(node: Any) -> tuple[bool, str]:
    """share_database and min_version for a test, each from the nearest requires_tm1_engine marker
    that sets it: the test's own, then its class's, then its module's. So a marker on a test that
    sets one option keeps the other from its class or module, as JUnit evaluates a class's
    annotation as well as its method's."""
    options: dict[str, Any] = {}
    for marker in node.iter_markers(name="requires_tm1_engine"):  # the nearest first
        unknown = sorted(set(marker.kwargs) - {"share_database", "min_version"})
        if marker.args or unknown:
            raise pytest.UsageError(f"requires_tm1_engine takes share_database=... and min_version=..., not {[*marker.args, *unknown]}")
        for name, value in marker.kwargs.items():
            options.setdefault(name, value)
    return bool(options.get("share_database", False)), str(options.get("min_version") or "")


def cognos_skip_reason() -> str | None:
    """Why a Cognos test is skipped, or None when it runs: as for TM1, or no Cognos source is
    configured."""
    return tm1_skip_reason() or (None if CognosSource.discover() is not None else NO_COGNOS_MESSAGE)


def tm1_web_skip_reason() -> str | None:
    """Why a TM1 Web test is skipped, or None when it runs: as for TM1, or no TM1 Web source is
    configured."""
    return tm1_skip_reason() or (None if Tm1WebSource.discover() is not None else NO_TM1_WEB_MESSAGE)


def paw_skip_reason() -> str | None:
    """Why a PAW test is skipped, or None when it runs: as for TM1, or no PAW source is configured,
    or IBM's license isn't accepted."""
    reason = tm1_skip_reason()
    if reason is not None:
        return reason
    if PawSource.discover() is None:
        return NO_PAW_MESSAGE
    if not PawContainer.license_accepted_by_setting():
        return NO_PAW_LICENSE_MESSAGE
    return None


_CONDITIONS: dict[str, Callable[[], str | None]] = {
    "requires_tm1": tm1_skip_reason,
    "requires_tm1_engine": tm1_engine_skip_reason,
    "requires_cognos": cognos_skip_reason,
    "requires_tm1_web": tm1_web_skip_reason,
    "requires_paw": paw_skip_reason,
}


def pytest_configure(config: Any) -> None:
    """pytest's hook: registers the ``requires_…`` markers, so ``--strict-markers`` accepts them."""
    for name, help_text in _MARKERS.items():
        config.addinivalue_line("markers", f"{name}: {help_text}")


def pytest_runtest_setup(item: Any) -> None:
    """pytest's hook: skips a test marked ``requires_…`` when what it needs isn't there."""
    names = {marker.name for marker in item.iter_markers()}
    # Read first: a misspelt option fails, even where the test would be skipped
    minimum = _engine_options(item)[1] if "requires_tm1_engine" in names else ""
    for name, condition in _CONDITIONS.items():
        if name in names:
            reason = condition()
            if reason is not None:
                pytest.skip(reason)
    if minimum:
        _require(lambda: tm1_engine_version_skip_reason(minimum))


def pytest_unconfigure(config: Any) -> None:
    """pytest's hook: removes the shared containers as the session ends, unless reuse is enabled."""
    # Not left to the end of the process: removed as the session ends, unless kept for reuse
    if Tm1EngineContainer._shared is not None:
        # Never kept: an engine and its datastore belong together
        Tm1EngineContainer._shared.stop()
    if reuse_enabled():
        return
    for shared in (PawContainer._shared, Tm1WebContainer._shared, Tm1ServerContainer._shared):
        if shared is not None:
            shared.stop()


def _require(condition: Callable[[], str | None]) -> None:
    reason = condition()
    if reason is not None:
        pytest.skip(reason)


@pytest.fixture(scope="session")
def tm1_server() -> Tm1ServerContainer:
    """The shared TM1 server (Java's ``Tm1ServerContainer`` parameter under ``@RequiresTm1``)."""
    _require(tm1_skip_reason)
    return Tm1ServerContainer.shared()


@pytest.fixture(scope="session")
def cognos() -> CognosSource:
    """The configured Cognos source (Java's ``CognosSource`` parameter under ``@RequiresCognos``)."""
    _require(cognos_skip_reason)
    source = CognosSource.discover()
    assert source is not None
    return source


@pytest.fixture(scope="session")
def tm1_web() -> Tm1WebContainer:
    """The shared TM1 Web, listing the shared TM1 server (Java's ``@RequiresTm1Web``)."""
    _require(tm1_web_skip_reason)
    return Tm1WebContainer.shared()


@pytest.fixture(scope="session")
def paw() -> PawContainer:
    """The shared PAW, logging on to the shared TM1 server (Java's ``@RequiresPaw``)."""
    _require(paw_skip_reason)
    return PawContainer.shared()


@pytest.fixture(scope="session")
def tm1_engine() -> Tm1EngineContainer:
    """The shared TM1 v12 engine (Java's ``Tm1EngineContainer`` parameter under
    ``@RequiresTm1Engine``)."""
    _require(tm1_engine_skip_reason)
    return Tm1EngineContainer.shared()


@pytest.fixture(scope="module")
def _tm1_module_database(request: pytest.FixtureRequest, tm1_engine: Tm1EngineContainer) -> Iterator[Tm1Database]:
    """A test module's own database: created when the module first asks, dropped when it's done."""
    # Named after the module, without its packages, and a few random characters
    database = tm1_engine.create_database(database_name(request.module.__name__.rpartition(".")[2]))
    yield database
    if tm1_engine.is_running:
        tm1_engine.drop_database(database)


@pytest.fixture
def tm1_database(request: pytest.FixtureRequest, tm1_engine: Tm1EngineContainer) -> Tm1Database:
    """A database of the shared engine (Java's ``Tm1Database`` parameter under
    ``@RequiresTm1Engine``): the test module's own, or under
    ``requires_tm1_engine(share_database=True)`` the engine's one database, ``tm1``."""
    share, minimum = _engine_options(request.node)
    _require(lambda: tm1_engine_version_skip_reason(minimum))
    if share:
        return tm1_engine.database(Tm1EngineContainer.DEFAULT_DATABASE)
    database: Tm1Database = request.getfixturevalue("_tm1_module_database")
    return database
