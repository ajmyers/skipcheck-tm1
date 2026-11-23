"""Examples for SqlDataSourceService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client


def names(tm1: Tm1Client) -> list[str]:
    # @start region = names
    # The ODBC sources a process could read from, on TM1's host
    return [source.name for source in tm1.sql_data_sources.list() if source.name is not None]
    # @end
