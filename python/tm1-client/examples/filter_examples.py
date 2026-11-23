# SPDX-License-Identifier: Apache-2.0

"""Examples for Filter, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Filter
from skipcheck_tm1.model import Element
from skipcheck_tm1.odata import ODataLiterals


def build() -> list[Filter]:
    # @start region = build
    numeric_q = Filter.and_(Filter.starts_with(Element.NAME, "Q"), Element.TYPE.eq(ODataLiterals.enum_member("ibm.tm1.api.v1.ElementType", "Numeric")))
    # (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
    has_region = Filter.any("Hierarchies", "h", lambda h: h.nav("Name").eq("Region"))
    # Hierarchies/any(h:h/Name eq 'Region')
    # @end
    return [numeric_q, has_region]
