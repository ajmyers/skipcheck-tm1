"""OidcKeyService and SqlDataSourceService against a real TM1: a port of the Java client's
OidcKeyAndSqlDataSourceTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    Tm1Client,
    Tm1NotFoundError,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_and_clears_the_oidc_key_cache(tm1: Tm1Client) -> None:
    assert tm1.oidc_keys.keys() == []
    tm1.oidc_keys.remove([])
    with pytest.raises(Tm1NotFoundError, match="ObjectNotFound"):
        # TM1 answers 400 ObjectNotFound, read as not found
        tm1.oidc_keys.remove(["not-cached"])


def test_lists_the_hosts_odbc_sources(tm1: Tm1Client) -> None:
    assert tm1.sql_data_sources.list() == []
    with pytest.raises(Tm1NotFoundError):
        tm1.sql_data_sources.get("none")
