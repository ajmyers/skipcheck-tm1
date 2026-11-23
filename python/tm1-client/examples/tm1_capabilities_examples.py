# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Capability and Tm1Capabilities, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Capability, Tm1Session


def supports(session: Tm1Session) -> bool:
    # @start region = supports
    # Does the server's $metadata define the cube's tm1.Reserve action?
    reserve = Tm1Capability.bound_operation("tm1.Cube", "tm1.Reserve").since("11.8.34")
    return session.capabilities().supports(reserve)
    # @end
