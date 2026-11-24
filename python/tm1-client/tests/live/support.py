# SPDX-License-Identifier: Apache-2.0

"""What the live tests build their models from: dimensions, elements, edges, and the like."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1Request
from skipcheck_tm1._sync.services.attribute import AttributeOwner
from skipcheck_tm1.model import (
    AttributeDefinitionCreate,
    AttributesPatch,
    AttributeType,
    DimensionCreate,
    EdgePatch,
    ElementCreate,
    ElementType,
    HierarchyCreate,
    LocalizedAttributesPatch,
    ProcessCreate,
    ProcessParameterPatch,
    ProcessVariableType,
    SubsetCreate,
)

# Dimensions made through the raw session, for tests that aren't about the dimension services


def dim(name: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(name)


def element(name: str, type: ElementType = ElementType.NUMERIC) -> Any:
    return ElementCreate.builder().name(name).type(type).build()


def edge(parent: str, component: str) -> Any:
    return EdgePatch.builder().parent_name(parent).component_name(component).weight(1.0).build()


def dimension(tm1: Tm1Client, name: str, elements: list[Any], edges: list[Any] | None = None, subset: bool = False) -> str:
    hierarchy = HierarchyCreate.builder().name(name).elements(elements)
    if edges:
        hierarchy = hierarchy.edges(edges)
    patch = DimensionCreate.builder().name(name).hierarchies([hierarchy.build()]).build()
    tm1.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), patch, None))
    if subset:
        path = dim(name).nav("Hierarchies").key(name).nav("Subsets")
        all_ = SubsetCreate.builder().name("All").expression(f"{{TM1SUBSETALL([{name}].[{name}])}}").build()
        tm1.session.execute(Tm1Request.post(path, all_, None))
    return name


def drop(tm1: Tm1Client, *dimensions: str) -> None:
    for d in dimensions:
        tm1.session.execute(Tm1Request.delete(dim(d)))


def names(dimensions: list[Any]) -> list[str]:
    return [d.name for d in dimensions]


def first_value(client: Tm1Client, mdx: str) -> Any:
    """The first cell's value of a query, read through ``stream``."""

    def first(cellset: Any, cells: Any) -> Any:
        for cell in cells:
            return cell.value
        return None

    return client.cellsets.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 10, first)


# Dimensions made through DimensionService


def numeric(name: str) -> ElementCreate:
    return ElementCreate.builder().name(name).type(ElementType.NUMERIC).build()


def consolidated(name: str) -> ElementCreate:
    return ElementCreate.builder().name(name).type(ElementType.CONSOLIDATED).build()


def string(name: str) -> ElementCreate:
    return ElementCreate.builder().name(name).type(ElementType.STRING).build()


def dimension_of(name: str, elements: list[ElementCreate], edges: list[EdgePatch] | None = None) -> DimensionCreate:
    hierarchy = HierarchyCreate.builder().name(name).elements(elements)
    if edges:
        hierarchy = hierarchy.edges(edges)
    return DimensionCreate.builder().name(name).hierarchies([hierarchy.build()]).build()


@contextmanager
def made(tm1: Tm1Client, name: str, patch: DimensionCreate) -> Iterator[str]:
    tm1.dimensions.create(patch)
    try:
        yield name
    finally:
        tm1.dimensions.delete(name)


def components(tm1: Tm1Client, d: str, parent: str) -> list[str]:
    return names(tm1.elements.components(d, d, parent))


# Attributes and their translations


def label() -> str:
    return "Label " + uuid.uuid4().hex[:4]


def string_attribute(name: str) -> AttributeDefinitionCreate:
    return AttributeDefinitionCreate.builder().name(name).type(AttributeType.STRING).build()


def french(attribute: str, value: str) -> LocalizedAttributesPatch:
    return LocalizedAttributesPatch.builder().locale_id("fr").attributes(AttributesPatch.builder().property(attribute, value).build()).build()


def french_value(tm1: Tm1Client, owner: AttributeOwner, attribute: str) -> object:
    return (tm1.localized_attributes.get(owner, "fr").attributes.model_extra or {}).get(attribute)


# A process with a text and a numeric parameter


def process_definition(name: str, prolog: str) -> Any:
    return (
        ProcessCreate.builder()
        .name(name)
        .parameters(
            [
                ProcessParameterPatch.builder().name("pName").value("").type(ProcessVariableType.STRING).build(),
                ProcessParameterPatch.builder().name("pCount").value(0).type(ProcessVariableType.NUMERIC).build(),
            ]
        )
        .prolog_procedure(prolog)
        .build()
    )
