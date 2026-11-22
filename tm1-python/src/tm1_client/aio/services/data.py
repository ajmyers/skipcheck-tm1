"""Services for data: cellsets, cell writes, MDX, sandboxes, change sets and drillthrough (the
Java client's ``net.ajmyers.tm1.client.service.data``)."""

from __future__ import annotations

import math
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Awaitable, Callable, Mapping, Sequence

from ..._calls import endpoint
from ..._generated.model import (
    Cellset,
    CellsetAxis,
    CellsetAxisTuple,
    CellsetCell,
    ConflictResolution,
    Cube,
    Drillthrough,
    Hierarchy,
    Member,
    Rowset,
    RowsetRow,
    Sandbox,
    UpdateOrder,
    View,
)
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ..session import AsyncTm1Session
from ._base import Service, _require
from ...values import Tuples
from .cube import ViewScope

__all__ = [
    "CellService",
    "CellsetService",
    "ChangeSetService",
    "DrillthroughService",
    "MemberSetService",
    "SandboxService",
    "Tuples",
]

_CELLSETS = ODataPath.of("Cellsets")
_MEMBER_SETS = ODataPath.of("MemberSets")
_SANDBOXES = ODataPath.of("Sandboxes")
_ROWSETS = ODataPath.of("Rowsets")


def _import(operation: Tm1Operation) -> ODataPath:
    assert operation.import_name is not None
    return ODataPath.of(operation.import_name)


def _bindings(tuple: Mapping[str, str]) -> list[str]:
    return [str(p) for p in Tuples.of(tuple)]


class CellService(Service):
    """Cell writes to a cube, by coordinates. Cells are given as :meth:`Tuples.of` mappings of
    dimension to element; reading is through cellsets (:class:`CellsetService`), and writing
    into one by ordinal is there too.

    Values are numbers for numeric cells and text for string cells; ``None`` clears a cell.
    TM1 (11.8) refuses a write to a consolidated cell (400,
    ``CubeCellWriteStatusElementIsConsolidated``): it doesn't spread; write the leaves.

    ``tm1.Update`` takes several cells, but TM1 (11.8) writes only the first, so :meth:`update`
    takes one; :meth:`update_cells` writes many in one request.

    Write one cell, given an element for every dimension of the cube, in order::

        cell = {"Version": "Budget", "Region": "North", "Month": "Jan", "Measure": "Revenue"}
        await tm1.cells.update("Sales", cell, 1200)

    Write many cells in one request::

        budget = {"Jan": 1200, "Feb": 1350, "Mar": 1100}
        updates = [
            CubeUpdatePatch.builder()
            .bind_tuple(*Tuples.of({"Version": "Budget", "Region": "North", "Month": month, "Measure": "Revenue"}))
            .value(value)
            .build()
            for month, value in budget.items()
        ]
        await tm1.cells.update_cells("Sales", updates)
    """

    @endpoint("Action Cube/Update", "Navigation CellDescriptor/Tuple")
    async def update(self, cube: str, tuple: Mapping[str, str], value: Any) -> None:
        """``tm1.Update``: writes one value to a cell; ``tuple`` maps dimension to element, as for
        :meth:`Tuples.of`; ``None`` clears the cell."""
        body = {"Cells": [{"Tuple@odata.bind": _bindings(tuple)}], "Value": value}
        await self._session.execute(_cube_action(Tm1Operations.CUBE_UPDATE, cube, body))

    @endpoint(
        "Action Cube/UpdateCells",
        "Navigation CubeUpdate/Tuple",
        "Navigation CubeUpdate/ReferenceCube",
        "Navigation CubeUpdate/ReferenceCell",
    )
    async def update_cells(self, cube: str, updates: Sequence[Any], order: UpdateOrder | None = None) -> None:
        """``tm1.UpdateCells``: writes many cells, each its own value, in one request: each update
        ``CubeUpdatePatch.builder().bind_tuple(*Tuples.of(cell)).value(v)``, or with a reference
        cube and cell to copy from. Needs schema 11.0.0.

        With ``order``, ``ByLevel`` writes leaves before the consolidations above them, so a
        spread to a consolidation isn't overwritten.

        TM1 (11.8) requires a ``Tuple`` and a ``Value`` in every update (400 otherwise). A
        ``ReferenceCube`` and ``ReferenceCell`` didn't change what's written on a leaf, and
        don't copy that cell's value."""
        body: dict[str, Any] = {"Updates": list(_require(updates, "updates"))}
        if order is not None:
            body["Order"] = order
        await self._session.execute(_cube_action(Tm1Operations.CUBE_UPDATE_CELLS, cube, body))


def _cube_action(operation: Tm1Operation, cube: str, body: Mapping[str, Any]) -> Tm1Request[Any]:
    path = ODataPath.of("Cubes").key(_require(cube, "cube")).action(operation.name)
    return Tm1Request.post(path, dict(body), None).requires(operation.capability())


class CellsetService(Service):
    """Cellsets: the server-side results of an MDX query or a view, with their axes and cells.

    A cellset lives on the server until it's deleted, or the session ends; one that isn't
    deleted holds the server's memory. The 1:1 methods (:meth:`execute`, :meth:`cells`,
    :meth:`partition`, :meth:`delete`) leave that to the caller. :meth:`stream` does it all:
    runs the query, pages the cells in order, and deletes the cellset however the handler
    finishes.

    Read the cells of an MDX query, placing each by its ordinal on the axes::

        mdx = (
            "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb], [Month].[Month].[Mar]} ON 0,"
            " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]"
            " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])"
        )

        async def place(cellset, cells):
            # Axis 0 is the columns, axis 1 the rows; ordinals run along a row
            columns, rows = cellset.axes[0].tuples, cellset.axes[1].tuples
            return [
                f"{rows[c.ordinal // len(columns)].members[0].name}, "
                f"{columns[c.ordinal % len(columns)].members[0].name}: {c.value}"
                async for c in cells
            ]

        lines = await tm1.cellsets.stream(
            mdx, ODataQuery.none().select(CellsetCell.ORDINAL, CellsetCell.VALUE), 10_000, place
        )

    Write by ordinal into the cells of a query, then delete its cellset; the cells must be
    leaves, as TM1 doesn't spread to a consolidation::

        cellset = await tm1.cellsets.execute(mdx)
        try:
            await tm1.cellsets.update_cells(cellset.id, [
                CellsetUpdatePatch.builder().ordinal(0).value(1200).build(),
                CellsetUpdatePatch.builder().ordinal(1).value(1350).build(),
            ])
        finally:
            await tm1.cellsets.delete(cellset.id)

    Read the values of a saved view, in the same round trip as its cellset::

        cellset = await tm1.cellsets.execute_view(
            "Sales", ViewScope.PUBLIC, "Revenue by Region",
            ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.VALUE)),
        )
        try:
            values = [cell.value for cell in cellset.cells]
        finally:
            await tm1.cellsets.delete(cellset.id)
    """

    @endpoint("ActionImport ExecuteMDX")
    async def execute(self, mdx: str, query: ODataQuery | None = None, **options: Any) -> Cellset:
        """``ExecuteMDX``: runs an MDX query, leaving its cellset on the server until
        :meth:`delete`. Expand ``Axes`` and ``Cells`` (with ``$top`` and ``$select``) to get them
        in the same round trip. The cellset has its ``ID``."""
        operation = Tm1Operations.EXECUTE_MDX
        request = Tm1Request.post(_import(operation), {"MDX": _require(mdx, "mdx")}, Cellset)
        return await self._session.execute(
            request.with_query(ODataQuery.of(query, **options)).requires(operation.capability())
        )

    @endpoint("ActionImport ExecuteMDXStatement")
    async def execute_statement(self, mdx: str) -> None:
        """``ExecuteMDXStatement``: runs an MDX statement for its effect, keeping no cellset.
        Needs schema 11.8.20. TM1 (11.8) runs a ``SELECT`` and returns nothing, and rejects
        ``CREATE SET`` as a syntax error (code 501)."""
        operation = Tm1Operations.EXECUTE_MDX_STATEMENT
        request = Tm1Request.post(_import(operation), {"MDX": _require(mdx, "mdx")}, None)
        await self._session.execute(request.requires(operation.capability()))

    @endpoint("Action NativeView/Execute", "Action MDXView/Execute")
    async def execute_view(
        self, cube: str, scope: ViewScope, view: str, query: ODataQuery | None = None, **options: Any
    ) -> Cellset:
        """``tm1.Execute`` on a native or MDX view: its cellset, with its ``ID``, left on the
        server until :meth:`delete`."""
        path = (
            ODataPath.of("Cubes")
            .key(_require(cube, "cube"))
            .nav(_require(scope, "scope").navigation())
            .key(_require(view, "view"))
            .action(Tm1Operations.NATIVE_VIEW_EXECUTE.name)
        )
        return await self._session.execute(Tm1Request.post(path, {}, Cellset).with_query(ODataQuery.of(query, **options)))

    @endpoint("EntitySet Cellsets")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Cellset]:
        """``GET Cellsets``. TM1 (11.8) lists none, though each cellset reads by its ID with
        :meth:`get`: keep the IDs of the cellsets you create."""
        return await self._session.collect(Tm1Request.list(_CELLSETS, ODataQuery.of(query, **options), Cellset))

    @endpoint("EntitySet Cellsets")
    async def get(self, id: str, query: ODataQuery | None = None, **options: Any) -> Cellset:
        """``GET Cellsets('id')``: the cellset, or a ``Tm1NotFoundError`` if it's gone."""
        return await self._session.execute(Tm1Request.get(_cellset(id), ODataQuery.of(query, **options), Cellset))

    @endpoint("EntitySet Cellsets")
    async def delete(self, id: str) -> None:
        """``DELETE Cellsets('id')``: frees the cellset's memory on the server."""
        await self._session.execute(Tm1Request.delete(_cellset(id)))

    @endpoint("Navigation Cellset/Axes")
    async def axes(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[CellsetAxis]:
        """``GET Cellsets('id')/Axes``: columns (0), rows (1), and so on, then the slicer.
        TM1 (11.8) adds the slicer even without a ``WHERE``: it holds the cube's other
        dimensions, such as its virtual ``Sandboxes``, with cardinality 1. Expand, for example,
        ``Tuples($expand=Members)``."""
        request = Tm1Request.list(_cellset(id).nav("Axes"), ODataQuery.of(query, **options), CellsetAxis)
        return await self._session.collect(request)

    @endpoint("Navigation CellsetAxis/Hierarchies")
    async def hierarchies(self, id: str, axis: int, query: ODataQuery | None = None, **options: Any) -> list[Hierarchy]:
        """``GET Cellsets('id')/Axes(n)/Hierarchies``: the hierarchies on the axis."""
        request = Tm1Request.list(_axis(id, axis).nav("Hierarchies"), ODataQuery.of(query, **options), Hierarchy)
        return await self._session.collect(request)

    @endpoint("Navigation CellsetAxis/Tuples")
    async def tuples(self, id: str, axis: int, query: ODataQuery | None = None, **options: Any) -> list[CellsetAxisTuple]:
        """``GET Cellsets('id')/Axes(n)/Tuples``, in order: an axis can be large, so it's read
        page by page. Expand, for example, ``Members($select=Name)``."""
        request = Tm1Request.list(_axis(id, axis).nav("Tuples"), ODataQuery.of(query, **options), CellsetAxisTuple)
        return await self._session.collect(request)

    @endpoint("Navigation CellsetAxisTuple/Members")
    async def tuple_members(
        self, id: str, axis: int, tuple: int, query: ODataQuery | None = None, **options: Any
    ) -> list[Member]:
        """``GET Cellsets('id')/Axes(n)/Tuples(t)/Members``: one member per hierarchy on the axis."""
        path = _axis(id, axis).nav("Tuples").key(tuple).nav("Members")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Cellset/Cells")
    async def cells(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[CellsetCell]:
        """``GET Cellsets('id')/Cells``, in ordinal order. Use ``$top`` and ``$skip``, or
        :meth:`partition`, for pages; select ``Ordinal``, ``Value``, …"""
        request = Tm1Request.list(_cellset(id).nav("Cells"), ODataQuery.of(query, **options), CellsetCell)
        return await self._session.collect(request)

    @endpoint("Navigation CellsetCell/Members")
    async def cell_members(self, id: str, ordinal: int, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``GET Cellsets('id')/Cells(n)/Members``: the members that place a cell, one per
        hierarchy of the cube. TM1 (11.8) includes the virtual ``Sandboxes`` dimension's,
        ``Base``, first."""
        path = _cellset(id).nav("Cells").key(ordinal).nav("Members")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Cellset/Cube")
    async def cube(self, id: str, query: ODataQuery | None = None, **options: Any) -> Cube:
        """``GET Cellsets('id')/Cube``: the cube the cellset is from."""
        return await self._session.execute(Tm1Request.get(_cellset(id).nav("Cube"), ODataQuery.of(query, **options), Cube))

    @endpoint("Function Cellset/GetPartition")
    async def partition(
        self, id: str, begin: int, end: int, query: ODataQuery | None = None, **options: Any
    ) -> list[CellsetCell]:
        """``tm1.GetPartition(Begin=b,End=e)``: a rectangle of cells. Needs schema 10.2.2.3.

        TM1 (11.8) reads ``Begin`` and ``End`` as the top-left and bottom-right cells of a
        rectangle on the first two axes, not as a range of ordinals: with two columns,
        ``partition(id, 2, 4)`` is column 0 of rows 1 and 2, cells 2 and 4, not 3. A run of whole
        rows, from a row's first cell to a row's last, is a range. Across pages, keep the query
        the same."""
        operation = Tm1Operations.CELLSET_GET_PARTITION
        path = _cellset(id).function(operation.name, {"Begin": begin, "End": end})
        request = Tm1Request.list(path, ODataQuery.of(query, **options), CellsetCell).requires(operation.capability())
        return await self._session.collect(request)

    @endpoint("Action Cellset/Update")
    async def update(self, id: str, ordinal: int, value: Any) -> None:
        """``tm1.Update``: writes one value to a cell of the cellset, by ordinal; ``None`` clears
        it. The action takes a range (``BeginOrdinal``, ``EndOrdinal``), but TM1 (11.8) writes
        only the first cell of it, so this takes one ordinal; :meth:`update_cells` writes many."""
        body = {"BeginOrdinal": ordinal, "EndOrdinal": ordinal, "Value": value}
        await self._session.execute(_cellset_action(Tm1Operations.CELLSET_UPDATE, id, body, None))

    @endpoint("Action Cellset/UpdateCells", "Navigation CellsetUpdate/ReferenceCube", "Navigation CellsetUpdate/ReferenceCell")
    async def update_cells(self, id: str, updates: Sequence[Any]) -> None:
        """``tm1.UpdateCells``: writes many of the cellset's cells, each its own value, by
        ordinal: ``CellsetUpdatePatch.builder().ordinal(n).value(v)``, or with a reference cube
        and cell to copy from. Needs schema 11.0.0."""
        body = {"Updates": list(_require(updates, "updates"))}
        await self._session.execute(_cellset_action(Tm1Operations.CELLSET_UPDATE_CELLS, id, body, None))

    @endpoint("Action Cellset/SaveViewAs")
    async def save_view_as(
        self, id: str, name: str, overwrite: bool | None = None, make_private: bool | None = None
    ) -> View:
        """``tm1.SaveViewAs``: saves the cellset's query as a view of its cube. Needs schema
        11.0.0. With only a name, a public view, failing if one has the name; with
        ``overwrite`` and ``make_private``, whether to replace one that has it, and whether the
        view is private."""
        body: dict[str, Any] = {"Name": _require(name, "name")}
        if overwrite is not None or make_private is not None:
            body["Overwrite"] = bool(overwrite)
            body["MakePrivate"] = bool(make_private)
        return await self._session.execute(_cellset_action(Tm1Operations.CELLSET_SAVE_VIEW_AS, id, body, View))

    @endpoint("ActionImport ExecuteMDX", "Function Cellset/GetPartition", "EntitySet Cellsets")
    async def stream(
        self,
        mdx: str,
        query: ODataQuery | None,
        page_size: int,
        handler: Callable[[Cellset, AsyncIterator[CellsetCell]], Awaitable[Any]],
    ) -> Any:
        """Runs an MDX query, gives ``handler`` the cellset (its ``ID`` and expanded ``Axes``)
        and its cells in ordinal order, then deletes the cellset however the handler finishes.
        ``query`` (Java's ``cellQuery``) is what to select of each cell; include ``Ordinal`` to
        place them. The handler's result is this method's.

        One round trip brings the axes (hierarchies by name, tuples with their members' names
        and unique names) and the first ``page_size`` cells; the rest come in pages of
        ``GetPartition`` as the handler reads them, in ordinal order, so memory stays bounded on
        both sides however large the cellset. (The Java client fetches two pages ahead; this
        one fetches each page when it's needed.)"""
        if page_size < 1:
            raise ValueError(f"page_size must be positive: {page_size}")
        cell_query = query or ODataQuery.none()
        first = (
            ODataQuery.none()
            .expand(
                "Axes",
                lambda a: a.expand("Hierarchies", lambda h: h.select("Name")).expand(
                    "Tuples", lambda t: t.expand("Members", lambda m: m.select("Name", "UniqueName"))
                ),
            )
            .expand("Cells", lambda ignored: cell_query.top(page_size))
        )
        cellset = await self.execute(mdx, first)
        try:
            return await handler(cellset, self._stream_cells(cellset, cell_query, page_size))
        finally:
            # One TM1 sent without an ID has nothing to delete
            if cellset.id is not None:
                await self.delete(cellset.id)

    async def _stream_cells(self, cellset: Cellset, cell_query: ODataQuery, page_size: int) -> AsyncIterator[CellsetCell]:
        """The cells of a streamed cellset: the first page, which came inline, then the rest.
        ``GetPartition`` takes a rectangle, so with two axes the pages are whole rows: the
        first page's last row, if it came incomplete, is dropped and fetched again with the
        next. With more axes, rows aren't rectangles, so pages come from ``Cells`` with
        ``$skip`` and ``$top``."""
        inline = list(cellset.cells or [])
        axes = list(cellset.axes or [])
        total = 1
        for axis in axes:
            total *= _cardinality(axis)
        if len(inline) >= total:
            for cell in inline:
                yield cell
            return
        id = _require(cellset.id, "cellset ID")
        if any(_cardinality(axis) > 1 for axis in axes[2:]):
            start = len(inline)
            for cell in inline:
                yield cell
            for page in range(math.ceil((total - start) / page_size)):
                for cell in await self.cells(id, cell_query.skip(start + page * page_size).top(page_size)):
                    yield cell
            return
        columns = _cardinality(axes[0]) if axes else 1
        rows_per_page = max(1, page_size // columns)
        first_row = len(inline) // columns
        rows = total // columns
        for cell in inline[: first_row * columns]:
            yield cell
        for page in range(math.ceil((rows - first_row) / rows_per_page)):
            top = first_row + page * rows_per_page
            bottom = min(top + rows_per_page, rows) - 1
            for cell in await self.partition(id, top * columns, bottom * columns + columns - 1, cell_query):
                yield cell


def _cardinality(axis: CellsetAxis) -> int:
    return 1 if axis.cardinality is None else axis.cardinality


def _cellset(id: str) -> ODataPath:
    return _CELLSETS.key(_require(id, "id"))


def _axis(id: str, axis: int) -> ODataPath:
    return _cellset(id).nav("Axes").key(axis)


def _cellset_action(operation: Tm1Operation, id: str, body: Mapping[str, Any], type: Any) -> Tm1Request[Any]:
    return Tm1Request.post(_cellset(id).action(operation.name), dict(body), type).requires(operation.capability())


class MemberSetService(Service):
    """Member sets: the results of MDX set expressions, as a single axis of tuples, kept on the
    server in ``/MemberSets`` and keyed by an ordinal TM1 assigns (1, 2, … in the session).

    Like a cellset, a member set stays until it's deleted or the session ends. TM1 (11.8) lists
    none, though each reads by its ordinal: keep the ordinals :meth:`execute` returns, and
    delete what you're done with.

    Evaluate a set expression and read its members' names, deleting the set after::

        members = await tm1.member_sets.execute(
            "{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}",
            ODataQuery.none().expand(CellsetAxis.TUPLES, lambda t: t.expand("Members", lambda m: m.select(Member.NAME))),
        )
        try:
            names = [t.members[0].name for t in members.tuples]
        finally:
            await tm1.member_sets.delete(members.ordinal)

    Keep a large set on the server and read it later by its ordinal; TM1 doesn't list member
    sets, so hold on to the ordinal, and delete the set when done::

        ordinal = (await tm1.member_sets.execute("{TM1SUBSETALL([Product].[Product])}")).ordinal
    """

    @endpoint("ActionImport ExecuteMDXSetExpression")
    async def execute(self, mdx: str, query: ODataQuery | None = None, **options: Any) -> CellsetAxis:
        """``ExecuteMDXSetExpression``: evaluates an MDX set expression, such as
        ``{TM1SUBSETALL([Region].[Region])}``, keeping the result as a member set, with the
        ``Ordinal`` that keys it in ``MemberSets``. Expand
        ``Tuples($expand=Members($select=Name))`` to get its members in the same round trip.
        Needs schema 11.1.0."""
        operation = Tm1Operations.EXECUTE_MDX_SET_EXPRESSION
        request = Tm1Request.post(_import(operation), {"MDX": _require(mdx, "mdx")}, CellsetAxis)
        return await self._session.execute(
            request.with_query(ODataQuery.of(query, **options)).requires(operation.capability())
        )

    @endpoint("EntitySet MemberSets")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[CellsetAxis]:
        """``GET MemberSets``. TM1 (11.8) lists none, though each reads by its ordinal with
        :meth:`get`."""
        return await self._session.collect(Tm1Request.list(_MEMBER_SETS, ODataQuery.of(query, **options), CellsetAxis))

    @endpoint("EntitySet MemberSets")
    async def get(self, ordinal: int, query: ODataQuery | None = None, **options: Any) -> CellsetAxis:
        """``GET MemberSets(n)``: the set, or a ``Tm1NotFoundError`` if it's gone. Expand, for
        example, ``Tuples($expand=Members)``."""
        request = Tm1Request.get(_MEMBER_SETS.key(_require(ordinal, "ordinal")), ODataQuery.of(query, **options), CellsetAxis)
        return await self._session.execute(request)

    @endpoint("EntitySet MemberSets")
    async def delete(self, ordinal: int) -> None:
        """``DELETE MemberSets(n)``: frees the set on the server."""
        await self._session.execute(Tm1Request.delete(_MEMBER_SETS.key(_require(ordinal, "ordinal"))))


class SandboxService(Service):
    """Sandboxes: private copies of the data a user writes to before publishing. They belong to
    the user the session is for.

    Reading and writing in a sandbox go through a session view: ``session.with_sandbox`` or
    ``tm1.with_sandbox``, which add TM1's ``!sandbox`` option to every request. This service
    manages the sandboxes themselves.

    Create a sandbox, write to it, and publish the changes to base data::

        cell = {"Version": "Budget", "Region": "North", "Month": "Jan", "Measure": "Revenue"}
        await tm1.sandboxes.create(SandboxPatch.builder().name("What If").build())
        await tm1.with_sandbox("What If").cells.update("Sales", cell, 1500)
        await tm1.sandboxes.publish("What If")

    Read the same cell in base data and in a sandbox: the same query, through a client for
    each::

        values = ODataQuery.none().select(CellsetCell.VALUE)

        async def first(cellset, cells):
            async for cell in cells:
                return cell.value

        base = await tm1.cellsets.stream(mdx, values, 1, first)
        what_if = await tm1.with_sandbox("What If").cellsets.stream(mdx, values, 1, first)
    """

    @endpoint("EntitySet Sandboxes")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Sandbox]:
        """``GET Sandboxes``: the session user's sandboxes."""
        return await self._session.collect(Tm1Request.list(_SANDBOXES, ODataQuery.of(query, **options), Sandbox))

    @endpoint("EntitySet Sandboxes")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Sandbox:
        """``GET Sandboxes('name')``: the sandbox, or a ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_sandbox(name), ODataQuery.of(query, **options), Sandbox))

    @endpoint("EntitySet Sandboxes")
    async def create(self, sandbox: Any) -> Sandbox:
        """``POST Sandboxes``: creates an empty sandbox; it needs a name."""
        return await self._session.execute(Tm1Request.post(_SANDBOXES, _require(sandbox, "sandbox"), Sandbox))

    @endpoint("EntitySet Sandboxes")
    async def update(self, name: str, patch: Any) -> None:
        """``PATCH Sandboxes('name')``: changes the properties the patch sets, such as
        ``IncludeInSandboxDimension``."""
        await self._session.execute(Tm1Request.patch(_sandbox(name), _require(patch, "patch")))

    @endpoint("EntitySet Sandboxes")
    async def delete(self, name: str) -> None:
        """``DELETE Sandboxes('name')``, with its unpublished changes."""
        await self._session.execute(Tm1Request.delete(_sandbox(name)))

    @endpoint("Action Sandbox/Load")
    async def load(self, name: str) -> None:
        """``tm1.Load``: loads the sandbox into memory."""
        await self._session.execute(_sandbox_action(Tm1Operations.SANDBOX_LOAD, name, {}))

    @endpoint("Action Sandbox/Unload")
    async def unload(self, name: str) -> None:
        """``tm1.Unload``: frees the sandbox's memory, keeping its changes."""
        await self._session.execute(_sandbox_action(Tm1Operations.SANDBOX_UNLOAD, name, {}))

    @endpoint("Action Sandbox/DiscardChanges")
    async def discard_changes(self, name: str) -> None:
        """``tm1.DiscardChanges``: throws away the sandbox's changes, keeping the sandbox."""
        await self._session.execute(_sandbox_action(Tm1Operations.SANDBOX_DISCARD_CHANGES, name, {}))

    @endpoint("Action Sandbox/Publish")
    async def publish(
        self,
        name: str,
        conflicts: ConflictResolution | None = None,
        allow_queueing: bool = False,
        clean_after: bool = False,
    ) -> None:
        """``tm1.Publish``: writes the sandbox's changes to base data. With ``conflicts``, the
        options are sent too: what to do with cells changed in base data since the sandbox's
        changes, whether to wait for a lock rather than fail (``allow_queueing``), and whether
        to empty the sandbox once published (``clean_after``)."""
        await self._session.execute(
            _sandbox_action(Tm1Operations.SANDBOX_PUBLISH, name, _options(conflicts, allow_queueing, clean_after))
        )

    @endpoint("Action Sandbox/Merge")
    async def merge(
        self,
        name: str,
        target: str,
        conflicts: ConflictResolution | None = None,
        allow_queueing: bool = False,
        clean_after: bool = False,
    ) -> None:
        """``tm1.Merge``: copies the sandbox's changes into sandbox ``target``. With
        ``conflicts``, the options are sent too: what to do with cells the target changed too,
        whether to wait for a lock rather than fail, and whether to empty this sandbox once
        merged."""
        body: dict[str, Any] = {"Target@odata.bind": str(_sandbox(target))}
        body.update(_options(conflicts, allow_queueing, clean_after))
        await self._session.execute(_sandbox_action(Tm1Operations.SANDBOX_MERGE, name, body))


def _options(conflicts: ConflictResolution | None, allow_queueing: bool, clean_after: bool) -> dict[str, Any]:
    if conflicts is None:
        if allow_queueing or clean_after:
            raise ValueError("allow_queueing and clean_after go with conflicts: give all three, as TM1 needs")
        return {}
    return {"ConflictResolution": conflicts, "AllowQueueing": bool(allow_queueing), "CleanAfter": bool(clean_after)}


def _sandbox(name: str) -> ODataPath:
    return _SANDBOXES.key(_require(name, "name"))


def _sandbox_action(operation: Tm1Operation, name: str, body: Mapping[str, Any]) -> Tm1Request[Any]:
    return Tm1Request.post(_sandbox(name).action(operation.name), dict(body), None).requires(operation.capability())


class ChangeSetService(Service):
    """Change sets: groups of cell writes that can be undone together.

    A write joins a change set through TM1's ``!ChangeSet`` query option, which a session view
    from ``session.with_change_set`` adds to every request (``$metadata`` doesn't say how; TM1
    11.8 does it this way). :meth:`within` does it all: begins a change set, gives the work a
    session that records into it, and ends it however the work finishes. :meth:`recording` is
    the same as a context manager.

    Record some writes in a change set, and keep its ID to undo them later::

        cell = {"Version": "Forecast", "Region": "North", "Month": "Jan", "Measure": "Revenue"}

        async def work(session):
            await AsyncTm1Client.of(session).cells.update("Sales", cell, 1500)
            return session.change_set

        change_set = await tm1.change_sets.within(work)

        # or
        async with tm1.change_sets.recording() as session:
            await AsyncTm1Client.of(session).cells.update("Sales", cell, 1500)
            change_set = session.change_set

    Revert every write recorded in the change set; writes outside it stay::

        await tm1.change_sets.undo(change_set)
    """

    @endpoint("ActionImport BeginChangeSet")
    async def begin(self) -> str:
        """``BeginChangeSet``: starts a change set, and returns its ID, for
        ``session.with_change_set``. Needs schema 10.2.2.6."""
        result = await self._session.execute(_change_set_request(Tm1Operations.BEGIN_CHANGE_SET, {}))
        # A primitive result comes wrapped, as {"value": "3"}
        return str((result or {}).get("value", ""))

    @endpoint("ActionImport EndChangeSet")
    async def end(self, id: str) -> int:
        """``EndChangeSet``: stops recording into a change set; it can still be undone. Returns
        TM1's return code. Needs schema 10.2.2.6."""
        result = await self._session.execute(
            _change_set_request(Tm1Operations.END_CHANGE_SET, {"ChangeSetID": _require(id, "id")})
        )
        return int((result or {}).get("value", 0))

    @endpoint("ActionImport UndoChangeSet")
    async def undo(self, id: str) -> None:
        """``UndoChangeSet``: reverts every write recorded in a change set. Needs schema 10.2.2.6."""
        operation = Tm1Operations.UNDO_CHANGE_SET
        request = Tm1Request.post(_import(operation), {"ChangeSetID": _require(id, "id")}, None)
        await self._session.execute(request.requires(operation.capability()))

    @endpoint("ActionImport BeginChangeSet", "ActionImport EndChangeSet")
    async def within(self, work: Callable[[AsyncTm1Session], Awaitable[Any]]) -> Any:
        """Runs ``work`` in a new change set: begins it, gives the work a view of the session that
        records into it (``session.change_set`` has the ID, for a later :meth:`undo`), and ends
        it when the work completes or fails. Returns what the work gives."""
        async with self.recording() as session:
            return await work(session)

    @asynccontextmanager
    @endpoint("ActionImport BeginChangeSet", "ActionImport EndChangeSet")
    async def recording(self) -> AsyncIterator[AsyncTm1Session]:
        """:meth:`within` as a context manager: begins a change set, gives the block a view of the
        session that records into it, and ends it however the block finishes."""
        change_set = await self.begin()
        try:
            yield self._session.with_change_set(change_set)
        finally:
            await self.end(change_set)


def _change_set_request(operation: Tm1Operation, body: Mapping[str, Any]) -> Tm1Request[Any]:
    return Tm1Request.post(_import(operation), dict(body), dict).requires(operation.capability())


class DrillthroughService(Service):
    """Drillthrough: from a cell to the detail behind it, through a drillthrough process
    (``}Drill_…``). A cube drillthrough returns a cellset, of a view the process returns; a
    relational one returns rows from an ODBC source, directly or kept as a ``Rowset``.

    A cell's drillthroughs come from the cube's ``DrillthroughRules``, which name the process;
    :meth:`scripts` lists them for a cell of a cellset. TM1 (11.8) passes such a process the
    cube's name and the cell's element on each of its dimensions, so the process must declare
    that many parameters (the cube's name, then one per dimension, in order); otherwise running
    the cell's drillthrough fails with "process parameter count error" (code 248). Run directly
    (:meth:`execute_process`), it takes the parameters given. The returned cellsets and rowsets
    stay on the server until deleted, like any cellset.

    ``$metadata`` declares a relational row (``DrillthroughRow``) with no properties and not
    open, so its columns would all be lost in a model: relational rows come as plain JSON
    (dicts). A rowset's rows (``RowsetRow``) are open, and keep theirs in ``model_extra``.

    Drill from a cell of a query to the detail behind it; both cellsets are deleted at the end::

        source = await tm1.cellsets.execute(mdx)
        try:
            scripts = await tm1.drillthrough.scripts(source.id, 0)
            script = next(s for s in scripts if isinstance(s, CubeDrillthrough))
            detail = await tm1.drillthrough.execute(
                source.id, 0, script.name,
                ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.ORDINAL, CellsetCell.VALUE)),
            )
            try:
                cells = detail.cells
            finally:
                await tm1.cellsets.delete(detail.id)
        finally:
            await tm1.cellsets.delete(source.id)

    Run a drillthrough process directly, with the parameters it declares::

        parameters = [
            ProcessParameterPatch.builder().name("pRegion").value("North").build(),
            ProcessParameterPatch.builder().name("pMonth").value("Jan").build(),
        ]
        detail = await tm1.drillthrough.execute_process(
            "}Drill_Sales Detail", parameters, ODataQuery.none().expand(Cellset.CELLS, lambda c: c.select(CellsetCell.VALUE))
        )

    Read the rows of a relational drillthrough as a rowset::

        rowset = await tm1.drillthrough.execute_relational_process_with_rowset("}Drill_Sales Orders", [])
        try:
            orders = [row.model_extra["OrderNumber"] for row in await tm1.drillthrough.rows(rowset.id)]
        finally:
            await tm1.drillthrough.delete_rowset(rowset.id)
    """

    @endpoint("Navigation CellsetCell/DrillthroughScripts")
    async def scripts(self, cellset: str, ordinal: int, query: ODataQuery | None = None, **options: Any) -> list[Drillthrough]:
        """``GET Cellsets('id')/Cells(n)/DrillthroughScripts``: the drillthroughs a cell has, by
        name, each a ``CubeDrillthrough`` or ``RelationalDrillthrough``."""
        request = Tm1Request.list(_scripts(cellset, ordinal), ODataQuery.of(query, **options), Drillthrough)
        return await self._session.collect(request)

    @endpoint("Action CubeDrillthrough/Execute")
    async def execute(
        self, cellset: str, ordinal: int, drillthrough: str, query: ODataQuery | None = None, **options: Any
    ) -> Cellset:
        """``tm1.Execute`` on a cell's cube drillthrough (named as by :meth:`scripts`): the cellset
        of the view its process returns, left on the server until deleted. Needs schema
        10.2.2.6. The process must take the cube's name and one parameter per dimension (see
        the class description)."""
        operation = Tm1Operations.CUBE_DRILLTHROUGH_EXECUTE
        path = _script(cellset, ordinal, drillthrough).action(operation.name)
        request = Tm1Request.post(path, {}, Cellset).with_query(ODataQuery.of(query, **options))
        return await self._session.execute(request.requires(operation.capability()))

    @endpoint("Action RelationalDrillthrough/Execute")
    async def execute_relational(self, cellset: str, ordinal: int, drillthrough: str) -> list[Any]:
        """``tm1.Execute`` on a cell's relational drillthrough: its rows, as JSON. Needs schema
        10.2.2.6."""
        operation = Tm1Operations.RELATIONAL_DRILLTHROUGH_EXECUTE
        path = _script(cellset, ordinal, drillthrough).action(operation.name)
        return await self._session.collect(Tm1Request.post_list(path, {}, dict).requires(operation.capability()))

    @endpoint("Action RelationalDrillthrough/ExecuteWithRowset")
    async def execute_relational_with_rowset(self, cellset: str, ordinal: int, drillthrough: str) -> Rowset:
        """``tm1.ExecuteWithRowset`` on a cell's relational drillthrough: its rows, kept as a
        rowset (with its ``ID``) to read with :meth:`rows`. Needs schema 11.6.0."""
        operation = Tm1Operations.RELATIONAL_DRILLTHROUGH_EXECUTE_WITH_ROWSET
        path = _script(cellset, ordinal, drillthrough).action(operation.name)
        return await self._session.execute(Tm1Request.post(path, {}, Rowset).requires(operation.capability()))

    @endpoint("ActionImport ExecuteCubeDrillthrough")
    async def execute_process(
        self, process: str, parameters: Sequence[Any], query: ODataQuery | None = None, **options: Any
    ) -> Cellset:
        """``ExecuteCubeDrillthrough``: runs a cube drillthrough process (such as
        ``}Drill_Sales``) directly, with its parameters (``ProcessParameterPatch``, by name and
        value), and returns the cellset of the view it returns, left on the server until
        deleted. Needs schema 11.2.0."""
        operation = Tm1Operations.EXECUTE_CUBE_DRILLTHROUGH
        request = Tm1Request.post(_import(operation), _process_body(process, parameters), Cellset)
        return await self._session.execute(
            request.with_query(ODataQuery.of(query, **options)).requires(operation.capability())
        )

    @endpoint("ActionImport ExecuteRelationalDrillthrough")
    async def execute_relational_process(self, process: str, parameters: Sequence[Any]) -> list[Any]:
        """``ExecuteRelationalDrillthrough``: runs a relational drillthrough process directly;
        its rows, as JSON. Needs schema 11.2.0."""
        operation = Tm1Operations.EXECUTE_RELATIONAL_DRILLTHROUGH
        request = Tm1Request.post_list(_import(operation), _process_body(process, parameters), dict)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint("ActionImport ExecuteRelationalDrillthroughWithRowset")
    async def execute_relational_process_with_rowset(self, process: str, parameters: Sequence[Any]) -> Rowset:
        """``ExecuteRelationalDrillthroughWithRowset``: runs a relational drillthrough process
        directly, keeping its rows as a rowset, with its ``ID``. Needs schema 11.6.0."""
        operation = Tm1Operations.EXECUTE_RELATIONAL_DRILLTHROUGH_WITH_ROWSET
        request = Tm1Request.post(_import(operation), _process_body(process, parameters), Rowset)
        return await self._session.execute(request.requires(operation.capability()))

    @endpoint("EntitySet Rowsets")
    async def rowset(self, id: str, query: ODataQuery | None = None, **options: Any) -> Rowset:
        """``GET Rowsets('id')``."""
        return await self._session.execute(Tm1Request.get(_rowset(id), ODataQuery.of(query, **options), Rowset))

    @endpoint("Navigation Rowset/Rows")
    async def rows(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[RowsetRow]:
        """``GET Rowsets('id')/Rows``: each row's columns are in its ``model_extra``; select,
        filter, and page (``$top``, ``$skip``) with the query."""
        return await self._session.collect(Tm1Request.list(_rowset(id).nav("Rows"), ODataQuery.of(query, **options), RowsetRow))

    @endpoint("EntitySet Rowsets")
    async def delete_rowset(self, id: str) -> None:
        """``DELETE Rowsets('id')``: frees the rowset on the server."""
        await self._session.execute(Tm1Request.delete(_rowset(id)))


def _process_body(process: str, parameters: Sequence[Any]) -> dict[str, Any]:
    body: dict[str, Any] = {"DrillthroughProcess@odata.bind": str(ODataPath.of("Processes").key(_require(process, "process")))}
    if parameters:
        body["Parameters"] = list(parameters)
    return body


def _rowset(id: str) -> ODataPath:
    return _ROWSETS.key(_require(id, "id"))


def _scripts(cellset: str, ordinal: int) -> ODataPath:
    return _CELLSETS.key(_require(cellset, "cellset")).nav("Cells").key(ordinal).nav("DrillthroughScripts")


def _script(cellset: str, ordinal: int, drillthrough: str) -> ODataPath:
    return _scripts(cellset, ordinal).key(_require(drillthrough, "drillthrough"))
