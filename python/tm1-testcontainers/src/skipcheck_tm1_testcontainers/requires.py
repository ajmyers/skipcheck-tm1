# SPDX-License-Identifier: Apache-2.0

"""Java's ``@Requires…`` annotations: their messages here, and as pytest markers (the
``mark`` of each, or ``@pytest.mark.requires_tm1`` and the others; see
:mod:`skipcheck_tm1_testcontainers.plugin`)."""

from __future__ import annotations

import pytest

from . import cognos, engine, paw, tm1, web

__all__ = ["RequiresCognos", "RequiresPaw", "RequiresTm1", "RequiresTm1Engine", "RequiresTm1Web"]


class RequiresTm1:
    """Runs a test against a real TM1 server, and skips it when there isn't one: ``mark`` is the
    marker (``@RequiresTm1.mark``, or ``@pytest.mark.requires_tm1``). Tests are skipped, not failed,
    when Docker isn't available or no :class:`Tm1Source` is configured, so a build without IBM
    software stays green. A source that is configured but wrong, such as a folder with no TM1 in it,
    fails instead. The ``tm1_server`` fixture gives the shared server."""

    NO_SOURCE_MESSAGE = tm1.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1


class RequiresTm1Engine:
    """Runs a test against a real TM1 v12 engine, and skips it when there isn't one: ``mark`` is the
    marker (``@RequiresTm1Engine.mark``, or ``@pytest.mark.requires_tm1_engine``). For TM1 11, use
    :class:`RequiresTm1`.

    Tests are skipped, not failed, when Docker isn't available or no :class:`Tm1EngineSource` is
    configured, so a build without IBM software stays green. A source that is configured but wrong
    fails instead.

    One engine serves the whole test session (:meth:`Tm1EngineContainer.shared`, the ``tm1_engine``
    fixture), and **each test module gets a database of its own** (the ``tm1_database`` fixture),
    created when a test of the module first asks for it and dropped when the module is done. So
    modules don't see each other's objects. Java's "test class" is the unit a test framework shares
    set-up over: in pytest that's the module, a test file.

    A database takes about 10 seconds to create and 7 to drop.
    ``@pytest.mark.requires_tm1_engine(share_database=True)`` gives the tests it marks the engine's
    one shared database, ``tm1``, instead, at no cost: they then need uniquely named objects, and to
    delete them afterwards. ``min_version="12.6"`` skips them on an older engine; checking it starts
    the engine. The marker nearest the test counts: its own, then its class's, then its module's."""

    NO_SOURCE_MESSAGE = engine.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1_engine
    share_database = False
    """The marker's ``share_database`` unless it's given: a database of the module's own."""
    min_version = ""
    """The marker's ``min_version`` unless it's given: any version."""


class RequiresCognos:
    """Runs a test that needs IBM Cognos Analytics (CAM security) as well as TM1, and skips it when
    either isn't available. It includes :class:`RequiresTm1`, so tests also skip without Docker or a
    :class:`Tm1Source`. The ``cognos`` fixture gives the source :meth:`CognosSource.discover` found.
    Cognos takes minutes to start and its test users vary by test, so there's no shared Cognos:
    build a :class:`CognosContainer` with the users the test needs."""

    NO_SOURCE_MESSAGE = cognos.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_cognos


class RequiresTm1Web:
    """Runs a test against real TM1 Web and TM1 servers, and skips it when either isn't available.
    It includes :class:`RequiresTm1`, so tests also skip without Docker or a :class:`Tm1Source`. The
    ``tm1_web`` fixture gives the shared TM1 Web, which lists and logs on to the shared TM1 server."""

    NO_SOURCE_MESSAGE = web.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1_web


class RequiresPaw:
    """Runs a test against a real Planning Analytics Workspace and TM1 server, and skips it when
    either isn't available. It includes :class:`RequiresTm1`, so tests also skip without Docker or a
    :class:`Tm1Source`. They also skip without a :class:`PawSource`, or when IBM's PAW license
    hasn't been accepted with ``PAW_ACCEPT_LICENSE=true``. The ``paw`` fixture gives the shared PAW."""

    NO_SOURCE_MESSAGE = paw.NO_SOURCE_MESSAGE
    NO_LICENSE_MESSAGE = paw.NO_LICENSE_MESSAGE
    mark = pytest.mark.requires_paw
