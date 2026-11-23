"""Java's ``@Requires…`` annotations: their messages here, and as pytest markers (the
``mark`` of each, or ``@pytest.mark.requires_tm1`` and the others; see
:mod:`skipcheck_tm1_testcontainers.plugin`)."""

from __future__ import annotations

import pytest

from . import cognos, paw, tm1, web

__all__ = ["RequiresCognos", "RequiresPaw", "RequiresTm1", "RequiresTm1Web"]


class RequiresTm1:
    """Runs a test against a real TM1 server, and skips it when there isn't one: ``mark`` is the
    marker (``@RequiresTm1.mark``, or ``@pytest.mark.requires_tm1``). Tests are skipped, not failed,
    when Docker isn't available or no :class:`Tm1Source` is configured, so a build without IBM
    software stays green. A source that is configured but wrong, such as a folder with no TM1 in it,
    fails instead. The ``tm1_server`` fixture gives the shared server."""

    NO_SOURCE_MESSAGE = tm1.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1


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
