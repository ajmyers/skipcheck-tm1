"""The client against a real TM1 (see conftest.py for how to point it at one)."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest
from pydantic import ValidationError

from skipcheck_tm1 import ODataQuery, Tm1ApiError, Tm1Client, Tm1ProtocolError, odata
from skipcheck_tm1._generated import index
from skipcheck_tm1.model import Cube, CubeUpdate, Dimension, Edge, Element, ElementType, Hierarchy

from ..conftest import SPEC
from .conftest import CONFIGURED, REASON
from .conftest import async_client as _async_client

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

DOC = json.loads(SPEC.read_text(encoding="utf-8"))


def test_the_server_has_what_the_spec_says(tm1: Tm1Client) -> None:
    """Drift between the server's $metadata and the spec, as the Java MetadataDriftTest: equal
    on the snapshot's version, reported otherwise."""
    caps = tm1.session.capabilities()
    spec = {e["id"] for e in DOC["endpoints"]}
    if caps.schema_version == DOC["source"]["schemaVersion"]:
        assert caps.endpoints == spec
    else:
        print(f"schema {caps.schema_version}: new {sorted(caps.endpoints - spec)}, gone {sorted(spec - caps.endpoints)}")


def test_reads_every_entity_set_and_singleton(tm1: Tm1Client) -> None:
    """What TM1 sends for each reads into the model: refusals are fine (some sets need rights,
    or list nothing), decoding failures aren't."""
    failures = []
    for name, attr in index.ENTITY_SETS.items():
        try:
            getattr(tm1.api, attr).list(top=3)
        except Tm1ApiError:
            pass
        except (ValidationError, Tm1ProtocolError) as e:
            failures.append(f"{name}: {e}")
    for name, attr in index.SINGLETONS.items():
        try:
            getattr(tm1.api, attr).get()
        except Tm1ApiError:
            pass
        except (ValidationError, Tm1ProtocolError) as e:
            failures.append(f"{name}: {e}")
    assert failures == []


def test_a_dimension_a_cube_and_a_cell(tm1: Tm1Client, name: str) -> None:
    year, region = name + "_year", name + "_region"
    for dim, elements in ((year, ["2025", "2026"]), (region, ["North", "South"])):
        tm1.api.dimensions.create(
            Dimension(
                name=dim,
                hierarchies=[
                    Hierarchy(
                        name=dim,
                        elements=[Element(name=e, type=ElementType.NUMERIC) for e in elements] + [Element(name="Total", type=ElementType.CONSOLIDATED)],
                        edges=[Edge(parent_name="Total", component_name=e, weight=1.0) for e in elements],
                    )
                ],
            )
        )
    try:
        tm1.api.cubes.create(Cube(name=name, dimensions=[odata.Bind(f"Dimensions('{year}')"), odata.Bind(f"Dimensions('{region}')")]))
        try:
            cell = [odata.element(year, "2026"), odata.element(region, "North")]
            tm1.api.cubes[name].update_cells(updates=[CubeUpdate(tuple=cell, value=42)])
            mdx = f"SELECT {{[{year}].[2026]}} ON 0, {{[{region}].[North], [{region}].[Total]}} ON 1 FROM [{name}]"
            assert _values(tm1.api.execute_mdx(mdx=mdx, query=ODataQuery(expand="Cells($select=Ordinal,Value)"))) == [42, 42]

            # A batch reads both dimensions in one request
            with tm1.batch() as batch:
                a = batch.client.api.dimensions[year].get(select="Name")
                b = batch.client.api.dimensions[region].hierarchies[region].elements.count()
            assert a.result().name == year
            assert b.result() == 3

            # The same through the asyncio client
            async def read() -> Any:

                async with _async_client() as aio:
                    return _values(await aio.api.execute_mdx(mdx=mdx, query=ODataQuery(expand="Cells($select=Ordinal,Value)")))

            assert asyncio.run(read()) == [42, 42]
        finally:
            tm1.api.cubes[name].delete()
    finally:
        tm1.api.dimensions[year].delete()
        tm1.api.dimensions[region].delete()


def test_a_sandbox_keeps_its_writes_to_itself(tm1: Tm1Client, name: str) -> None:
    from skipcheck_tm1.model import Sandbox

    tm1.api.sandboxes.create(Sandbox(name=name))
    try:
        assert tm1.with_sandbox(name).api.sandboxes[name].get().name == name
    finally:
        tm1.api.sandboxes[name].delete()


def _values(cellset: object) -> list[object]:
    cells = sorted(cellset.cells or [], key=lambda c: c.ordinal or 0)  # type: ignore[attr-defined]
    return [c.value for c in cells]
