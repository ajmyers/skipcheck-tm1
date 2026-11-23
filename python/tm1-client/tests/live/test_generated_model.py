"""The generated model, read straight from a real TM1 through the session: a port of the Java
client's GeneratedModelTm1Test."""

from __future__ import annotations

import uuid

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Error, Tm1Request, Tm1Session
from skipcheck_tm1._generated.operations import Tm1Operations
from skipcheck_tm1.model import (
    Cube,
    CubePatch,
    Dimension,
    DimensionPatch,
    Element,
    ElementPatch,
    ElementType,
    HierarchyPatch,
    MDXView,
    Member,
    Server,
    View,
)

from .conftest import CONFIGURED, REASON, session, started_version

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_reads_the_server_and_streams_dimensions_into_generated_types() -> None:
    with session() as s:
        server = s.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Server))
        version = started_version()
        if version is not None:
            assert server.product_version == str(version)
        assert server.etag is None, "TM1 sends no ETag for the Server singleton"

        dimensions = list(s.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().expand("Hierarchies"), Dimension)))
        assert dimensions
        for d in dimensions:
            assert d.name and d.name.strip()
            assert d.etag and d.etag.strip()
            assert d.hierarchies


def test_creates_a_view_as_its_subtype_and_reads_it_back_through_the_hierarchy() -> None:
    name = f"tm1-client-{uuid.uuid4()}"
    views = ODataPath.of("Cubes").key("}ClientGroups").nav("Views")
    with session() as s:
        try:
            mdx = "SELECT {[}Groups].[}Groups].MEMBERS} ON 0 FROM [}ClientGroups]"
            created = s.execute(Tm1Request.post(views, MDXView(name=name, mdx=mdx), View))
            assert isinstance(created, MDXView) and "}Groups" in created.mdx

            every = list(s.stream(Tm1Request.list(views, ODataQuery.none(), View)))
            (mine,) = [v for v in every if v.name == name]
            assert isinstance(mine, MDXView)

            members = list(
                s.stream(Tm1Request.list(ODataPath.of("Dimensions").key("}Groups").nav("Hierarchies").key("}Groups").nav("Members"), ODataQuery.none(), Member))
            )
            assert members and all(type(m) is Member for m in members), "plain members, not a subtype"
        finally:
            _quietly(s, Tm1Request.delete(views.key(name)))


def test_queries_with_field_constants_and_enum_literals() -> None:
    elements = ODataPath.of("Dimensions").key("}Groups").nav("Hierarchies").key("}Groups").nav("Elements")
    with session() as s:
        every = list(s.stream(Tm1Request.list(elements, ODataQuery.none().select(Element.NAME, Element.TYPE).order_by(Element.NAME), Element)))
        assert every
        assert [e.name for e in every] == sorted(e.name for e in every)
        type = every[0].type
        assert type is not None and type is not ElementType.UNKNOWN

        filtered = list(s.stream(Tm1Request.list(elements, ODataQuery.none().filter(Element.TYPE.eq(type)), Element)))
        assert len(filtered) == len([e for e in every if e.type is type])
        assert all(e.type is type for e in filtered)


def test_creates_a_dimension_and_a_cube_from_patches_then_patches_and_clears_its_rules() -> None:
    name = f"tm1-client-{uuid.uuid4()}"
    dimension = ODataPath.of("Dimensions").key(name)
    cube = ODataPath.of("Cubes").key(name)

    def rules(s: Tm1Session) -> str | None:
        return s.execute(Tm1Request.get(cube, ODataQuery.none().select(Cube.RULES), Cube)).rules

    with session() as s:
        try:
            north = ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()
            south = ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()
            hierarchy = HierarchyPatch.builder().name(name).elements([north, south]).build()
            s.execute(Tm1Request.post(ODataPath.of("Dimensions"), DimensionPatch.builder().name(name).hierarchies([hierarchy]).build(), Dimension))
            groups = ODataPath.of("Dimensions").key("}Groups")
            s.execute(Tm1Request.post(ODataPath.of("Cubes"), CubePatch.builder().name(name).bind_dimensions(dimension, groups).build(), Cube))

            created = s.execute(Tm1Request.get(cube, ODataQuery.none().expand(Cube.DIMENSIONS, lambda d: d.select(Dimension.NAME)), Cube))
            assert [d.name for d in created.dimensions] == ["Sandboxes", name, "}Groups"], "TM1 lists its virtual Sandboxes dimension first"
            elements = list(s.stream(Tm1Request.list(dimension.nav("Hierarchies").key(name).nav("Elements"), ODataQuery.none(), Element)))
            assert sorted((e.name, e.type) for e in elements) == [("North", ElementType.NUMERIC), ("South", ElementType.NUMERIC)]

            s.execute(Tm1Request.patch(cube, CubePatch.builder().rules("SKIPCHECK;").build()))
            assert "SKIPCHECK" in (rules(s) or "")
            s.execute(Tm1Request.patch(cube, CubePatch.builder().rules(None).build()))
            assert not rules(s), "an explicit null clears them"
        finally:
            _quietly(s, Tm1Request.delete(cube))
            _quietly(s, Tm1Request.delete(dimension))


def test_the_server_has_every_operation_in_the_catalog() -> None:
    with session() as s:
        capabilities = s.capabilities()
        missing = [op.endpoint() for op in Tm1Operations.ALL if not capabilities.supports(op.capability())]
        assert missing == []


def _quietly(s: Tm1Session, request: Tm1Request[None]) -> None:
    try:
        s.execute(request)
    except Tm1Error:
        pass
