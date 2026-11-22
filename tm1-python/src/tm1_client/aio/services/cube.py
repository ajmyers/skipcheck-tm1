"""Services for cubes and views (the Java client's ``net.ajmyers.tm1.client.service.cube``)."""

from __future__ import annotations

from typing import Any, Mapping, Sequence

from ..._calls import endpoint
from ..._generated.model import (
    CalculationComponent,
    CheckFeedersSelection,
    Cube,
    Dimension,
    FedCellDescriptor,
    FeederTrace,
    RuleSyntaxError,
)
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ._base import Service, _require

__all__ = ["CubeService", "ViewService"]

_CUBES = ODataPath.of("Cubes")


def _bindings(tuple_: Mapping[str, str]) -> list[str]:
    """A cell's coordinates, dimension → element, as element paths in each dimension's own
    hierarchy, in the order given."""
    return [
        str(ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(e))
        for d, e in tuple_.items()
    ]


class CubeService(Service):
    """Cubes: ``/Cubes``, a cube's dimensions, and the operations bound to a cube. Views have a
    service of their own; cell data is read and written through cellsets.

    List cubes with a query: here, the model cubes whose names start with "Sales"::

        await tm1.cubes.model_cubes(
            ODataQuery.none().select(Cube.NAME).filter(Filter.starts_with(Cube.NAME, "Sales")).order_by(Cube.NAME)
        )

    Create a cube over existing dimensions, bound in order::

        dimensions = ODataPath.of("Dimensions")
        await tm1.cubes.create(CubePatch.builder().name("Sales").bind_dimensions(dimensions.key("Region"), dimensions.key("Month")).build())

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
        ``CubePatch.Builder.bind_dimensions``, and optionally its rules. It needs a name and at
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
        result = await self._session.execute(
            _action(Tm1Operations.CUBE_REORDER_DIMENSIONS, name, {"Dimensions@odata.bind": bindings}, dict)
        )
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

    @endpoint(
        "Action Cube/TraceCellCalculation", "Navigation CalculationComponent/Cube", "Navigation CalculationComponent/Tuple"
    )
    async def trace_cell_calculation(
        self, name: str, tuple: Mapping[str, str], query: ODataQuery | None = None, **options: Any
    ) -> CalculationComponent:
        """``tm1.TraceCellCalculation``: how a cell's value is calculated, component by
        component; ``tuple`` maps each dimension to an element. Expand ``Components`` (and
        ``Components/Tuple``) to see them."""
        request = _action(
            Tm1Operations.CUBE_TRACE_CELL_CALCULATION, name, {"Tuple@odata.bind": _bindings(tuple)}, CalculationComponent
        )
        return await self._session.execute(request.with_query(ODataQuery.of(query, **options)))

    @endpoint("Action Cube/TraceFeeders", "Navigation FedCellDescriptor/Cube", "Navigation FedCellDescriptor/Tuple")
    async def trace_feeders(
        self, name: str, tuple: Mapping[str, str], query: ODataQuery | None = None, **options: Any
    ) -> FeederTrace:
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
    """Views: a cube's public and private views, native (subsets on columns, rows and titles)
    or MDX."""

    # Not ported yet: see tests/test_java_parity.py
