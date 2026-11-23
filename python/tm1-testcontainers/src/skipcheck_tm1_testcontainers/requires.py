"""Java's ``@Requires…`` annotations: their messages here, and as pytest markers (the
``mark`` of each, or ``@pytest.mark.requires_tm1`` and the others; see
:mod:`skipcheck_tm1_testcontainers.plugin`)."""

from __future__ import annotations

import pytest

from . import cognos, paw, tm1, web

__all__ = ["RequiresCognos", "RequiresPaw", "RequiresTm1", "RequiresTm1Web"]


class RequiresTm1:
    NO_SOURCE_MESSAGE = tm1.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1


class RequiresCognos:
    NO_SOURCE_MESSAGE = cognos.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_cognos


class RequiresTm1Web:
    NO_SOURCE_MESSAGE = web.NO_SOURCE_MESSAGE
    mark = pytest.mark.requires_tm1_web


class RequiresPaw:
    NO_SOURCE_MESSAGE = paw.NO_SOURCE_MESSAGE
    NO_LICENSE_MESSAGE = paw.NO_LICENSE_MESSAGE
    mark = pytest.mark.requires_paw
