# SPDX-License-Identifier: Apache-2.0

"""Examples for ServerService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import LogLevel, Server


def version(tm1: Tm1Client) -> str:
    # @start region = version
    server = tm1.server.server(ODataQuery.none().select(Server.NAME, Server.PRODUCT_VERSION))
    return f"{server.name} runs TM1 {server.product_version}"
    # @end


def data_directory(tm1: Tm1Client) -> str | None:
    # @start region = data-directory
    # What the server runs with now, which can differ from tm1s.cfg until it restarts
    administration = tm1.server.active_configuration().administration
    return None if administration is None else administration.data_base_directory
    # @end


def logger_level(tm1: Tm1Client) -> None:
    # @start region = logger-level
    # Takes effect at once, without a restart
    tm1.server.set_logger_level("TM1.Process", LogLevel.DEBUG)
    # @end
