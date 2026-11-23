# SPDX-License-Identifier: Apache-2.0

"""CubeService, in both flavors, against a fake TM1: each method sends what the Java
client's does, and reads TM1's answer the same way."""

from __future__ import annotations

import json

import httpx2
import pytest

from skipcheck_tm1 import Filter, ODataPath, ODataQuery, Tm1PreconditionFailedError
from skipcheck_tm1.model import CheckFeedersSelection, Cube, CubePatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx2.Request) -> object:
    return json.loads(request.content) if request.content else None


def test_lists_with_a_query(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"Name": "Sales"}]}
    query = ODataQuery.none().select(Cube.NAME).filter(Filter.starts_with(Cube.NAME, "Sales")).order_by(Cube.NAME)
    cubes = api.run(api.client.cubes.model_cubes(query))
    assert [c.name for c in cubes] == ["Sales"]
    assert target(fake.last) == "GET ModelCubes()?$select=Name&$filter=startswith(Name,'Sales')&$orderby=Name"
    api.run(api.client.cubes.list(top=2))
    assert target(fake.last) == "GET Cubes?$top=2"
    api.run(api.client.cubes.control_cubes())
    assert target(fake.last) == "GET ControlCubes()"


def test_gets_creates_updates_and_deletes(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Name": "Sales", "@odata.etag": 'W/"1"'}
    cube = api.run(api.client.cubes.get("Sales", select="Name"))
    assert (cube.name, cube.etag) == ("Sales", 'W/"1"')
    assert target(fake.last) == "GET Cubes('Sales')?$select=Name"

    dimensions = ODataPath.of("Dimensions")
    api.run(api.client.cubes.create(CubePatch.builder().name("Sales").bind_dimensions(dimensions.key("Region"), dimensions.key("Month")).build()))
    assert target(fake.last) == "POST Cubes"
    assert body(fake.last) == {"Name": "Sales", "Dimensions@odata.bind": ["Dimensions('Region')", "Dimensions('Month')"]}

    fake.respond = lambda r: None
    api.run(api.client.cubes.update("Sales", CubePatch.builder().rules(None).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Cubes('Sales')", {"Rules": None})
    assert "if-match" not in fake.last.headers
    api.run(api.client.cubes.update("Sales", Cube(rules="SKIPCHECK;"), etag='W/"1"'))
    assert fake.last.headers["if-match"] == 'W/"1"'
    api.run(api.client.cubes.delete("Sales"))
    assert target(fake.last) == "DELETE Cubes('Sales')"


def test_a_stale_etag_is_refused(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: httpx2.Response(412, json={"error": {"code": "278", "message": "stale"}})
    with pytest.raises(Tm1PreconditionFailedError):
        api.run(api.client.cubes.update("Sales", Cube(rules="x"), 'W/"0"'))


def test_operations(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    for method, action in (("load", "Load"), ("unload", "Unload"), ("lock", "Lock"), ("unlock", "Unlock"), ("reserve", "Reserve"), ("release", "Release")):
        api.run(getattr(api.client.cubes, method)("Sales"))
        assert (target(fake.last), body(fake.last)) == (f"POST Cubes('Sales')/tm1.{action}", {})

    fake.respond = lambda r: {"@odata.context": "$metadata#Edm.Double", "value": 12.5}
    assert api.run(api.client.cubes.reorder_dimensions("Sales", ["Month", "Region"])) == 12.5
    assert body(fake.last) == {"Dimensions@odata.bind": ["Dimensions('Month')", "Dimensions('Region')"]}

    fake.respond = lambda r: {"value": [{"LineNumber": 2, "Message": "Syntax error"}]}
    errors = api.run(api.client.cubes.check_rules("Sales", "SKIPCHECK"))
    assert (target(fake.last), body(fake.last)) == ("POST Cubes('Sales')/tm1.CheckRules", {"Rules": "SKIPCHECK"})
    assert errors[0].line_number == 2
    api.run(api.client.cubes.check_rules("Sales"))
    assert body(fake.last) == {}

    fake.respond = lambda r: {"value": [{"Name": "Month"}]}
    api.run(api.client.cubes.dimensions("Sales"))
    assert target(fake.last) == "GET Cubes('Sales')/Dimensions"
    api.run(api.client.cubes.dimensions_storage_order("Sales"))
    assert target(fake.last) == "GET Cubes('Sales')/tm1.DimensionsStorageOrder()"


def test_traces_by_cell(api: Api, fake: FakeTm1) -> None:
    cell = {"Region": "North", "Month": "Jan"}
    elements = ["Dimensions('Region')/Hierarchies('Region')/Elements('North')", "Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"]
    fake.respond = lambda r: {"Type": "Rule"}
    api.run(api.client.cubes.trace_cell_calculation("Sales", cell, expand="Components"))
    assert target(fake.last) == "POST Cubes('Sales')/tm1.TraceCellCalculation?$expand=Components"
    assert body(fake.last) == {"Tuple@odata.bind": elements}
    fake.respond = lambda r: {}
    api.run(api.client.cubes.trace_feeders("Sales", cell, ODataQuery.none().expand("FedCells/Tuple")))
    assert target(fake.last) == "POST Cubes('Sales')/tm1.TraceFeeders?$expand=FedCells/Tuple"
    fake.respond = lambda r: {"value": []}
    api.run(api.client.cubes.check_feeders("Sales", cell, list(CheckFeedersSelection)[0]))
    assert body(fake.last) == {"Tuple@odata.bind": elements, "Selection": list(CheckFeedersSelection)[0].value}
