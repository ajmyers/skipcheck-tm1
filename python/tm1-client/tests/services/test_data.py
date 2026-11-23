# SPDX-License-Identifier: Apache-2.0

"""The data services (cellsets, cells, member sets, sandboxes, change sets, drillthrough) and
Tuples, in both flavors, against a fake TM1."""

from __future__ import annotations

import json
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import ODataQuery, Tm1NotFoundError
from skipcheck_tm1.aio.services.cube import ViewScope
from skipcheck_tm1.aio.services.data import Tuples
from skipcheck_tm1.model import (
    CellsetCell,
    CellsetUpdatePatch,
    ConflictResolution,
    CubeDrillthrough,
    CubeUpdatePatch,
    MDXView,
    ProcessParameterPatch,
    SandboxPatch,
    UpdateOrder,
)

from ..conftest import Api, FakeTm1, target

NORTH = "Dimensions('Region')/Hierarchies('Region')/Elements('North')"
JAN = "Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"
CELL = {"Region": "North", "Month": "Jan"}


def body(request: httpx2.Request) -> Any:
    return json.loads(request.content) if request.content else None


def handler(api: Api, read: Any = None) -> Any:
    """A stream handler of the client's flavor: the cellset and its cells, as a list."""
    if api.flavor == "async":

        async def collect(cellset: Any, cells: Any) -> Any:
            if read is not None:
                read(cellset)
            return cellset, [c async for c in cells]

        return collect

    def collect_sync(cellset: Any, cells: Any) -> Any:
        if read is not None:
            read(cellset)
        return cellset, list(cells)

    return collect_sync


# Tuples


def test_tuples_bind_a_cell_in_each_dimensions_own_hierarchy() -> None:
    assert [str(p) for p in Tuples.of(CELL)] == [NORTH, JAN]
    assert str(Tuples.element("Region", "By Size", "Big Co")) == "Dimensions('Region')/Hierarchies('By%20Size')/Elements('Big%20Co')"


# Cells


def test_writes_one_cell(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.cells.update("Sales", CELL, 1200))
    assert target(fake.last) == "POST Cubes('Sales')/tm1.Update"
    assert body(fake.last) == {"Cells": [{"Tuple@odata.bind": [NORTH, JAN]}], "Value": 1200}
    api.run(api.client.cells.update("Sales", CELL, None))
    assert body(fake.last)["Value"] is None


def test_writes_many_cells(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    updates = [CubeUpdatePatch.builder().bind_tuple(*Tuples.of(CELL)).value(v).build() for v in (1, 2)]
    api.run(api.client.cells.update_cells("Sales", updates))
    assert target(fake.last) == "POST Cubes('Sales')/tm1.UpdateCells"
    assert body(fake.last) == {"Updates": [{"Tuple@odata.bind": [NORTH, JAN], "Value": 1}, {"Tuple@odata.bind": [NORTH, JAN], "Value": 2}]}
    api.run(api.client.cells.update_cells("Sales", updates, UpdateOrder.BY_LEVEL))
    assert body(fake.last)["Order"] == "ByLevel"


# Cellsets


def test_executes_mdx_statements_and_views(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"ID": "cs1"}
    assert api.run(api.client.cellsets.execute("SELECT", expand="Cells($select=Value)")).id == "cs1"
    assert target(fake.last).startswith("POST ExecuteMDX?$expand=Cells")
    assert body(fake.last) == {"MDX": "SELECT"}
    fake.respond = lambda r: None
    api.run(api.client.cellsets.execute_statement("SELECT x"))
    assert (target(fake.last), body(fake.last)) == ("POST ExecuteMDXStatement", {"MDX": "SELECT x"})
    fake.respond = lambda r: {"ID": "cs2"}
    api.run(api.client.cellsets.execute_view("Sales", ViewScope.PRIVATE, "v"))
    assert (target(fake.last), body(fake.last)) == ("POST Cubes('Sales')/PrivateViews('v')/tm1.Execute", {})


def test_reads_a_cellset_and_its_parts(api: Api, fake: FakeTm1) -> None:
    cellsets = api.client.cellsets
    fake.respond = lambda r: {"value": []}
    for call, expected in (
        (lambda: cellsets.list(), "GET Cellsets"),
        (lambda: cellsets.axes("cs", expand="Tuples"), "GET Cellsets('cs')/Axes?$expand=Tuples"),
        (lambda: cellsets.hierarchies("cs", 1), "GET Cellsets('cs')/Axes(1)/Hierarchies"),
        (lambda: cellsets.tuples("cs", 0, top=5), "GET Cellsets('cs')/Axes(0)/Tuples?$top=5"),
        (lambda: cellsets.tuple_members("cs", 0, 2), "GET Cellsets('cs')/Axes(0)/Tuples(2)/Members"),
        (lambda: cellsets.cells("cs", select="Value"), "GET Cellsets('cs')/Cells?$select=Value"),
        (lambda: cellsets.cell_members("cs", 7), "GET Cellsets('cs')/Cells(7)/Members"),
        (lambda: cellsets.partition("cs", 2, 4), "GET Cellsets('cs')/tm1.GetPartition(Begin=2,End=4)"),
    ):
        assert api.run(call()) == []
        assert target(fake.last) == expected
    fake.respond = lambda r: {"ID": "cs"}
    assert api.run(cellsets.get("cs")).id == "cs"
    assert target(fake.last) == "GET Cellsets('cs')"
    fake.respond = lambda r: {"Name": "Sales"}
    assert api.run(cellsets.cube("cs")).name == "Sales"
    assert target(fake.last) == "GET Cellsets('cs')/Cube"
    fake.respond = lambda r: None
    api.run(cellsets.delete("cs"))
    assert target(fake.last) == "DELETE Cellsets('cs')"


def test_writes_into_a_cellset_by_ordinal(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.cellsets.update("cs", 3, 42))
    assert (target(fake.last), body(fake.last)) == (
        "POST Cellsets('cs')/tm1.Update",
        {"BeginOrdinal": 3, "EndOrdinal": 3, "Value": 42},
    )
    updates = [CellsetUpdatePatch.builder().ordinal(0).value(1200).build(), CellsetUpdatePatch.builder().ordinal(1).value(1350).build()]
    api.run(api.client.cellsets.update_cells("cs", updates))
    assert body(fake.last) == {"Updates": [{"Ordinal": 0, "Value": 1200}, {"Ordinal": 1, "Value": 1350}]}


def test_saves_a_cellset_as_a_view(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.MDXView", "Name": "v"}
    assert isinstance(api.run(api.client.cellsets.save_view_as("cs", "v")), MDXView)
    assert (target(fake.last), body(fake.last)) == ("POST Cellsets('cs')/tm1.SaveViewAs", {"Name": "v"})
    api.run(api.client.cellsets.save_view_as("cs", "v", True, False))
    assert body(fake.last) == {"Name": "v", "Overwrite": True, "MakePrivate": False}


def _cellset_server(cardinalities: list[int], inline: int) -> Any:
    total = 1
    for c in cardinalities:
        total *= c

    def cells(begin: int, end: int) -> list[dict[str, Any]]:
        return [{"Ordinal": n, "Value": n * 10} for n in range(begin, end + 1)]

    def respond(request: httpx2.Request) -> Any:
        if request.method == "POST":
            return {
                "ID": "cs1",
                "Axes": [{"Ordinal": i, "Cardinality": c} for i, c in enumerate(cardinalities)],
                "Cells": cells(0, min(inline, total) - 1),
            }
        if request.method == "DELETE":
            return None
        path = request.url.raw_path.decode()
        if "GetPartition" in path:
            begin = int(path.split("Begin=")[1].split(",")[0])
            end = int(path.split("End=")[1].split(")")[0])
            return {"value": cells(begin, end)}
        skip, top = int(request.url.params["$skip"]), int(request.url.params["$top"])
        return {"value": cells(skip, min(skip + top, total) - 1)}

    return respond


def test_streams_two_axes_in_whole_rows_then_deletes_the_cellset(api: Api, fake: FakeTm1) -> None:
    # Two columns, three rows; the first page (3 cells) ends mid-row, so its last cell is
    # fetched again with the next row
    fake.respond = _cellset_server([2, 3], inline=3)
    cellset, cells = api.run(api.client.cellsets.stream("SELECT", ODataQuery.none().select(CellsetCell.ORDINAL, CellsetCell.VALUE), 3, handler(api)))
    assert cellset.id == "cs1"
    assert [c.ordinal for c in cells] == [0, 1, 2, 3, 4, 5]
    first = fake.requests[0]
    assert first.url.params["$expand"] == (
        "Axes($expand=Hierarchies($select=Name),Tuples($expand=Members($select=Name,UniqueName))),Cells($select=Ordinal,Value;$top=3)"
    )
    assert [target(r).split("?")[0] for r in fake.requests[1:]] == [
        "GET Cellsets('cs1')/tm1.GetPartition(Begin=2,End=3)",
        "GET Cellsets('cs1')/tm1.GetPartition(Begin=4,End=5)",
        "DELETE Cellsets('cs1')",
    ]


def test_streams_more_axes_by_skip_and_top(api: Api, fake: FakeTm1) -> None:
    fake.respond = _cellset_server([2, 2, 2], inline=3)
    _, cells = api.run(api.client.cellsets.stream("SELECT", ODataQuery.none().select(CellsetCell.ORDINAL), 3, handler(api)))
    assert [c.ordinal for c in cells] == list(range(8))
    pages = [(r.url.params["$skip"], r.url.params["$top"]) for r in fake.requests if "Cells" in r.url.raw_path.decode() and r.method == "GET"]
    assert pages == [("3", "3"), ("6", "3")]
    assert target(fake.last) == "DELETE Cellsets('cs1')"


def test_a_small_cellset_comes_whole_in_the_first_round_trip(api: Api, fake: FakeTm1) -> None:
    fake.respond = _cellset_server([2, 1], inline=10)
    _, cells = api.run(api.client.cellsets.stream("SELECT", None, 10, handler(api)))
    assert len(cells) == 2
    assert [r.method for r in fake.requests] == ["POST", "DELETE"]


def test_a_failing_handler_still_deletes_the_cellset(api: Api, fake: FakeTm1) -> None:
    fake.respond = _cellset_server([1, 1], inline=1)

    def fail(cellset: Any) -> None:
        raise RuntimeError("the handler failed")

    with pytest.raises(RuntimeError):
        api.run(api.client.cellsets.stream("SELECT", None, 1, handler(api, fail)))
    assert target(fake.last) == "DELETE Cellsets('cs1')"
    with pytest.raises(ValueError):
        api.run(api.client.cellsets.stream("SELECT", None, 0, handler(api)))


def test_a_handler_that_stops_reading_early_still_deletes_the_cellset(api: Api, fake: FakeTm1) -> None:
    # Java: deletesTheCellsetWhenTheStreamIsCancelled
    fake.respond = _cellset_server([3, 4], inline=3)
    if api.flavor == "async":

        async def first_four(cellset: Any, cells: Any) -> Any:
            out = []
            async for c in cells:
                out.append(c.ordinal)
                if len(out) == 4:
                    break
            return out

    else:

        def first_four(cellset: Any, cells: Any) -> Any:
            out = []
            for c in cells:
                out.append(c.ordinal)
                if len(out) == 4:
                    break
            return out

    assert api.run(api.client.cellsets.stream("SELECT", ODataQuery.none().select(CellsetCell.ORDINAL), 3, first_four)) == [0, 1, 2, 3]
    assert target(fake.last) == "DELETE Cellsets('cs1')"


# Member sets


def test_member_sets(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Ordinal": 1, "Tuples": [{"Members": [{"Name": "North"}]}]}
    members = api.run(api.client.member_sets.execute("{[Region].[North]}", expand="Tuples($expand=Members)"))
    assert members.ordinal == 1 and members.tuples[0].members[0].name == "North"
    assert target(fake.last).startswith("POST ExecuteMDXSetExpression?$expand=")
    assert body(fake.last) == {"MDX": "{[Region].[North]}"}
    api.run(api.client.member_sets.get(1, expand="Tuples"))
    assert target(fake.last) == "GET MemberSets(1)?$expand=Tuples"
    fake.respond = lambda r: {"value": []}
    assert api.run(api.client.member_sets.list()) == []
    assert target(fake.last) == "GET MemberSets"
    fake.respond = lambda r: None
    api.run(api.client.member_sets.delete(1))
    assert target(fake.last) == "DELETE MemberSets(1)"


# Sandboxes


def test_sandboxes(api: Api, fake: FakeTm1) -> None:
    sandboxes = api.client.sandboxes
    fake.respond = lambda r: {"Name": "What If"}
    assert api.run(sandboxes.create(SandboxPatch.builder().name("What If").build())).name == "What If"
    assert (target(fake.last), body(fake.last)) == ("POST Sandboxes", {"Name": "What If"})
    api.run(sandboxes.get("What If", select="Name"))
    assert target(fake.last) == "GET Sandboxes('What%20If')?$select=Name"
    fake.respond = lambda r: {"value": [{"Name": "a"}]}
    assert [s.name for s in api.run(sandboxes.list())] == ["a"]
    fake.respond = lambda r: None
    api.run(sandboxes.update("a", SandboxPatch.builder().include_in_sandbox_dimension(False).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Sandboxes('a')", {"IncludeInSandboxDimension": False})
    for method, action in (("load", "Load"), ("unload", "Unload"), ("discard_changes", "DiscardChanges"), ("publish", "Publish")):
        api.run(getattr(sandboxes, method)("a"))
        assert (target(fake.last), body(fake.last)) == (f"POST Sandboxes('a')/tm1.{action}", {})
    api.run(sandboxes.publish("a", ConflictResolution.USING_SOURCE, True, False))
    assert body(fake.last) == {"ConflictResolution": "UsingSource", "AllowQueueing": True, "CleanAfter": False}
    api.run(sandboxes.merge("a", "b"))
    assert (target(fake.last), body(fake.last)) == ("POST Sandboxes('a')/tm1.Merge", {"Target@odata.bind": "Sandboxes('b')"})
    api.run(sandboxes.merge("a", "b", ConflictResolution.INDIVIDUALLY, False, True))
    assert body(fake.last) == {
        "Target@odata.bind": "Sandboxes('b')",
        "ConflictResolution": "Individually",
        "AllowQueueing": False,
        "CleanAfter": True,
    }
    with pytest.raises(ValueError):
        api.run(sandboxes.publish("a", allow_queueing=True))
    api.run(sandboxes.delete("a"))
    assert target(fake.last) == "DELETE Sandboxes('a')"


def test_writes_in_a_sandbox_through_a_view(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.with_sandbox("What If").cells.update("Sales", CELL, 1500))
    assert target(fake.last) == "POST Cubes('Sales')/tm1.Update?!sandbox=What%20If"


# Change sets


def _change_set_server(fake: FakeTm1) -> None:
    def respond(request: httpx2.Request) -> Any:
        if target(request) == "POST BeginChangeSet":
            return {"@odata.context": "$metadata#Edm.String", "value": "3"}  # TM1 wraps the ID
        if target(request) == "POST EndChangeSet":
            return {"value": 0}
        return None

    fake.respond = respond


def test_begins_ends_and_undoes(api: Api, fake: FakeTm1) -> None:
    _change_set_server(fake)
    assert api.run(api.client.change_sets.begin()) == "3"
    assert api.run(api.client.change_sets.end("3")) == 0
    assert body(fake.last) == {"ChangeSetID": "3"}
    api.run(api.client.change_sets.undo("3"))
    assert (target(fake.last), body(fake.last)) == ("POST UndoChangeSet", {"ChangeSetID": "3"})


def test_within_records_the_work_and_ends_however_it_finishes(api: Api, fake: FakeTm1) -> None:
    from skipcheck_tm1 import Tm1Client
    from skipcheck_tm1.aio import AsyncTm1Client

    _change_set_server(fake)
    if api.flavor == "async":

        async def write(session: Any) -> Any:
            await AsyncTm1Client.of(session).cells.update("Sales", CELL, 1500)
            return session.change_set

        async def fail(session: Any) -> Any:
            raise RuntimeError("the work failed")

    else:

        def write(session: Any) -> Any:
            Tm1Client.of(session).cells.update("Sales", CELL, 1500)
            return session.change_set

        def fail(session: Any) -> Any:
            raise RuntimeError("the work failed")

    assert api.run(api.client.change_sets.within(write)) == "3"
    assert [target(r) for r in fake.requests] == [
        "POST BeginChangeSet",
        "POST Cubes('Sales')/tm1.Update?!ChangeSet=3",
        "POST EndChangeSet",
    ]
    fake.requests.clear()
    with pytest.raises(RuntimeError):
        api.run(api.client.change_sets.within(fail))
    assert [target(r) for r in fake.requests] == ["POST BeginChangeSet", "POST EndChangeSet"]


def test_within_does_no_work_if_the_change_set_cant_begin(api: Api, fake: FakeTm1) -> None:
    # Java: doesNoWorkIfTheChangeSetCantBegin
    from skipcheck_tm1 import Tm1ServerError

    fake.respond = lambda request: httpx2.Response(500, json={"error": {"code": "1", "message": "down"}})
    ran = []

    def work(session: Any) -> Any:
        ran.append(session)
        return "never"

    async def awork(session: Any) -> Any:
        return work(session)

    with pytest.raises(Tm1ServerError):
        api.run(api.client.change_sets.within(awork if api.flavor == "async" else work))
    assert ran == []
    assert [target(r) for r in fake.requests] == ["POST BeginChangeSet"]


def test_recording_is_within_as_a_context_manager(client: Any, fake: FakeTm1) -> None:
    from skipcheck_tm1 import Tm1Client

    _change_set_server(fake)
    with pytest.raises(RuntimeError):
        with client.change_sets.recording() as session:
            assert session.change_set == "3"
            Tm1Client.of(session).cells.update("Sales", CELL, 1)
            raise RuntimeError("the block failed")
    assert [target(r) for r in fake.requests] == [
        "POST BeginChangeSet",
        "POST Cubes('Sales')/tm1.Update?!ChangeSet=3",
        "POST EndChangeSet",
    ]


# Drillthrough


def test_drills_from_a_cell(api: Api, fake: FakeTm1) -> None:
    drillthrough = api.client.drillthrough
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.CubeDrillthrough", "Name": "Detail"}]}
    scripts = api.run(drillthrough.scripts("cs", 0))
    assert isinstance(scripts[0], CubeDrillthrough)
    assert target(fake.last) == "GET Cellsets('cs')/Cells(0)/DrillthroughScripts"
    fake.respond = lambda r: {"ID": "detail"}
    assert api.run(drillthrough.execute("cs", 0, "Detail", expand="Cells")).id == "detail"
    assert target(fake.last) == "POST Cellsets('cs')/Cells(0)/DrillthroughScripts('Detail')/tm1.Execute?$expand=Cells"
    fake.respond = lambda r: {"value": [{"OrderNumber": "A1"}]}
    assert api.run(drillthrough.execute_relational("cs", 0, "Orders")) == [{"OrderNumber": "A1"}]
    assert target(fake.last) == "POST Cellsets('cs')/Cells(0)/DrillthroughScripts('Orders')/tm1.Execute"
    fake.respond = lambda r: {"ID": "rs"}
    assert api.run(drillthrough.execute_relational_with_rowset("cs", 0, "Orders")).id == "rs"
    assert target(fake.last) == "POST Cellsets('cs')/Cells(0)/DrillthroughScripts('Orders')/tm1.ExecuteWithRowset"


def test_runs_drillthrough_processes(api: Api, fake: FakeTm1) -> None:
    drillthrough = api.client.drillthrough
    parameters = [ProcessParameterPatch.builder().name("pRegion").value("North").build()]
    process = "Processes('%7DDrill_Sales')"
    fake.respond = lambda r: {"ID": "detail"}
    api.run(drillthrough.execute_process("}Drill_Sales", parameters, expand="Cells"))
    assert target(fake.last) == "POST ExecuteCubeDrillthrough?$expand=Cells"
    assert body(fake.last) == {
        "DrillthroughProcess@odata.bind": process,
        "Parameters": [{"Name": "pRegion", "Value": "North"}],
    }
    fake.respond = lambda r: {"value": [{"a": 1}]}
    assert api.run(drillthrough.execute_relational_process("}Drill_Sales", [])) == [{"a": 1}]
    assert (target(fake.last), body(fake.last)) == ("POST ExecuteRelationalDrillthrough", {"DrillthroughProcess@odata.bind": process})
    fake.respond = lambda r: {"ID": "rs"}
    api.run(drillthrough.execute_relational_process_with_rowset("}Drill_Sales", parameters))
    assert target(fake.last) == "POST ExecuteRelationalDrillthroughWithRowset"


def test_reads_rowsets(api: Api, fake: FakeTm1) -> None:
    drillthrough = api.client.drillthrough
    fake.respond = lambda r: {"ID": "rs"}
    assert api.run(drillthrough.rowset("rs")).id == "rs"
    assert target(fake.last) == "GET Rowsets('rs')"
    fake.respond = lambda r: {"value": [{"OrderNumber": "A1"}]}
    rows = api.run(drillthrough.rows("rs", top=10))
    assert rows[0].model_extra["OrderNumber"] == "A1"
    assert target(fake.last) == "GET Rowsets('rs')/Rows?$top=10"
    fake.respond = lambda r: httpx2.Response(404, json={"error": {"message": "gone"}})
    with pytest.raises(Tm1NotFoundError):
        api.run(drillthrough.delete_rowset("rs"))


def test_operations_are_checked_against_the_server(api: Api, fake: FakeTm1) -> None:
    """Every operation request requires its capability, so a server without it refuses first."""
    from skipcheck_tm1 import Tm1UnsupportedOperationError

    fake.metadata = fake.metadata.replace(b'Name="ExecuteMDXStatement"', b'Name="SomethingElse"')
    with pytest.raises(Tm1UnsupportedOperationError, match="ExecuteMDXStatement"):
        api.run(api.client.cellsets.execute_statement("SELECT"))
    assert fake.requests == []
