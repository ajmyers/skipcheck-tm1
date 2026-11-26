# SPDX-License-Identifier: Apache-2.0

"""Version and capabilities read from a real TM1: a port of the Java client's
Tm1CapabilitiesTm1Test."""

from __future__ import annotations

import xml.etree.ElementTree as ET

import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1Capabilities,
    Tm1Capability,
    Tm1Identity,
    Tm1Request,
    Tm1Session,
    Tm1SessionPool,
    Tm1UnsupportedOperationError,
    auth,
)

from .conftest import CONFIGURED, REASON, _builder, session, started_version, target
from .support import own_snapshot, schema_version_in

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def _metadata(s: Tm1Session) -> str:
    return s.execute(Tm1Request.text(ODataPath.metadata()).with_header("Accept", "application/xml"))


def test_reads_the_version_and_the_capabilities_the_snapshot_describes() -> None:
    with session() as s:
        version = started_version()
        if version is not None:
            assert s.version() == version
        capabilities = s.capabilities()
        # The schema its $metadata says, read from the text, not by the client
        assert capabilities.schema_version == schema_version_in(_metadata(s))
        snapshot = own_snapshot(capabilities)

        container = next(e for e in ET.fromstring(snapshot).iter() if e.tag.endswith("}EntityContainer"))
        kinds = {"EntitySet": Tm1Capability.entity_set, "Singleton": Tm1Capability.singleton, "ActionImport": Tm1Capability.unbound_operation}
        declared = [(child.tag.rpartition("}")[2], child.get("Name")) for child in container]
        assert {"EntitySet", "Singleton", "ActionImport"} <= {kind for kind, _ in declared}
        missing = [name for kind, name in declared if kind in kinds and not capabilities.supports(kinds[kind](name))]
        assert missing == []
        reserve = Tm1Capability.bound_operation("tm1.Cube", "tm1.Reserve")
        assert capabilities.supports(reserve) == Tm1Capabilities.parse(snapshot).supports(reserve), "Reserve, from schema 11.8.34"


def test_refuses_a_request_the_server_doesnt_define() -> None:
    with session() as s:
        missing = Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none(), dict).requires(Tm1Capability.entity_set("Bananas").since("99.0"))
        with pytest.raises(Tm1UnsupportedOperationError) as refused:
            s.execute(missing)
        message = str(refused.value)
        assert "added in schema 99.0" in message
        assert f"reports schema {schema_version_in(_metadata(s))}" in message
        assert f"product version {s.version()}" in message


def test_shares_one_fetch_between_a_pools_sessions() -> None:
    _, user, password = target()
    pool = Tm1SessionPool.builder(_builder(Tm1Session.builder())).build()
    try:
        first = pool.session().capabilities()
        second = pool.session(Tm1Identity.with_credentials("again", auth.basic(user, password))).capabilities()
        assert second is first
    finally:
        pool.close()
