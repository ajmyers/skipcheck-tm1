# SPDX-License-Identifier: Apache-2.0

"""Attributes and translations of cubes and views against a real TM1: a port of the Java client's
CubeAttributeTm1Test."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import ODataPath, Tm1Client
from skipcheck_tm1._sync.services.attribute import AttributeOwner, AttributeSet
from skipcheck_tm1._sync.services.cube import ViewScope
from skipcheck_tm1._sync.services.dimension import SubsetScope
from skipcheck_tm1.model import (
    CubeCreate,
    DimensionCreate,
    ElementCreate,
    ElementType,
    HierarchyCreate,
    MDXViewCreate,
    NativeViewCreate,
    SubsetCreate,
    ViewAxisSelectionPatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    french,
    french_value,
    label,
    string_attribute,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def cube(tm1: Tm1Client, name: str) -> Iterator[str]:
    """A cube over two dimensions, with a public MDX view V and a public native view N (a
    view attribute needs a public native view)."""
    dims = [name + "-a", name + "-b"]
    for dim in dims:
        tm1.dimensions.create(
            DimensionCreate.builder()
            .name(dim)
            .hierarchies([HierarchyCreate.builder().name(dim).elements([ElementCreate.builder().name("X").type(ElementType.NUMERIC).build()]).build()])
            .build()
        )
        tm1.subsets.create(dim, dim, SubsetScope.PUBLIC, SubsetCreate.builder().name("All").expression(f"{{TM1SUBSETALL([{dim}].[{dim}])}}").build())
    try:
        dimensions = ODataPath.of("Dimensions")
        tm1.cubes.create(CubeCreate.builder().name(name).bind_dimensions(dimensions.key(dims[0]), dimensions.key(dims[1])).build())
        mdx = f"SELECT {{[{dims[0]}].[{dims[0]}].Members}} ON 0 FROM [{name}]"
        tm1.views.create(name, ViewScope.PUBLIC, MDXViewCreate.builder().name("V").mdx(mdx).build())

        def axis(dim: str) -> ViewAxisSelectionPatch:
            return ViewAxisSelectionPatch.builder().bind_subset(dimensions.key(dim).nav("Hierarchies").key(dim).nav("Subsets").key("All")).build()

        tm1.views.create(name, ViewScope.PUBLIC, NativeViewCreate.builder().name("N").columns([axis(dims[0])]).rows([axis(dims[1])]).build())
        yield name
    finally:
        try:
            tm1.cubes.delete(name)
        finally:
            for dim in dims:
                tm1.dimensions.delete(dim)


@pytest.mark.parametrize("kind", ["cube", "view"])
def test_defines_and_translates_attributes(tm1: Tm1Client, cube: str, kind: str) -> None:
    attribute_set = AttributeSet.cubes() if kind == "cube" else AttributeSet.views(cube)
    owner = AttributeOwner.cube(cube) if kind == "cube" else AttributeOwner.view(cube, ViewScope.PUBLIC, "V")
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        tm1.localized_attributes.create(owner, french(attribute, "Ventes"))
        assert french_value(tm1, owner, attribute) == "Ventes"
    finally:
        tm1.attributes.delete(attribute_set, attribute)
