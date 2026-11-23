# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Timeouts, quoted in the guide."""

from __future__ import annotations

from datetime import timedelta

from skipcheck_tm1 import Tm1Timeouts


def long_requests() -> Tm1Timeouts:
    # @start region = long-requests
    # Let a request go two hours without data, for a long process run synchronously
    timeouts = Tm1Timeouts.builder().request(timedelta(hours=2)).build()
    # @end
    return timeouts
