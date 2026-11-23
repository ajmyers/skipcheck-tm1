"""The attribute services against a real TM1: ports of the Java client's
AttributeServiceTm1Test, CubeAttributeTm1Test and ProcessAttributeTm1Test. Skipped unless
TM1_URL is set.

Views, processes and chores are set up with plain requests, so these tests need nothing but
the attribute and dimension services."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import pytest

from tm1_client import ODataPath, Tm1BadRequestError, Tm1Client, Tm1Request
from tm1_client._sync.services.attribute import AttributeOwner, AttributeSet
from tm1_client._sync.services.cube import ViewScope
from tm1_client._sync.services.dimension import SubsetScope
from tm1_client.model import (
    AttributeDefinitionPatch,
    AttributesPatch,
    AttributeType,
    ChoreExecutionMode,
    ChorePatch,
    ChoreTaskPatch,
    CubePatch,
    DimensionPatch,
    ElementPatch,
    ElementType,
    HierarchyPatch,
    LocalizedAttributesPatch,
    MDXViewPatch,
    NativeViewPatch,
    ProcessPatch,
    SubsetPatch,
    ViewAxisSelectionPatch,
)

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def label() -> str:
    return "Label " + uuid.uuid4().hex[:4]


def string_attribute(name: str) -> AttributeDefinitionPatch:
    return AttributeDefinitionPatch.builder().name(name).type(AttributeType.STRING).build()


def french(attribute: str, value: str) -> LocalizedAttributesPatch:
    return LocalizedAttributesPatch.builder().locale_id("fr").attributes(AttributesPatch.builder().property(attribute, value).build()).build()


def value(tm1: Tm1Client, owner: AttributeOwner, attribute: str) -> object:
    return (tm1.localized_attributes.get(owner, "fr").attributes.model_extra or {}).get(attribute)


@pytest.fixture
def d(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A dimension with North and South, and a public subset S."""
    tm1.dimensions.create(
        DimensionPatch.builder()
        .name(name)
        .hierarchies(
            [
                HierarchyPatch.builder()
                .name(name)
                .elements(
                    [
                        ElementPatch.builder().name("North").type(ElementType.NUMERIC).build(),
                        ElementPatch.builder().name("South").type(ElementType.NUMERIC).build(),
                    ]
                )
                .build()
            ]
        )
        .build()
    )
    try:
        tm1.subsets.create(name, name, SubsetScope.PUBLIC, SubsetPatch.builder().name("S").expression(f"{{[{name}].[{name}].[North]}}").build())
        yield name
    finally:
        tm1.dimensions.delete(name)


# AttributeServiceTm1Test


@pytest.mark.parametrize("kind", ["dimensions", "hierarchies", "elements", "subsets"])
def test_creates_lists_reads_and_deletes_attribute_definitions(tm1: Tm1Client, d: str, kind: str) -> None:
    attribute_set = {
        "dimensions": AttributeSet.dimensions(),
        "hierarchies": AttributeSet.hierarchies(d),
        "elements": AttributeSet.elements(d, d),
        "subsets": AttributeSet.subsets(d, d),
    }[kind]
    attribute = "Code " + uuid.uuid4().hex[:4]
    created = tm1.attributes.create(attribute_set, string_attribute(attribute))
    assert created.type is AttributeType.STRING
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        assert tm1.attributes.get(attribute_set, attribute).name == attribute
    finally:
        tm1.attributes.delete(attribute_set, attribute)
    assert attribute not in [a.name for a in tm1.attributes.list(attribute_set)]


@pytest.mark.parametrize("kind", ["dimension", "hierarchy", "element", "member", "subset"])
def test_translates_attribute_values(tm1: Tm1Client, d: str, kind: str) -> None:
    attribute_set = {
        "dimension": AttributeSet.dimensions(),
        "hierarchy": AttributeSet.hierarchies(d),
        "element": AttributeSet.elements(d, d),
        "member": AttributeSet.elements(d, d),
        "subset": AttributeSet.subsets(d, d),
    }[kind]
    owner = {
        "dimension": AttributeOwner.dimension(d),
        "hierarchy": AttributeOwner.hierarchy(d, d),
        "element": AttributeOwner.element(d, d, "North"),
        "member": AttributeOwner.element(d, d, "North"),
        "subset": AttributeOwner.subset(d, d, SubsetScope.PUBLIC, "S"),
    }[kind]
    # A member's translations are its element's, and read only
    reader = AttributeOwner.member(d, d, "North") if kind == "member" else owner
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    localized = tm1.localized_attributes
    try:
        localized.create(owner, french(attribute, "Nord"))
        assert "fr" in [t.locale_id for t in localized.list(reader)]
        assert value(tm1, reader, attribute) == "Nord"
        changed = french(attribute, "Le Nord")
        fr = owner.path().nav("LocalizedAttributes").key("fr")
        with pytest.raises(Tm1BadRequestError):  # a locale can't be created twice
            localized.create(owner, changed)
        with pytest.raises(Tm1BadRequestError):  # nor patched
            tm1.session.execute(Tm1Request.patch(fr, changed))
        with pytest.raises(Tm1BadRequestError):  # nor deleted
            tm1.session.execute(Tm1Request.delete(fr))
        assert value(tm1, reader, attribute) == "Nord"
    finally:
        tm1.attributes.delete(attribute_set, attribute)


def test_needs_the_attribute_defined_and_refuses_writes_through_a_member(tm1: Tm1Client, d: str) -> None:
    caption = LocalizedAttributesPatch.builder().locale_id("fr").attributes(AttributesPatch.builder().caption("Nord").build()).build()
    with pytest.raises(Tm1BadRequestError):  # Caption isn't an attribute until it's defined
        tm1.localized_attributes.create(AttributeOwner.element(d, d, "North"), caption)
    with pytest.raises(Tm1BadRequestError):  # POST not supported on a member's
        tm1.localized_attributes.create(AttributeOwner.member(d, d, "North"), LocalizedAttributesPatch.builder().locale_id("fr").build())


# CubeAttributeTm1Test


@pytest.fixture
def cube(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A cube over two dimensions, with a public MDX view V and a public native view N (a
    view attribute needs a public native view)."""
    dims = [name + "-a", name + "-b"]
    for dim in dims:
        tm1.dimensions.create(
            DimensionPatch.builder()
            .name(dim)
            .hierarchies([HierarchyPatch.builder().name(dim).elements([ElementPatch.builder().name("X").type(ElementType.NUMERIC).build()]).build()])
            .build()
        )
        tm1.subsets.create(dim, dim, SubsetScope.PUBLIC, SubsetPatch.builder().name("All").expression(f"{{TM1SUBSETALL([{dim}].[{dim}])}}").build())
    try:
        dimensions = ODataPath.of("Dimensions")
        tm1.cubes.create(CubePatch.builder().name(name).bind_dimensions(dimensions.key(dims[0]), dimensions.key(dims[1])).build())
        mdx = f"SELECT {{[{dims[0]}].[{dims[0]}].Members}} ON 0 FROM [{name}]"
        tm1.views.create(name, ViewScope.PUBLIC, MDXViewPatch.builder().name("V").mdx(mdx).build())

        def axis(dim: str) -> ViewAxisSelectionPatch:
            return ViewAxisSelectionPatch.builder().bind_subset(dimensions.key(dim).nav("Hierarchies").key(dim).nav("Subsets").key("All")).build()

        tm1.views.create(name, ViewScope.PUBLIC, NativeViewPatch.builder().name("N").columns([axis(dims[0])]).rows([axis(dims[1])]).build())
        yield name
    finally:
        try:
            tm1.cubes.delete(name)
        finally:
            for dim in dims:
                tm1.dimensions.delete(dim)


@pytest.mark.parametrize("kind", ["cube", "view"])
def test_defines_and_translates_cube_and_view_attributes(tm1: Tm1Client, cube: str, kind: str) -> None:
    attribute_set = AttributeSet.cubes() if kind == "cube" else AttributeSet.views(cube)
    owner = AttributeOwner.cube(cube) if kind == "cube" else AttributeOwner.view(cube, ViewScope.PUBLIC, "V")
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        tm1.localized_attributes.create(owner, french(attribute, "Ventes"))
        assert value(tm1, owner, attribute) == "Ventes"
    finally:
        tm1.attributes.delete(attribute_set, attribute)


# ProcessAttributeTm1Test


@pytest.fixture
def process_and_chore(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str]]:
    process, chore = name, name + "-chore"
    tm1.processes.create(ProcessPatch.builder().name(process).prolog_procedure("nX = 1;\n").build())
    try:
        tm1.chores.create(
            ChorePatch.builder()
            .name(chore)
            .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=timezone.utc))
            .dst_sensitive(False)
            .active(False)
            .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
            .frequency(timedelta(days=1))
            .tasks([ChoreTaskPatch.builder().bind_process(ODataPath.of("Processes").key(process)).build()])
            .build()
        )
        try:
            yield process, chore
        finally:
            tm1.chores.delete(chore)
    finally:
        tm1.processes.delete(process)


@pytest.mark.parametrize("kind", ["process", "chore"])
def test_defines_and_translates_process_and_chore_attributes(tm1: Tm1Client, process_and_chore: tuple[str, str], kind: str) -> None:
    process, chore = process_and_chore
    attribute_set = AttributeSet.processes() if kind == "process" else AttributeSet.chores()
    owner = AttributeOwner.process(process) if kind == "process" else AttributeOwner.chore(chore)
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        tm1.localized_attributes.create(owner, french(attribute, "Charger"))
        assert value(tm1, owner, attribute) == "Charger"
    finally:
        tm1.attributes.delete(attribute_set, attribute)
