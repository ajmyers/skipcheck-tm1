# SPDX-License-Identifier: Apache-2.0

"""ServerService against a real TM1: a port of the Java client's ServerServiceTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import ODataPath, Tm1BadRequestError, Tm1Client, Tm1Request
from skipcheck_tm1.model import AdministrationSettingsPatch, LogLevel, ServerSettingsPatch

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_the_server_and_its_configurations(tm1: Tm1Client) -> None:
    read = tm1.server.server()
    assert read.product_version
    assert read.etag is None, "none for the Server"
    fixed = tm1.server.static_configuration()
    active = tm1.server.active_configuration()
    assert fixed.server_name == read.name
    assert active.server_name == read.name
    assert tm1.server.configuration().server_name == read.name

    assert fixed.administration is not None and active.administration is not None
    tm1.server.update_static_configuration(
        ServerSettingsPatch.builder().administration(AdministrationSettingsPatch.builder().server_name(fixed.administration.server_name).build()).build()
    )
    with pytest.raises(Tm1BadRequestError):
        # The active configuration is read only
        tm1.session.execute(
            Tm1Request.patch(
                ODataPath.of("ActiveConfiguration"),
                ServerSettingsPatch.builder()
                .administration(AdministrationSettingsPatch.builder().server_name(active.administration.server_name).build())
                .build(),
            )
        )
    # A duration here, though declared Int32
    assert isinstance(active.access.cam.client_ping_cam_passport, str)


def test_reads_and_sets_logger_levels(tm1: Tm1Client) -> None:
    loggers = tm1.server.loggers()
    assert loggers
    # Not the first, TM1's root logger: at DEBUG it floods the message log of the server every
    # test shares, with lines alike to the millisecond
    first = next(logger for logger in loggers if logger.name == "TM1.Blob")
    assert tm1.server.logger(first.name).level == first.level
    other = LogLevel.INFO if first.level == LogLevel.DEBUG else LogLevel.DEBUG
    tm1.server.set_logger_level(first.name, other)
    try:
        assert tm1.server.logger(first.name).level == other
    finally:
        tm1.server.set_logger_level(first.name, first.level)
