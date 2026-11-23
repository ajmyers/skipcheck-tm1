# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1SessionPool, quoted in the guide."""

from __future__ import annotations

from datetime import timedelta

from skipcheck_tm1 import Tm1Identity, Tm1Session, Tm1SessionPool
from skipcheck_tm1.model import Cube
from skipcheck_tm1.services import CubeService


def per_user(secret: str, user: str) -> list[Cube]:
    # @start region = per-user
    # One pool for the application, built once
    pool = (
        Tm1SessionPool.builder(Tm1Session.builder().base_url("http://tm1:8010/api/v1/").basic_auth("service", secret))
        .idle_timeout(timedelta(minutes=10))
        .build()
    )
    # Each time it's needed: the user's session, which logs in on its first request
    session = pool.session(Tm1Identity.impersonating(user))
    return CubeService(session).list()
    # @end
