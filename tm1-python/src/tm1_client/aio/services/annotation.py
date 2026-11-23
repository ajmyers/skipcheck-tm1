"""Services for annotations on cube cells (the Java client's ``net.ajmyers.tm1.client.service.annotation``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..._calls import endpoint
from ..._generated.model import (
    Annotation,
    ApplicationContextFacet,
    ApplicationContextFacetValue,
    Attachment,
    Member,
)
from ..._generated.operations import Tm1Operations
from ..._generated.patches import AnnotationPatch, ApplicationContextFacetValuePatch
from ...base import Tm1Model, Tm1Patch
from ...odata import ODataPath, ODataQuery
from ...request import Tm1Request
from ._base import Service, _require

__all__ = ["AnnotationService"]

_ANNOTATIONS = ODataPath.of("Annotations")
_FACETS = ODataPath.of("ApplicationContextFacets")


class AnnotationService(Service):
    """Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and
    the application context facets that tie them to cubes.

    What TM1 (11.8) does here that you might not expect:

    * ``GET Annotations`` lists none, even with annotations made. They're found by cube
      (:meth:`cube_annotations`), by cell (:meth:`cell_annotations`), or by ID.
    * An annotation's ``ApplicationContext`` reads back empty, though the facet value given
      when it was made exists (:meth:`facet_values`).
    * Besides ``Text``, an annotation carries properties ``$metadata`` doesn't declare
      (``Annotation`` is an open type): ``objectName`` (the cube), ``commentValue``,
      ``commentType`` and ``commentLocation`` (the cell's elements, comma-separated). They're
      in ``model_extra``.
    * IDs look like ``C}ClientGroups~B.B~7uA_4TxLwG``.

    Comment on a cell, as Planning Analytics Workspace does: the cell's elements by dimension,
    in the cube's dimension order::

        cell = {"Version": "Forecast", "Region": "North", "Measure": "Amount"}
        await tm1.annotations.create("Sales", cell, "Includes the March promotion")

    Read a cube's comments (``GET Annotations`` lists none on TM1 11.8)::

        for a in await tm1.annotations.cube_annotations("Sales"):
            print(f"{a.creator}: {a.text}")

    Change what a comment says: TM1 keeps ``Text`` and the ``commentValue`` PAW shows apart,
    so change both::

        text = "Includes the March and April promotions"
        await tm1.annotations.update(id, AnnotationPatch.builder().text(text).property("commentValue", text).build())
    """

    @endpoint("EntitySet Annotations")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Annotation]:
        """``GET Annotations``. TM1 (11.8) lists none here: use :meth:`cube_annotations` or
        :meth:`cell_annotations`."""
        return await self._session.collect(Tm1Request.list(_ANNOTATIONS, ODataQuery.of(query, **options), Annotation))

    @endpoint("EntitySet Annotations")
    async def get(self, id: str, query: ODataQuery | None = None, **options: Any) -> Annotation:
        """``GET Annotations('id')``; a ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_annotation(id), ODataQuery.of(query, **options), Annotation))

    @endpoint("EntitySet Annotations")
    async def create(
        self,
        cube: Any = None,
        elements: Mapping[str, str] | None = None,
        text: str | None = None,
        annotation: Any = None,
    ) -> Annotation:
        """``POST Annotations``. Either annotates one cell the way Planning Analytics Workspace
        does (``create(cube, elements, text)``: ``Text`` and ``commentValue`` are the text, the
        ``}Cubes`` facet names the cube, and the dimensional context binds the cell's members,
        ``elements`` mapping each dimension, in the cube's order, to the cell's element in its
        same-named hierarchy), or posts an annotation built by hand (``create(annotation)``, an
        ``AnnotationPatch``). Returns the annotation, with its ID."""
        if annotation is None and isinstance(cube, (Tm1Patch, Tm1Model, Mapping)):
            cube, annotation = None, cube
        if annotation is None:
            annotation = _cell_annotation(_require(cube, "cube"), _require(elements, "elements"), _require(text, "text"))
        return await self._session.execute(Tm1Request.post(_ANNOTATIONS, annotation, Annotation))

    @endpoint("EntitySet Annotations")
    async def update(self, id: str, patch: Any) -> None:
        """``PATCH Annotations('id')``. To change what it says, set both ``Text`` and the
        ``commentValue`` property: TM1 keeps them apart. TM1 records ``LastUpdatedBy`` and
        ``LastUpdated``."""
        await self._session.execute(Tm1Request.patch(_annotation(id), _require(patch, "patch")))

    @endpoint("EntitySet Annotations")
    async def delete(self, id: str) -> None:
        """``DELETE Annotations('id')``."""
        await self._session.execute(Tm1Request.delete(_annotation(id)))

    @endpoint("Navigation Annotation/ApplicationContext")
    async def application_context(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[ApplicationContextFacetValue]:
        """``ApplicationContext``; expand ``Facet``. TM1 (11.8) returns none."""
        path = _annotation(id).nav("ApplicationContext")
        request = Tm1Request.list(path, ODataQuery.of(query, **options), ApplicationContextFacetValue)
        return await self._session.collect(request)

    @endpoint("Navigation Annotation/DimensionalContext")
    async def dimensional_context(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[Member]:
        """``DimensionalContext``: the members of the annotated cell."""
        path = _annotation(id).nav("DimensionalContext")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Member))

    @endpoint("Navigation Annotation/Attachments")
    async def attachments(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[Attachment]:
        """``Attachments``: documents attached to the annotation."""
        path = _annotation(id).nav("Attachments")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Attachment))

    @endpoint("Navigation Cube/Annotations")
    async def cube_annotations(self, cube: str, query: ODataQuery | None = None, **options: Any) -> list[Annotation]:
        """``Cubes('c')/Annotations``: a cube's annotations."""
        path = ODataPath.of("Cubes").key(_require(cube, "cube")).nav("Annotations")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Annotation))

    @endpoint("Navigation CellsetCell/Annotations")
    async def cell_annotations(self, cellset: str, ordinal: int, query: ODataQuery | None = None, **options: Any) -> list[Annotation]:
        """``Cellsets('id')/Cells(n)/Annotations``: one cellset cell's annotations. They also
        come with the cells by ``expand="Annotations"``."""
        cells = ODataPath.of("Cellsets").key(_require(cellset, "cellset")).nav("Cells")
        path = cells.key(int(ordinal)).nav("Annotations")
        return await self._session.collect(Tm1Request.list(path, ODataQuery.of(query, **options), Annotation))

    @endpoint("EntitySet ApplicationContextFacets")
    async def facets(self, query: ODataQuery | None = None, **options: Any) -> list[ApplicationContextFacet]:
        """``GET ApplicationContextFacets``, such as ``}Cubes``; expand ``Values``."""
        request = Tm1Request.list(_FACETS, ODataQuery.of(query, **options), ApplicationContextFacet)
        return await self._session.collect(request)

    @endpoint("Navigation ApplicationContextFacet/Values")
    async def facet_values(self, facet: str, query: ODataQuery | None = None, **options: Any) -> list[ApplicationContextFacetValue]:
        """A facet's ``Values``: for ``}Cubes``, the cubes with annotations."""
        path = _FACETS.key(_require(facet, "facet")).nav("Values")
        request = Tm1Request.list(path, ODataQuery.of(query, **options), ApplicationContextFacetValue)
        return await self._session.collect(request)

    @endpoint("Navigation ApplicationContextFacetValue/Facet")
    async def facet(self, facet: str, value: str, query: ODataQuery | None = None, **options: Any) -> ApplicationContextFacet:
        """A facet value's ``Facet``."""
        path = _FACETS.key(_require(facet, "facet")).nav("Values").key(_require(value, "value")).nav("Facet")
        request = Tm1Request.get(path, ODataQuery.of(query, **options), ApplicationContextFacet)
        return await self._session.execute(request)

    @endpoint("ActionImport DeleteAnnotationArtifacts")
    async def delete_annotation_artifacts(self) -> None:
        """``DeleteAnnotationArtifacts``: removes every annotation in the model, with their facet
        values, attachments and control cubes. It can't be undone."""
        operation = Tm1Operations.DELETE_ANNOTATION_ARTIFACTS
        request = Tm1Request.post(ODataPath.of(_require(operation.import_name, "import name")), None, None)
        await self._session.execute(request.requires(operation.capability()))


def _annotation(id: str) -> ODataPath:
    return _ANNOTATIONS.key(_require(id, "id"))


def _cell_annotation(cube: str, elements: Mapping[str, str], text: str) -> Any:
    members = [ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Members").key(e) for d, e in elements.items()]
    facet = ApplicationContextFacetValuePatch.builder().bind_facet(_FACETS.key("}Cubes")).value(cube).build()
    return (
        AnnotationPatch.builder()
        .text(text)
        .application_context([facet])
        .bind_dimensional_context(*members)
        .property("objectName", cube)
        .property("commentValue", text)
        .property("commentType", "ANNOTATION")
        .property("commentLocation", ",".join(elements.values()))
        .build()
    )
