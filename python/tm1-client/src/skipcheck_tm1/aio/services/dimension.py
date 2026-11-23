# SPDX-License-Identifier: Apache-2.0

"""Services for dimensions, hierarchies, elements, edges, levels, members and subsets (the Java
client's ``io.skipcheck.tm1.client.service.dimension``)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ..._calls import endpoint
from ..._generated.model import Dimension, Edge, Element, Hierarchy, Level, Member, Subset
from ..._generated.operations import Tm1Operations
from ..._generated.patches import HierarchyPatch
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request, body_of
from ...values import SubsetScope
from ._base import Service, _require

__all__ = [
    "DimensionService",
    "EdgeService",
    "ElementService",
    "HierarchyService",
    "LevelService",
    "MemberService",
    "SubsetScope",
    "SubsetService",
]

_DIMENSIONS = ODataPath.of("Dimensions")


def _dimension(dimension: str) -> ODataPath:
    return _DIMENSIONS.key(_require(dimension, "dimension"))


def _hierarchies(dimension: str) -> ODataPath:
    return _dimension(dimension).nav("Hierarchies")


def _hierarchy(dimension: str, hierarchy: str) -> ODataPath:
    return _hierarchies(dimension).key(_require(hierarchy, "hierarchy"))


def _elements(dimension: str, hierarchy: str) -> ODataPath:
    return _hierarchy(dimension, hierarchy).nav("Elements")


def _element(dimension: str, hierarchy: str, element: str) -> ODataPath:
    return _elements(dimension, hierarchy).key(_require(element, "element"))


def _keys(patch: Any) -> set[str]:
    """The names a patch (a generated ``…Patch``, a model or a mapping) sends."""
    json = body_of(patch)
    return set(json) if isinstance(json, Mapping) else set()


def _post(target: ODataPath, operation: Tm1Operation, body: Mapping[str, Any], type: Any) -> Tm1Request[Any]:
    return Tm1Request.post(target.action(operation.name), dict(body), type).requires(operation.capability())


class DimensionService(Service):
    """Dimensions: ``/Dimensions`` and the operations bound to a dimension.

    A dimension's hierarchies, elements and subsets have services of their own.

    List dimensions with a query: here, the names of the model dimensions, in name order::

        dimensions = await tm1.dimensions.model_dimensions(
            ODataQuery.none().select(Dimension.NAME).order_by(Dimension.NAME)
        )
        names = [d.name for d in dimensions]

    Create a dimension with its hierarchy, elements and edges in one request (Region, with
    one hierarchy of the same name: Total = North + South)::

        total = ElementPatch.builder().name("Total").type(ElementType.CONSOLIDATED).build()
        north = ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()
        south = ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()
        await tm1.dimensions.create(
            DimensionPatch.builder()
            .name("Region")
            .hierarchies([
                HierarchyPatch.builder()
                .name("Region")
                .elements([total, north, south])
                .edges([
                    EdgePatch.builder().parent_name("Total").component_name("North").weight(1.0).build(),
                    EdgePatch.builder().parent_name("Total").component_name("South").weight(1.0).build(),
                ])
                .build()
            ])
            .build()
        )

    Read a dimension with its hierarchies' names expanded in the same request::

        region = await tm1.dimensions.get(
            "Region", ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.select(Hierarchy.NAME))
        )
        names = [h.name for h in region.hierarchies]
    """

    @endpoint("EntitySet Dimensions")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Dimension]:
        """``GET Dimensions``: every dimension, control dimensions included."""
        return await self._session.collect(Tm1Request.list(_DIMENSIONS, ODataQuery.of(query, **options), Dimension))

    @endpoint("FunctionImport ModelDimensions")
    async def model_dimensions(self, query: ODataQuery | None = None, **options: Any) -> list[Dimension]:
        """``ModelDimensions()``: the dimensions that aren't control dimensions (whose names
        start with ``}``)."""
        path = ODataPath.function_import("ModelDimensions", {})
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Dimension))

    @endpoint("FunctionImport ControlDimensions")
    async def control_dimensions(self, query: ODataQuery | None = None, **options: Any) -> list[Dimension]:
        """``ControlDimensions()``: the control dimensions, such as ``}Clients``."""
        path = ODataPath.function_import("ControlDimensions", {})
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Dimension))

    @endpoint("EntitySet Dimensions")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Dimension:
        """``GET Dimensions('name')``: the dimension, or a ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_dimension(name), ODataQuery.of(query, **options), Dimension))

    @endpoint("EntitySet Dimensions")
    async def create(self, dimension: Any) -> Dimension:
        """``POST Dimensions``: creates a dimension, with its hierarchies, elements and edges if
        the patch has them (a deep insert). It needs a name; returns the dimension as created."""
        return await self._session.execute(Tm1Request.post(_DIMENSIONS, _require(dimension, "dimension"), Dimension))

    @endpoint("EntitySet Dimensions")
    async def update(self, name: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Dimensions('name')``: changes the properties the patch sets. With ``etag``
        (``Dimension.etag``, from a read), sent as ``If-Match``: only if the dimension hasn't
        changed since, else a ``Tm1PreconditionFailedError``.

        TM1 (11.8) accepts ``Hierarchies`` and ``AllLeavesHierarchyName`` in a dimension PATCH
        and ignores them: change hierarchies through their own service, and add an all-leaves
        hierarchy with :meth:`add_all_leaves_hierarchy`."""
        request = Tm1Request.patch(_dimension(name), _require(patch, "patch"))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("EntitySet Dimensions")
    async def delete(self, name: str) -> None:
        """``DELETE Dimensions('name')``."""
        await self._session.execute(Tm1Request.delete(_dimension(name)))

    @endpoint("Action Dimension/SaveAs")
    async def save_as(self, name: str, new_name: str, overwrite: bool | None = None, keep_existing_attributes: bool | None = None) -> Dimension:
        """``tm1.SaveAs``: copies a dimension under a new name, failing if one exists, unless
        ``overwrite``. When overwriting, ``keep_existing_attributes`` keeps the attributes of
        what's replaced; when not, true creates no attributes for the copy. Returns the copy."""
        body: dict[str, Any] = {"Name": _require(new_name, "new_name")}
        if overwrite is not None or keep_existing_attributes is not None:
            body["Overwrite"] = bool(overwrite)
            body["KeepExistingAttributes"] = bool(keep_existing_attributes)
        return await self._session.execute(_post(_dimension(name), Tm1Operations.DIMENSION_SAVE_AS, body, Dimension))

    @endpoint("Action Dimension/AddAllLeavesHierarchy")
    async def add_all_leaves_hierarchy(self, name: str, hierarchy_name: str | None = None, visible: bool | None = None) -> Hierarchy:
        """``tm1.AddAllLeavesHierarchy``: adds a hierarchy of all the dimension's leaves, which
        makes it a multi-hierarchy dimension. Without a name, TM1 names it ``Leaves`` and makes
        it not visible. Returns the new hierarchy."""
        body: dict[str, Any] = {}
        if hierarchy_name is not None:
            body["AllLeavesHierarchyName"] = hierarchy_name
        if visible is not None:
            body["Visible"] = visible
        operation = Tm1Operations.DIMENSION_ADD_ALL_LEAVES_HIERARCHY
        return await self._session.execute(_post(_dimension(name), operation, body, Hierarchy))

    @endpoint("Action Dimension/Lock")
    async def lock(self, name: str) -> None:
        """``tm1.Lock``: stops everyone else changing the dimension until it's unlocked."""
        await self._session.execute(_post(_dimension(name), Tm1Operations.DIMENSION_LOCK, {}, None))

    @endpoint("Action Dimension/Unlock")
    async def unlock(self, name: str) -> None:
        """``tm1.Unlock``."""
        await self._session.execute(_post(_dimension(name), Tm1Operations.DIMENSION_UNLOCK, {}, None))

    @endpoint("Action Dimension/Reserve")
    async def reserve(self, name: str) -> None:
        """``tm1.Reserve``: stops everyone else changing the dimension until it's released.
        Needs schema 11.8.34."""
        await self._session.execute(_post(_dimension(name), Tm1Operations.DIMENSION_RESERVE, {}, None))

    @endpoint("Action Dimension/Release")
    async def release(self, name: str) -> None:
        """``tm1.Release``. Needs schema 11.8.34."""
        await self._session.execute(_post(_dimension(name), Tm1Operations.DIMENSION_RELEASE, {}, None))


def _without_structure(patch: Any) -> Any:
    # Bindings to the collections (Elements@odata.bind) are structure changes too
    if any(k in ("Elements", "Edges") or k.startswith(("Elements@", "Edges@")) for k in _keys(patch)):
        raise ValueError("A hierarchy PATCH with Elements or Edges replaces them, deleting what it doesn't list: use replace_structure")
    return patch


class HierarchyService(Service):
    """Hierarchies: ``/Dimensions('d')/Hierarchies``, a dimension's default hierarchy, and the
    operations bound to a hierarchy. Elements, edges, members, levels and subsets have services
    of their own.

    A hierarchy PATCH that sets ``Elements`` or ``Edges`` *replaces* them: TM1 deletes the
    elements it doesn't list. So :meth:`update` refuses such a patch, and
    :meth:`replace_structure` does it by name.

    Add a hierarchy to a dimension, with its elements and edges (a second hierarchy of Region,
    rolling the same leaves up by manager)::

        await tm1.hierarchies.create("Region", HierarchyPatch.builder()
            .name("By Manager")
            .elements([
                ElementPatch.builder().name("Smith").type(ElementType.CONSOLIDATED).build(),
                ElementPatch.builder().name("North").type(ElementType.NUMERIC).build(),
                ElementPatch.builder().name("South").type(ElementType.NUMERIC).build(),
            ])
            .edges([
                EdgePatch.builder().parent_name("Smith").component_name("North").weight(1.0).build(),
                EdgePatch.builder().parent_name("Smith").component_name("South").weight(1.0).build(),
            ])
            .build())

    Replace a hierarchy's structure, knowing that TM1 deletes every other element of the
    hierarchy, with its data::

        all_ = ElementPatch.builder().name("All Versions").type(ElementType.CONSOLIDATED).build()
        actual = ElementPatch.builder().name("Actual").type(ElementType.NUMERIC).build()
        budget = ElementPatch.builder().name("Budget").type(ElementType.NUMERIC).build()
        await tm1.hierarchies.replace_structure("Version", "Version", [all_, actual, budget], [
            EdgePatch.builder().parent_name("All Versions").component_name("Actual").weight(1.0).build(),
            EdgePatch.builder().parent_name("All Versions").component_name("Budget").weight(1.0).build(),
        ])

    Read the roots of a hierarchy, the members with no parent, in order::

        roots = await tm1.hierarchies.root_members("Region", "Region", ODataQuery.none().select(Member.NAME))
    """

    @endpoint("Navigation Dimension/Hierarchies")
    async def list(self, dimension: str, query: ODataQuery | None = None, **options: Any) -> list[Hierarchy]:
        """``GET Dimensions('d')/Hierarchies``: the dimension's hierarchies."""
        return await self._session.collect(Tm1Request.list(_hierarchies(dimension), ODataQuery.of(query, **options), Hierarchy))

    @endpoint("Navigation Dimension/Hierarchies")
    async def get(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> Hierarchy:
        """``GET Dimensions('d')/Hierarchies('h')``: the hierarchy, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_hierarchy(dimension, hierarchy), ODataQuery.of(query, **options), Hierarchy))

    @endpoint("Navigation Dimension/Hierarchies")
    async def create(self, dimension: str, hierarchy: Any) -> Hierarchy:
        """``POST Dimensions('d')/Hierarchies``: adds a hierarchy, with its elements and edges if
        the patch has them. It needs a name; returns the hierarchy as created."""
        return await self._session.execute(Tm1Request.post(_hierarchies(dimension), _require(hierarchy, "hierarchy"), Hierarchy))

    @endpoint("Navigation Dimension/Hierarchies")
    async def update(self, dimension: str, hierarchy: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Dimensions('d')/Hierarchies('h')``: changes the properties the patch sets;
        with ``etag``, only if the hierarchy hasn't changed since it was read (``If-Match``).
        TM1 (11.8) ignores ``Visible``.

        Raises ``ValueError`` if the patch sets or binds ``Elements`` or ``Edges``, which would
        replace them: use :meth:`replace_structure`."""
        request = Tm1Request.patch(_hierarchy(dimension, hierarchy), _without_structure(_require(patch, "patch")))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("Navigation Dimension/Hierarchies")
    async def replace_structure(self, dimension: str, hierarchy: str, elements: Sequence[Any], edges: Sequence[Any]) -> None:
        """``PATCH Dimensions('d')/Hierarchies('h')`` with ``Elements`` and ``Edges``: makes them
        exactly these. TM1 deletes every element not listed, with its data."""
        patch = HierarchyPatch.builder().elements(list(elements)).edges(list(edges)).build()
        await self._session.execute(Tm1Request.patch(_hierarchy(dimension, hierarchy), patch))

    @endpoint("Navigation Dimension/Hierarchies")
    async def delete(self, dimension: str, hierarchy: str) -> None:
        """``DELETE Dimensions('d')/Hierarchies('h')``."""
        await self._session.execute(Tm1Request.delete(_hierarchy(dimension, hierarchy)))

    @endpoint("Navigation Dimension/DefaultHierarchy")
    async def default_hierarchy(self, dimension: str, query: ODataQuery | None = None, **options: Any) -> Hierarchy:
        """``GET Dimensions('d')/DefaultHierarchy``."""
        path = _dimension(dimension).nav("DefaultHierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))

    @endpoint("Navigation Hierarchy/Dimension")
    async def dimension(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> Dimension:
        """``GET Dimensions('d')/Hierarchies('h')/Dimension``: the hierarchy's dimension."""
        path = _hierarchy(dimension, hierarchy).nav("Dimension")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Dimension))

    @endpoint("Navigation Hierarchy/AllMember")
    async def all_member(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> Member | None:
        """``GET Dimensions('d')/Hierarchies('h')/AllMember``: the member that stands for the
        whole hierarchy. TM1 (11.8) has one only when the hierarchy has a single root, which is
        then that root; with several roots it sends no content, and this returns None."""
        path = _hierarchy(dimension, hierarchy).nav("AllMember")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Member | None))

    @endpoint("Navigation Hierarchy/DefaultMember")
    async def default_member(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> Member:
        """``GET Dimensions('d')/Hierarchies('h')/DefaultMember``."""
        path = _hierarchy(dimension, hierarchy).nav("DefaultMember")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Member))

    @endpoint("Function Hierarchy/RootMembers")
    async def root_members(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``tm1.RootMembers()``: the members with no parent. Needs schema 11.4.7."""
        operation = Tm1Operations.HIERARCHY_ROOT_MEMBERS
        path = _hierarchy(dimension, hierarchy).function(operation.name, {})
        request = Tm1Request.list(path, ODataQuery.of(query, **options), Member).requires(operation.capability())
        return await self._session.collect(request)

    @endpoint("Action Hierarchy/SaveAs")
    async def save_as(
        self,
        dimension: str,
        hierarchy: str,
        new_name: str,
        overwrite: bool | None = None,
        keep_existing_attributes: bool | None = None,
    ) -> Hierarchy:
        """``tm1.SaveAs``: copies a hierarchy under a new name in the same dimension, failing
        if one exists, unless ``overwrite``. When overwriting, ``keep_existing_attributes``
        keeps the attributes of what's replaced; when not, true creates no attributes for the
        copy. Returns the copy."""
        body: dict[str, Any] = {"Name": _require(new_name, "new_name")}
        if overwrite is not None or keep_existing_attributes is not None:
            body["Overwrite"] = bool(overwrite)
            body["KeepExistingAttributes"] = bool(keep_existing_attributes)
        target = _hierarchy(dimension, hierarchy)
        return await self._session.execute(_post(target, Tm1Operations.HIERARCHY_SAVE_AS, body, Hierarchy))

    @endpoint("Action Hierarchy/SetElement")
    async def set_element(self, dimension: str, hierarchy: str, element: str, before: str | None = None) -> None:
        """``tm1.SetElement``: makes an element a root of the hierarchy, placed just before the
        root ``before``, or without it *last* among the roots. (``$metadata`` says first; TM1
        11.8 puts it last.)"""
        body = {"Element@odata.bind": str(_element(dimension, hierarchy, element))}
        if before is not None:
            body["Before@odata.bind"] = str(_element(dimension, hierarchy, before))
        target = _hierarchy(dimension, hierarchy)
        await self._session.execute(_post(target, Tm1Operations.HIERARCHY_SET_ELEMENT, body, None))


def _checked_element(patch: Any) -> Any:
    keys = _keys(patch)
    if "Name" in keys:
        raise ValueError("TM1 ignores Name in an element PATCH: elements can't be renamed")
    if "Attributes" in keys:
        raise ValueError("TM1 rejects Attributes in an element PATCH: attribute values are cells of }ElementAttributes_<dimension>")
    return patch


class ElementService(Service):
    """Elements: ``/Dimensions('d')/Hierarchies('h')/Elements``, an element's parents,
    components and edges, and the operations bound to an element.

    TM1 (11.8) ignores ``Name`` in an element PATCH, so elements can't be renamed, and rejects
    ``Attributes``, whose values are cells of the ``}ElementAttributes_<dimension>`` cube.
    :meth:`update` refuses both, saying why.

    List elements with a query: here, the names of the consolidations, in name order::

        elements = await tm1.elements.list("Region", "Region", ODataQuery.none()
            .select(Element.NAME)
            .filter(Element.TYPE.eq(ElementType.CONSOLIDATED))
            .order_by(Element.NAME))

    Add an element under a consolidation. A new element is a root until it has a parent;
    ``set_component`` then puts it last under Total::

        await tm1.elements.create("Region", "Region", ElementPatch.builder().name("West").type(ElementType.NUMERIC).build())
        await tm1.elements.set_component("Region", "Region", "Total", "West")

    Read a consolidation's children, in order::

        children = await tm1.elements.components("Region", "Region", "Total", ODataQuery.none().select(Element.NAME))
    """

    @endpoint("Navigation Hierarchy/Elements")
    async def list(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> list[Element]:
        """``GET …/Hierarchies('h')/Elements``."""
        return await self._session.collect(Tm1Request.list(_elements(dimension, hierarchy), ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Hierarchy/Elements")
    async def get(self, dimension: str, hierarchy: str, element: str, query: ODataQuery | None = None, **options: Any) -> Element:
        """``GET …/Elements('e')``: the element, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_element(dimension, hierarchy, element), ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Hierarchy/Elements")
    async def create(self, dimension: str, hierarchy: str, element: Any) -> Element:
        """``POST …/Elements``: adds an element, as a root of the hierarchy."""
        return await self._session.execute(Tm1Request.post(_elements(dimension, hierarchy), _require(element, "element"), Element))

    @endpoint("Navigation Hierarchy/Elements")
    async def update(self, dimension: str, hierarchy: str, element: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH …/Elements('e')``: changes the properties the patch sets, such as its type;
        with ``etag``, only if the element hasn't changed since it was read (``If-Match``).

        Raises ``ValueError`` if the patch sets ``Name``, which TM1 ignores, or ``Attributes``,
        which it rejects."""
        request = Tm1Request.patch(_element(dimension, hierarchy, element), _checked_element(_require(patch, "patch")))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("Navigation Hierarchy/Elements")
    async def delete(self, dimension: str, hierarchy: str, element: str) -> None:
        """``DELETE …/Elements('e')``, with the element's edges and data."""
        await self._session.execute(Tm1Request.delete(_element(dimension, hierarchy, element)))

    @endpoint("Navigation Element/Parents")
    async def parents(self, dimension: str, hierarchy: str, element: str, query: ODataQuery | None = None, **options: Any) -> list[Element]:
        """``GET …/Elements('e')/Parents``."""
        path = _element(dimension, hierarchy, element).nav("Parents")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Element/Components")
    async def components(self, dimension: str, hierarchy: str, element: str, query: ODataQuery | None = None, **options: Any) -> list[Element]:
        """``GET …/Elements('e')/Components``: a consolidation's children, in order."""
        path = _element(dimension, hierarchy, element).nav("Components")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Element/Edges")
    async def edges(self, dimension: str, hierarchy: str, element: str, query: ODataQuery | None = None, **options: Any) -> list[Edge]:
        """``GET …/Elements('e')/Edges``."""
        path = _element(dimension, hierarchy, element).nav("Edges")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Edge))

    @endpoint("Navigation Element/Hierarchy")
    async def hierarchy(self, dimension: str, hierarchy: str, element: str, query: ODataQuery | None = None, **options: Any) -> Hierarchy:
        """``GET …/Elements('e')/Hierarchy``."""
        path = _element(dimension, hierarchy, element).nav("Hierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))

    @endpoint("Action Element/SetComponent")
    async def set_component(
        self,
        dimension: str,
        hierarchy: str,
        parent: str,
        child: str,
        *,
        before: str | None = None,
        weight: float | None = None,
    ) -> None:
        """``tm1.SetComponent``: makes ``child`` a child of ``parent``, with ``weight`` (TM1's
        default is 1), placed just before the child ``before``, or without it *last* among its
        children. (``$metadata`` doesn't say; TM1 11.8 puts it last.)"""
        body: dict[str, Any] = {"Element@odata.bind": str(_element(dimension, hierarchy, child))}
        if before is not None:
            body["Before@odata.bind"] = str(_element(dimension, hierarchy, before))
        if weight is not None:
            body["Weight"] = float(weight)
        target = _element(dimension, hierarchy, parent)
        await self._session.execute(_post(target, Tm1Operations.ELEMENT_SET_COMPONENT, body, None))

    @endpoint("Action Element/Lock")
    async def lock(self, dimension: str, hierarchy: str, element: str) -> None:
        """``tm1.Lock``: stops everyone else changing the element until it's unlocked. Needs
        schema 11.8.23."""
        await self._session.execute(_post(_element(dimension, hierarchy, element), Tm1Operations.ELEMENT_LOCK, {}, None))

    @endpoint("Action Element/Unlock")
    async def unlock(self, dimension: str, hierarchy: str, element: str) -> None:
        """``tm1.Unlock``. Needs schema 11.8.23."""
        await self._session.execute(_post(_element(dimension, hierarchy, element), Tm1Operations.ELEMENT_UNLOCK, {}, None))

    @endpoint("Action Element/Reserve")
    async def reserve(self, dimension: str, hierarchy: str, element: str) -> None:
        """``tm1.Reserve``: stops everyone else changing the element's data until it's released.
        Needs schema 11.8.34."""
        await self._session.execute(_post(_element(dimension, hierarchy, element), Tm1Operations.ELEMENT_RESERVE, {}, None))

    @endpoint("Action Element/Release")
    async def release(self, dimension: str, hierarchy: str, element: str) -> None:
        """``tm1.Release``. Needs schema 11.8.34."""
        await self._session.execute(_post(_element(dimension, hierarchy, element), Tm1Operations.ELEMENT_RELEASE, {}, None))


def _edge(dimension: str, hierarchy: str, parent: str, component: str) -> ODataPath:
    key = {"ParentName": _require(parent, "parent"), "ComponentName": _require(component, "component")}
    return _hierarchy(dimension, hierarchy).nav("Edges").key(key)


class EdgeService(Service):
    """Edges: ``/Dimensions('d')/Hierarchies('h')/Edges``, the parent-child links of a
    hierarchy, each keyed by its parent's and its component's names.

    List a hierarchy's edges, as "parent > child (weight)"::

        edges = await tm1.edges.list("Region", "Region", ODataQuery.none())
        lines = [f"{e.parent_name} > {e.component_name} ({e.weight})" for e in edges]

    Change an edge's weight, to make Cost count negatively in Margin::

        await tm1.edges.update("Account", "Account", "Margin", "Cost", EdgePatch.builder().weight(-1.0).build())

    Move an element to another parent: add the new edge, then delete the old::

        edge = await tm1.edges.create("Region", "Region",
            EdgePatch.builder().parent_name("Total Sales").component_name("North").weight(1.0).build())
        await tm1.edges.delete("Region", "Region", "Total", "North")
    """

    @endpoint("Navigation Hierarchy/Edges")
    async def list(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> list[Edge]:
        """``GET …/Hierarchies('h')/Edges``."""
        path = _hierarchy(dimension, hierarchy).nav("Edges")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Edge))

    @endpoint("Navigation Hierarchy/Edges")
    async def get(
        self,
        dimension: str,
        hierarchy: str,
        parent: str,
        component: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Edge:
        """``GET …/Edges(ParentName='p',ComponentName='c')``."""
        path = _edge(dimension, hierarchy, parent, component)
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Edge))

    @endpoint("Navigation Hierarchy/Edges")
    async def create(self, dimension: str, hierarchy: str, edge: Any) -> Edge:
        """``POST …/Edges``: makes an element a child of a consolidation."""
        path = _hierarchy(dimension, hierarchy).nav("Edges")
        return await self._session.execute(Tm1Request.post(path, _require(edge, "edge"), Edge))

    @endpoint("Navigation Hierarchy/Edges")
    async def update(self, dimension: str, hierarchy: str, parent: str, component: str, patch: Any) -> None:
        """``PATCH …/Edges(ParentName='p',ComponentName='c')``: changes the edge's weight."""
        await self._session.execute(Tm1Request.patch(_edge(dimension, hierarchy, parent, component), _require(patch, "patch")))

    @endpoint("Navigation Hierarchy/Edges")
    async def delete(self, dimension: str, hierarchy: str, parent: str, component: str) -> None:
        """``DELETE …/Edges(ParentName='p',ComponentName='c')``: takes an element out of a
        consolidation; the element stays in the hierarchy."""
        await self._session.execute(Tm1Request.delete(_edge(dimension, hierarchy, parent, component)))

    @endpoint("Navigation Edge/Parent")
    async def parent(
        self,
        dimension: str,
        hierarchy: str,
        parent: str,
        component: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Element:
        """``GET …/Edges(…)/Parent``."""
        path = _edge(dimension, hierarchy, parent, component).nav("Parent")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Edge/Component")
    async def component(
        self,
        dimension: str,
        hierarchy: str,
        parent: str,
        component: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Element:
        """``GET …/Edges(…)/Component``."""
        path = _edge(dimension, hierarchy, parent, component).nav("Component")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Edge/Hierarchy")
    async def hierarchy(
        self,
        dimension: str,
        hierarchy: str,
        parent: str,
        component: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Hierarchy:
        """``GET …/Edges(…)/Hierarchy``."""
        path = _edge(dimension, hierarchy, parent, component).nav("Hierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))


class LevelService(Service):
    """Levels: ``/Dimensions('d')/Hierarchies('h')/Levels``, keyed by number, and their
    members. Read only: TM1 derives levels from the hierarchy's structure.

    List a hierarchy's levels, with how many members each has, as "1: 2"::

        levels = await tm1.levels.list("Region", "Region", ODataQuery.none())
        lines = [f"{level.number}: {level.cardinality}" for level in levels]

    Read a level's members. Levels count from the top: level 0 holds the roots, level 1 their
    children::

        members = await tm1.levels.members("Region", "Region", 1, ODataQuery.none().select(Member.NAME))
    """

    @endpoint("Navigation Hierarchy/Levels")
    async def list(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> list[Level]:
        """``GET …/Hierarchies('h')/Levels``."""
        path = _hierarchy(dimension, hierarchy).nav("Levels")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Level))

    @endpoint("Navigation Hierarchy/Levels")
    async def get(self, dimension: str, hierarchy: str, number: int, query: ODataQuery | None = None, **options: Any) -> Level:
        """``GET …/Levels(n)``."""
        path = _hierarchy(dimension, hierarchy).nav("Levels").key(int(number))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Level))

    @endpoint("Navigation Level/Members")
    async def members(self, dimension: str, hierarchy: str, number: int, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``GET …/Levels(n)/Members``."""
        path = _hierarchy(dimension, hierarchy).nav("Levels").key(int(number)).nav("Members")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Level/Hierarchy")
    async def hierarchy(self, dimension: str, hierarchy: str, number: int, query: ODataQuery | None = None, **options: Any) -> Hierarchy:
        """``GET …/Levels(n)/Hierarchy``."""
        path = _hierarchy(dimension, hierarchy).nav("Levels").key(int(number)).nav("Hierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))


def _member(dimension: str, hierarchy: str, member: str) -> ODataPath:
    return _hierarchy(dimension, hierarchy).nav("Members").key(_require(member, "member"))


class MemberService(Service):
    """Members: ``/Dimensions('d')/Hierarchies('h')/Members``, the MDX view of a hierarchy's
    elements, and a member's parent, children, element, level and hierarchy. Read only:
    members change through their elements.

    Read a member's unique name, such as ``[Region].[Region].[North]``, for use in MDX::

        north = await tm1.members.get("Region", "Region", "North", ODataQuery.none().select(Member.UNIQUE_NAME))
        north.unique_name

    Read a member's children, in order::

        children = await tm1.members.children("Region", "Region", "Total", ODataQuery.none().select(Member.NAME))

    Read a member's parent (the first, if it has several), which a root doesn't have::

        parent = await tm1.members.parent("Region", "Region", "North", ODataQuery.none().select(Member.NAME))
        name = parent.name if parent is not None else "(a root)"
    """

    @endpoint("Navigation Hierarchy/Members")
    async def list(self, dimension: str, hierarchy: str, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``GET …/Hierarchies('h')/Members``."""
        path = _hierarchy(dimension, hierarchy).nav("Members")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Hierarchy/Members")
    async def get(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> Member:
        """``GET …/Members('m')``."""
        path = _member(dimension, hierarchy, member)
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Member/Parent")
    async def parent(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> Member | None:
        """``GET …/Members('m')/Parent``. A member with several parents has the first; a root
        has none, and this returns None."""
        path = _member(dimension, hierarchy, member).nav("Parent")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Member | None))

    @endpoint("Navigation Member/Children")
    async def children(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``GET …/Members('m')/Children``."""
        path = _member(dimension, hierarchy, member).nav("Children")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Member/Element")
    async def element(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> Element:
        """``GET …/Members('m')/Element``."""
        path = _member(dimension, hierarchy, member).nav("Element")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Member/Level")
    async def level(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> Level:
        """``GET …/Members('m')/Level``."""
        path = _member(dimension, hierarchy, member).nav("Level")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Level))

    @endpoint("Navigation Member/Hierarchy")
    async def hierarchy(self, dimension: str, hierarchy: str, member: str, query: ODataQuery | None = None, **options: Any) -> Hierarchy:
        """``GET …/Members('m')/Hierarchy``."""
        path = _member(dimension, hierarchy, member).nav("Hierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))


_SUBSET_SETS = ("Navigation Hierarchy/Subsets", "Navigation Hierarchy/PrivateSubsets", "Navigation Hierarchy/SessionSubsets")


def _subsets(dimension: str, hierarchy: str, scope: SubsetScope) -> ODataPath:
    return _hierarchy(dimension, hierarchy).nav(_require(scope, "scope").navigation())


def _subset(dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> ODataPath:
    return _subsets(dimension, hierarchy, scope).key(_require(subset, "subset"))


def _without_elements(patch: Any) -> Any:
    if any(k == "Elements" or k.startswith("Elements@") for k in _keys(patch)):
        raise ValueError("A subset PATCH with Elements appends to them: use add_elements, or clear_elements first")
    return patch


class SubsetService(Service):
    """Subsets: a hierarchy's public, private and session subsets (:class:`SubsetScope`), their
    elements, and the operations bound to a subset or creating a session subset.

    A static subset lists its elements; a dynamic one has an MDX ``Expression``. Binding
    ``Elements`` in a subset PATCH *appends* to them, so :meth:`update` refuses it:
    :meth:`add_elements` appends by name and :meth:`clear_elements` empties the list.

    Create a static subset, binding its elements by their paths::

        elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements")
        await tm1.subsets.create("Region", "Region", SubsetScope.PUBLIC,
            SubsetPatch.builder().name("Key Regions").bind_elements(elements.key("North"), elements.key("South")).build())

    Create a dynamic subset from an MDX expression, evaluated each time it's read::

        await tm1.subsets.create("Region", "Region", SubsetScope.PUBLIC,
            SubsetPatch.builder().name("All Leaves")
            .expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}").build())

    Replace a static subset's elements: a PATCH appends elements, so empty the list, then add
    the new ones in order::

        await tm1.subsets.clear_elements("Region", "Region", SubsetScope.PUBLIC, "Key Regions")
        await tm1.subsets.add_elements("Region", "Region", SubsetScope.PUBLIC, "Key Regions", ["East", "West"])

    Create a session subset and read its elements by the name TM1 gave it (TM1 names a session
    subset itself, ignoring the name sent)::

        subset = await tm1.subsets.create_session_subset("Region", "Region",
            SubsetPatch.builder().name("Regions").expression("{[Region].[Region].[Total].Children}").build())
        elements = await tm1.subsets.elements("Region", "Region", SubsetScope.SESSION, subset.name,
            ODataQuery.none().select(Element.NAME))
    """

    @endpoint(*_SUBSET_SETS)
    async def list(self, dimension: str, hierarchy: str, scope: SubsetScope, query: ODataQuery | None = None, **options: Any) -> list[Subset]:
        """``GET …/Hierarchies('h')/Subsets`` (or the private or session ones). TM1 (11.8) lists
        no session subsets, though each reads by the name :meth:`create_session_subset` returned."""
        path = _subsets(dimension, hierarchy, scope)
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Subset))

    @endpoint(*_SUBSET_SETS)
    async def get(
        self,
        dimension: str,
        hierarchy: str,
        scope: SubsetScope,
        subset: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Subset:
        """``GET …/Subsets('s')``."""
        path = _subset(dimension, hierarchy, scope, subset)
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Subset))

    @endpoint(*_SUBSET_SETS)
    async def create(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: Any) -> Subset:
        """``POST …/Subsets``: creates a subset: static with ``Elements@odata.bind``
        (``SubsetPatch.Builder.bind_elements``), dynamic with an ``Expression``."""
        path = _subsets(dimension, hierarchy, scope)
        return await self._session.execute(Tm1Request.post(path, _require(subset, "subset"), Subset))

    @endpoint(*_SUBSET_SETS)
    async def update(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH …/Subsets('s')``: changes the properties the patch sets, such as a dynamic
        subset's expression; with ``etag``, only if it hasn't changed since it was read.

        Raises ``ValueError`` if the patch sets or binds ``Elements``, which TM1 appends: use
        :meth:`add_elements` or :meth:`clear_elements`."""
        request = Tm1Request.patch(_subset(dimension, hierarchy, scope, subset), _without_elements(_require(patch, "patch")))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint(*_SUBSET_SETS)
    async def add_elements(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str, elements: Sequence[str]) -> None:
        """``PATCH …/Subsets('s')`` binding ``Elements``: appends elements to a static subset, in
        order."""
        bindings = [str(_element(dimension, hierarchy, e)) for e in elements]
        await self._session.execute(Tm1Request.patch(_subset(dimension, hierarchy, scope, subset), {"Elements@odata.bind": bindings}))

    @endpoint("Navigation Subset/Elements")
    async def clear_elements(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> None:
        """``DELETE …/Subsets('s')/Elements/$ref``: removes every element from a static subset;
        the elements stay in the hierarchy."""
        await self._session.execute(Tm1Request.delete(_subset(dimension, hierarchy, scope, subset).nav("Elements").ref()))

    @endpoint(*_SUBSET_SETS)
    async def delete(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> None:
        """``DELETE …/Subsets('s')``."""
        await self._session.execute(Tm1Request.delete(_subset(dimension, hierarchy, scope, subset)))

    @endpoint("Navigation Subset/Elements")
    async def elements(
        self,
        dimension: str,
        hierarchy: str,
        scope: SubsetScope,
        subset: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> list[Element]:
        """``GET …/Subsets('s')/Elements``: a static subset's elements in order, or what a
        dynamic one's expression gives now."""
        path = _subset(dimension, hierarchy, scope, subset).nav("Elements")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Element))

    @endpoint("Navigation Subset/Hierarchy")
    async def hierarchy(
        self,
        dimension: str,
        hierarchy: str,
        scope: SubsetScope,
        subset: str,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> Hierarchy:
        """``GET …/Subsets('s')/Hierarchy``."""
        path = _subset(dimension, hierarchy, scope, subset).nav("Hierarchy")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Hierarchy))

    @endpoint("Action Subset/SaveAs")
    async def save_as(
        self,
        dimension: str,
        hierarchy: str,
        scope: SubsetScope,
        subset: str,
        new_name: str,
        make_private: bool | None = None,
        make_static: bool | None = None,
    ) -> Subset:
        """``tm1.SaveAs``: copies a subset under a new name: by default as public and keeping
        whether it's static or dynamic; ``make_private`` and ``make_static`` change that
        (a static copy holds the elements a dynamic one gives now)."""
        body: dict[str, Any] = {"Name": _require(new_name, "new_name")}
        if make_private is not None or make_static is not None:
            body["MakePrivate"] = bool(make_private)
            body["MakeStatic"] = bool(make_static)
        target = _subset(dimension, hierarchy, scope, subset)
        return await self._session.execute(_post(target, Tm1Operations.SUBSET_SAVE_AS, body, Subset))

    @endpoint("Action Subset/SetElement")
    async def set_element(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str, element: str) -> None:
        """``tm1.SetElement``: adds an element to a static subset, or moves it if it's there.
        TM1 (11.8) appends it when no position is given."""
        await self._set_element(dimension, hierarchy, scope, subset, element, None, None)

    @endpoint("Action Subset/SetElement")
    async def set_element_before(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str, element: str, before: str) -> None:
        """``tm1.SetElement`` with ``Before``: places an element just before another in a static subset."""
        await self._set_element(dimension, hierarchy, scope, subset, element, "Before@odata.bind", _require(before, "before"))

    @endpoint("Action Subset/SetElement")
    async def set_element_after(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str, element: str, after: str) -> None:
        """``tm1.SetElement`` with ``After``: places an element just after another in a static subset."""
        await self._set_element(dimension, hierarchy, scope, subset, element, "After@odata.bind", _require(after, "after"))

    @endpoint("Action Hierarchy/CreateSessionSubset")
    async def create_session_subset(self, dimension: str, hierarchy: str, definition: Any) -> Subset:
        """``tm1.CreateSessionSubset`` with a definition: creates a subset visible only to this
        session. TM1 (11.8) ignores the definition's ``Name`` and names it itself (like an ID,
        ``yExesF4AAIABAAAQ``); the result has the name to use."""
        body = {"Subset": _require(definition, "definition")}
        target = _hierarchy(dimension, hierarchy)
        return await self._session.execute(_post(target, Tm1Operations.HIERARCHY_CREATE_SESSION_SUBSET, body, Subset))

    @endpoint("Action Hierarchy/CreateSessionSubset")
    async def create_session_subset_from(self, dimension: str, hierarchy: str, scope: SubsetScope, subset: str) -> Subset:
        """``tm1.CreateSessionSubset`` with a reference: copies a subset into one visible only to
        this session."""
        body = {"Subset@odata.bind": str(_subset(dimension, hierarchy, scope, subset))}
        target = _hierarchy(dimension, hierarchy)
        return await self._session.execute(_post(target, Tm1Operations.HIERARCHY_CREATE_SESSION_SUBSET, body, Subset))

    async def _set_element(
        self,
        dimension: str,
        hierarchy: str,
        scope: SubsetScope,
        subset: str,
        element: str,
        position: str | None,
        other: str | None,
    ) -> None:
        body = {"Element@odata.bind": str(_element(dimension, hierarchy, element))}
        if position is not None and other is not None:
            body[position] = str(_element(dimension, hierarchy, other))
        target = _subset(dimension, hierarchy, scope, subset)
        await self._session.execute(_post(target, Tm1Operations.SUBSET_SET_ELEMENT, body, None))
