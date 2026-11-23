# SPDX-License-Identifier: Apache-2.0

"""Examples for ODataQuery, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Filter, ODataProperty, ODataQuery


def build() -> str:
    # @start region = build
    query = (
        ODataQuery.none()
        .select("Name", "Type")
        .expand("Hierarchies", lambda h: h.select("Name").top(5))
        .filter(Filter.starts_with(ODataProperty.of("Name"), "Q"))
        .to_query_string()
    )
    # $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q'), each value encoded
    # @end
    return query
