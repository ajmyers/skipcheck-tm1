"""Connect, read ``Server``, close: a port of the Java client's Tm1ClientTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Request

from .conftest import CONFIGURED, REASON, client, started_version

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_connects_reads_the_server_and_closes() -> None:
    tm1 = client()

    server = tm1.connect().session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"), dict))
    tm1.close()

    version = started_version()
    if version is None:
        assert server["ProductVersion"]
    else:
        assert server["ProductVersion"] == str(version)
    assert tm1.session.is_closed
    assert tm1.with_sandbox("x").session.sandbox == "x"
