"""The client against a real TM1 (see conftest.py for how to point it at one)."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest
from pydantic import ValidationError

from tm1_client import Tm1ApiError, Tm1Client, Tm1ProtocolError, odata
from tm1_client._generated import index
from tm1_client.model import Cube, CubeUpdate, Dimension, Edge, Element, ElementType, Hierarchy

from ..conftest import SPEC
from .conftest import URL, options

pytestmark = pytest.mark.skipif(URL is None, reason="no TM1 configured (TM1_URL)")

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
            getattr(tm1, attr).list(top=3)
        except Tm1ApiError:
            pass
        except (ValidationError, Tm1ProtocolError) as e:
            failures.append(f"{name}: {e}")
    for name, attr in index.SINGLETONS.items():
        try:
            getattr(tm1, attr).get()
        except Tm1ApiError:
            pass
        except (ValidationError, Tm1ProtocolError) as e:
            failures.append(f"{name}: {e}")
    assert failures == []


def test_a_dimension_a_cube_and_a_cell(tm1: Tm1Client, name: str) -> None:
    year, region = name + "_year", name + "_region"
    for dim, elements in ((year, ["2025", "2026"]), (region, ["North", "South"])):
        tm1.dimensions.create(
            Dimension(
                name=dim,
                hierarchies=[
                    Hierarchy(
                        name=dim,
                        elements=[Element(name=e, type=ElementType.NUMERIC) for e in elements]
                        + [Element(name="Total", type=ElementType.CONSOLIDATED)],
                        edges=[Edge(parent_name="Total", component_name=e, weight=1.0) for e in elements],
                    )
                ],
            )
        )
    try:
        tm1.cubes.create(Cube(name=name, dimensions=[odata.Bind(f"Dimensions('{year}')"), odata.Bind(f"Dimensions('{region}')")]))
        try:
            cell = [odata.element(year, "2026"), odata.element(region, "North")]
            tm1.cubes[name].update_cells(updates=[CubeUpdate(tuple=cell, value=42)])
            mdx = f"SELECT {{[{year}].[2026]}} ON 0, {{[{region}].[North], [{region}].[Total]}} ON 1 FROM [{name}]"
            assert tm1.mdx_values(mdx) == [42, 42]

            # A batch reads both dimensions in one request
            with tm1.batch() as batch:
                a = batch.client.dimensions[year].get(select="Name")
                b = batch.client.dimensions[region].hierarchies[region].elements.count()
            assert a.result().name == year
            assert b.result() == 3

            # The same through the asyncio client
            async def read() -> Any:
                from tm1_client.aio import AsyncTm1Client

                async with AsyncTm1Client.connect(URL, **options()) as aio:  # type: ignore[arg-type]
                    return await aio.mdx_values(mdx)

            assert asyncio.run(read()) == [42, 42]
        finally:
            tm1.cubes[name].delete()
    finally:
        tm1.dimensions[year].delete()
        tm1.dimensions[region].delete()


def test_a_sandbox_keeps_its_writes_to_itself(tm1: Tm1Client, name: str) -> None:
    from tm1_client.model import Sandbox

    tm1.sandboxes.create(Sandbox(name=name))
    try:
        assert tm1.with_sandbox(name).sandboxes[name].get().name == name
    finally:
        tm1.sandboxes[name].delete()
