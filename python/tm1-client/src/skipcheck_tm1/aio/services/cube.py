# SPDX-License-Identifier: Apache-2.0

"""Services for cubes and views (the Java client's ``io.skipcheck.tm1.client.service.cube``)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ..._calls import endpoint
from ..._generated.model import (
    CalculationComponent,
    CheckFeedersSelection,
    Cube,
    Dimension,
    FedCellDescriptor,
    FeederTrace,
    NativeView,
    RuleSyntaxError,
    View,
)
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ...values import ViewScope
from ._base import Service, _require

__all__ = ["CubeService", "ViewScope", "ViewService"]

_CUBES = ODataPath.of("Cubes")


def _bindings(tuple_: Mapping[str, str]) -> list[str]:
    """A cell's coordinates, dimension → element, as element paths in each dimension's own
    hierarchy, in the order given."""
    return [str(ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(e)) for d, e in tuple_.items()]


class CubeService(Service):
    """Cubes: ``/Cubes``, a cube's dimensions, and the operations bound to a cube. Views have a
    service of their own; cell data is read and written through cellsets.

    List cubes with a query: here, the model cubes whose names start with "Sales"::

        await tm1.cubes.model_cubes(
            ODataQuery.none().select(Cube.NAME).filter(Filter.starts_with(Cube.NAME, "Sales")).order_by(Cube.NAME)
        )

    Create a cube over existing dimensions, bound in order::

        dimensions = ODataPath.of("Dimensions")
        await tm1.cubes.create(CubeCreate.builder().name("Sales").bind_dimensions(dimensions.key("Region"), dimensions.key("Month")).build())

    Change a cube's rules, checking them first::

        rules = "SKIPCHECK;\\n['Margin'] = N: ['Revenue'] - ['Cost'];"
        errors = await tm1.cubes.check_rules("Sales", rules)
        if errors:
            raise ValueError(f"The rules don't compile: {errors}")
        await tm1.cubes.update("Sales", CubePatch.builder().rules(rules).build())
    """

    @endpoint("EntitySet Cubes")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Cube]:
        """``GET Cubes``: every cube, control cubes included."""
        return await self._session.collect(Tm1Request.list(_CUBES, ODataQuery.of(query, **options), Cube))

    @endpoint("FunctionImport ModelCubes")
    async def model_cubes(self, query: ODataQuery | None = None, **options: Any) -> list[Cube]:
        """``ModelCubes()``: the cubes that aren't control cubes (whose names start with ``}``)."""
        path = ODataPath.function_import("ModelCubes", {})
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Cube))

    @endpoint("FunctionImport ControlCubes")
    async def control_cubes(self, query: ODataQuery | None = None, **options: Any) -> list[Cube]:
        """``ControlCubes()``: the control cubes, such as ``}ClientGroups``."""
        path = ODataPath.function_import("ControlCubes", {})
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Cube))

    @endpoint("EntitySet Cubes")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Cube:
        """``GET Cubes('name')``: the cube, or a ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_cube(name), ODataQuery.of(query, **options), Cube))

    @endpoint("EntitySet Cubes")
    async def create(self, cube: Any) -> Cube:
        """``POST Cubes``: creates a cube over existing dimensions, bound in order with
        ``CubeCreate.Builder.bind_dimensions``, and optionally its rules. It needs a name and at
        least two dimensions; returns the cube as created."""
        return await self._session.execute(Tm1Request.post(_CUBES, _require(cube, "cube"), Cube))

    @endpoint("EntitySet Cubes")
    async def update(self, name: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Cubes('name')``: changes the properties the patch sets, typically ``Rules`` or
        ``DrillthroughRules``; ``None`` clears them. With ``etag`` (``Cube.etag``, from a read),
        sent as ``If-Match``: only if the cube hasn't changed since, else a
        ``Tm1PreconditionFailedError``.

        TM1 (11.8) saves rules even when they don't compile: check them first with
        :meth:`check_rules`."""
        request = Tm1Request.patch(_cube(name), _require(patch, "patch"))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("EntitySet Cubes")
    async def delete(self, name: str) -> None:
        """``DELETE Cubes('name')``."""
        await self._session.execute(Tm1Request.delete(_cube(name)))

    @endpoint("Navigation Cube/Dimensions")
    async def dimensions(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[Dimension]:
        """``GET Cubes('name')/Dimensions``: the cube's dimensions, in the cube's order.
        TM1 (11.8) lists the virtual ``Sandboxes`` dimension first."""
        request = Tm1Request.list(_cube(name).nav("Dimensions"), ODataQuery.of(query, **options), Dimension)
        return await self._session.collect(request)

    @endpoint("Function Cube/DimensionsStorageOrder")
    async def dimensions_storage_order(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[Dimension]:
        """``tm1.DimensionsStorageOrder()``: the dimensions in the order TM1 stores the cube's
        cells. Needs schema 11.4.0."""
        operation = Tm1Operations.CUBE_DIMENSIONS_STORAGE_ORDER
        request = Tm1Request.list(_cube(name).function(operation.name, {}), ODataQuery.of(query, **options), Dimension)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint("Action Cube/ReorderDimensions")
    async def reorder_dimensions(self, name: str, dimensions: Sequence[str]) -> float:
        """``tm1.ReorderDimensions``: changes the order TM1 stores the cube's cells in, and returns
        the change in memory use, as a percentage."""
        bindings = [str(ODataPath.of("Dimensions").key(d)) for d in dimensions]
        result = await self._session.execute(_action(Tm1Operations.CUBE_REORDER_DIMENSIONS, name, {"Dimensions@odata.bind": bindings}, dict))
        # A primitive result comes wrapped, as {"@odata.context": ..., "value": 12.5}
        return float((result or {}).get("value", 0.0))

    @endpoint("Action Cube/Load")
    async def load(self, name: str) -> None:
        """``tm1.Load``: loads the cube into memory."""
        await self._session.execute(_action(Tm1Operations.CUBE_LOAD, name, {}, None))

    @endpoint("Action Cube/Unload")
    async def unload(self, name: str) -> None:
        """``tm1.Unload``: unloads the cube from memory."""
        await self._session.execute(_action(Tm1Operations.CUBE_UNLOAD, name, {}, None))

    @endpoint("Action Cube/Lock")
    async def lock(self, name: str) -> None:
        """``tm1.Lock``."""
        await self._session.execute(_action(Tm1Operations.CUBE_LOCK, name, {}, None))

    @endpoint("Action Cube/Unlock")
    async def unlock(self, name: str) -> None:
        """``tm1.Unlock``."""
        await self._session.execute(_action(Tm1Operations.CUBE_UNLOCK, name, {}, None))

    @endpoint("Action Cube/Reserve")
    async def reserve(self, name: str) -> None:
        """``tm1.Reserve``: no one else can change the cube until it's released. Needs schema 11.8.34."""
        await self._session.execute(_action(Tm1Operations.CUBE_RESERVE, name, {}, None))

    @endpoint("Action Cube/Release")
    async def release(self, name: str) -> None:
        """``tm1.Release``. Needs schema 11.8.34."""
        await self._session.execute(_action(Tm1Operations.CUBE_RELEASE, name, {}, None))

    @endpoint("Action Cube/CheckRules")
    async def check_rules(self, name: str, rules: str | None = None) -> list[RuleSyntaxError]:
        """``tm1.CheckRules``: the syntax errors in the cube's rules, or in ``rules`` if given
        (checked as if they were the cube's, without saving them); empty if they compile."""
        operation = Tm1Operations.CUBE_CHECK_RULES
        body = {} if rules is None else {"Rules": rules}
        request = Tm1Request.post_list(_cube(name).action(operation.name), body, RuleSyntaxError)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint("Action Cube/TraceCellCalculation", "Navigation CalculationComponent/Cube", "Navigation CalculationComponent/Tuple")
    async def trace_cell_calculation(self, name: str, tuple: Mapping[str, str], query: ODataQuery | None = None, **options: Any) -> CalculationComponent:
        """``tm1.TraceCellCalculation``: how a cell's value is calculated, component by
        component; ``tuple`` maps each dimension to an element. Expand ``Components`` (and
        ``Components/Tuple``) to see them."""
        request = _action(Tm1Operations.CUBE_TRACE_CELL_CALCULATION, name, {"Tuple@odata.bind": _bindings(tuple)}, CalculationComponent)
        return await self._session.execute(request.with_query(ODataQuery.of(query, **options)))

    @endpoint("Action Cube/TraceFeeders", "Navigation FedCellDescriptor/Cube", "Navigation FedCellDescriptor/Tuple")
    async def trace_feeders(self, name: str, tuple: Mapping[str, str], query: ODataQuery | None = None, **options: Any) -> FeederTrace:
        """``tm1.TraceFeeders``: what a cell feeds. TM1 (11.8) needs a path to expand into a
        complex property (``FedCells/Tuple``); nested options (``FedCells($expand=Tuple)``) get 400."""
        request = _action(Tm1Operations.CUBE_TRACE_FEEDERS, name, {"Tuple@odata.bind": _bindings(tuple)}, FeederTrace)
        return await self._session.execute(request.with_query(ODataQuery.of(query, **options)))

    @endpoint("Action Cube/CheckFeeders", "Navigation FedCellDescriptor/Cube", "Navigation FedCellDescriptor/Tuple")
    async def check_feeders(
        self,
        name: str,
        tuple: Mapping[str, str],
        selection: CheckFeedersSelection,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> list[FedCellDescriptor]:
        """``tm1.CheckFeeders``: the cells under a consolidated cell that aren't fed, though
        they have values, chosen by ``selection``."""
        operation = Tm1Operations.CUBE_CHECK_FEEDERS
        body = {"Tuple@odata.bind": _bindings(tuple), "Selection": _require(selection, "selection").value}
        request = Tm1Request.post_list(_cube(name).action(operation.name), body, FedCellDescriptor)
        request = request.with_query(ODataQuery.of(query, **options)).requires(operation.capability())
        return await self._session.collect(request)


def _cube(name: str) -> ODataPath:
    return _CUBES.key(_require(name, "name"))


def _action(operation: Tm1Operation, name: str, body: Mapping[str, Any], type: Any) -> Tm1Request[Any]:
    return Tm1Request.post(_cube(name).action(operation.name), dict(body), type).requires(operation.capability())


class ViewService(Service):
    """Views: a cube's public and private views (:class:`ViewScope`), native (``NativeView``:
    subsets on columns, rows and titles) or MDX (``MDXView``). They're read as ``View``, so
    each comes back as its own type.

    A native view's subsets are in complex properties, reached by expanding through the
    ``tm1.NativeView`` type: :meth:`list` with ``tm1.NativeView/Columns/Subset``, or
    :meth:`get_native`, which reads one view through the type. On TM1 11.8.35 the type-cast
    expand works on a list holding MDX views too; an older server was reported to fail it,
    and ``get_native`` doesn't depend on it.

    Create a native view: each axis binds a subset, and every dimension of the cube has to be
    on one::

        dimensions = ODataPath.of("Dimensions")
        months = dimensions.key("Month").nav("Hierarchies").key("Month").nav("Subsets").key("Quarters")
        regions = dimensions.key("Region").nav("Hierarchies").key("Region").nav("Subsets").key("Leaves")
        versions = dimensions.key("Version").nav("Hierarchies").key("Version")
        measures = dimensions.key("Measure").nav("Hierarchies").key("Measure")
        await tm1.views.create("Sales", ViewScope.PUBLIC, NativeViewCreate.builder()
            .name("Revenue by Region")
            .columns([ViewAxisSelectionPatch.builder().bind_subset(months).build()])
            .rows([ViewAxisSelectionPatch.builder().bind_subset(regions).build()])
            .titles([
                ViewTitlePatch.builder().bind_subset(versions.nav("Subsets").key("All"))
                    .bind_selected(versions.nav("Elements").key("Actual")).build(),
                ViewTitlePatch.builder().bind_subset(measures.nav("Subsets").key("All"))
                    .bind_selected(measures.nav("Elements").key("Revenue")).build(),
            ])
            .suppress_empty_rows(True)
            .build())

    Create an MDX view from its query::

        mdx = ("SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0,"
               " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]"
               " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])")
        await tm1.views.create("Sales", ViewScope.PRIVATE, MDXViewCreate.builder().name("My Revenue").mdx(mdx).build())

    List a cube's views, telling the kinds apart by type::

        for view in await tm1.views.list("Sales", ViewScope.PUBLIC):
            if isinstance(view, MDXView):
                print(view.name, view.mdx)
            elif isinstance(view, NativeView):
                print(view.name, "native")

    Read the subsets on a native view's axes::

        view = await tm1.views.get_native("Sales", ViewScope.PUBLIC, "Revenue by Region",
                                          ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset"))
        names = [axis.subset.unique_name for axis in [*view.columns, *view.rows]]
    """

    @endpoint("Navigation Cube/Views", "Navigation Cube/PrivateViews")
    async def list(self, cube: str, scope: ViewScope, query: ODataQuery | None = None, **options: Any) -> list[View]:
        """``GET Cubes('c')/Views`` (or the private ones): the views, each as its own type. A
        native view's axes expand through its type, as ``tm1.NativeView/Columns/Subset``."""
        return await self._session.collect(Tm1Request.list(_views(cube, scope), ODataQuery.of(query, **options), View))

    @endpoint("Navigation Cube/Views", "Navigation Cube/PrivateViews")
    async def get(self, cube: str, scope: ViewScope, view: str, query: ODataQuery | None = None, **options: Any) -> View:
        """``GET Cubes('c')/Views('v')``: the view as its own type, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_view(cube, scope, view), ODataQuery.of(query, **options), View))

    @endpoint(
        "Navigation Cube/Views",
        "Navigation Cube/PrivateViews",
        "Navigation ViewAxisSelection/Subset",
        "Navigation ViewTitle/Subset",
        "Navigation ViewTitle/Selected",
    )
    async def get_native(self, cube: str, scope: ViewScope, view: str, query: ODataQuery | None = None, **options: Any) -> NativeView:
        """``GET Cubes('c')/Views('v')/tm1.NativeView``: reads a native view through its type,
        so its axes can be expanded: ``Columns/Subset``, ``Rows/Subset``, ``Titles/Subset``,
        ``Titles/Selected``. They're complex properties, so TM1 wants paths, not nested
        options. An MDX view is an error."""
        path = _view(cube, scope, view).cast("ibm.tm1.api.v1.NativeView")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), NativeView))

    @endpoint("Navigation Cube/Views", "Navigation Cube/PrivateViews")
    async def create(self, cube: str, scope: ViewScope, view: Any) -> View:
        """``POST Cubes('c')/Views``: creates a view; the patch's type says which kind
        (``MDXViewCreate``, ``NativeViewCreate``), and a native view's axes bind subsets. It
        needs a name. TM1 (11.8) refuses a native view that leaves any of the cube's
        dimensions off its columns, rows and titles (400, "All cube dimensions must be placed
        on an axis")."""
        return await self._session.execute(Tm1Request.post(_views(cube, scope), _require(view, "view"), View))

    @endpoint("Navigation Cube/Views", "Navigation Cube/PrivateViews")
    async def update(self, cube: str, scope: ViewScope, view: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Cubes('c')/Views('v')``: changes the properties the patch (of the view's
        kind) sets, such as an MDX view's ``MDX``. With ``etag``, sent as ``If-Match``: a
        ``Tm1PreconditionFailedError`` if the view changed since it was read."""
        request = Tm1Request.patch(_view(cube, scope, view), _require(patch, "patch"))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("Navigation Cube/Views", "Navigation Cube/PrivateViews")
    async def delete(self, cube: str, scope: ViewScope, view: str) -> None:
        """``DELETE Cubes('c')/Views('v')``; its subsets stay."""
        await self._session.execute(Tm1Request.delete(_view(cube, scope, view)))

    @endpoint("Navigation View/Cube")
    async def cube(self, cube: str, scope: ViewScope, view: str, query: ODataQuery | None = None, **options: Any) -> Cube:
        """``GET Cubes('c')/Views('v')/Cube``: the view's cube."""
        path = _view(cube, scope, view).nav("Cube")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Cube))


def _views(cube: str, scope: ViewScope) -> ODataPath:
    return _CUBES.key(_require(cube, "cube")).nav(_require(scope, "scope").navigation())


def _view(cube: str, scope: ViewScope, view: str) -> ODataPath:
    return _views(cube, scope).key(_require(view, "view"))
