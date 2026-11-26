# SPDX-License-Identifier: Apache-2.0

"""What the live tests build their models from: dimensions, elements, edges, and the like."""

from __future__ import annotations

import re
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Capabilities, Tm1Client, Tm1Request, Tm1Version
from skipcheck_tm1._sync.services.attribute import AttributeOwner
from skipcheck_tm1.model import (
    AttributeDefinitionCreate,
    AttributesPatch,
    AttributeType,
    DimensionCreate,
    EdgeCreate,
    ElementCreate,
    ElementType,
    HierarchyCreate,
    LocalizedAttributesCreate,
    ProcessCreate,
    ProcessParameterPatch,
    SubsetCreate,
)

from ..conftest import REPO

# ---- what the server has: as Java's ServerAssumptions, so a test of what an older server doesn't
# define is skipped there, not failed. The server's own $metadata decides, as the client decides.

STALE_FIRST_PULL_FROM = Tm1Version.parse("11.8.03500")
"""From this TM1 the first Git pull after the remote moves plans the commit TM1 had, and the next the
new one; before, the first plans the new one. Tried at 11.8.032 (the new) and 11.8.035 (the stale): a
build between falls on the older side unverified, so a failure there is this bound to move."""

_SCHEMA_VERSION = re.compile(r'Term="Core\.SchemaVersion">\s*<String>([^<]+)</String>')


def _describe(capabilities: Tm1Capabilities) -> str:
    return f"the server (schema {capabilities.schema_version}, product version {capabilities.product_version})"


def assume_supports(tm1: Tm1Client, operation: Any) -> None:
    """Skips the rest of the test unless the server defines the operation."""
    capabilities = tm1.session.capabilities()
    if not capabilities.supports(operation.capability()):
        pytest.skip(f"{_describe(capabilities)} doesn't define {operation.endpoint()}")


def assume_supports_parameter(tm1: Tm1Client, operation: Any, parameter: str) -> None:
    """Skips the rest of the test unless the server's operation has the parameter."""
    capabilities = tm1.session.capabilities()
    if not capabilities.supports_parameter(operation.capability(), parameter):
        pytest.skip(f"{_describe(capabilities)} has no parameter {parameter} on {operation.endpoint()}")


def product_version(tm1: Tm1Client) -> Tm1Version:
    """The server's product version."""
    version = tm1.session.capabilities().product_version
    assert version is not None
    return version


def is_at_least(tm1: Tm1Client, version: Tm1Version) -> bool:
    """Whether the server's product version is at least the version."""
    return product_version(tm1) >= version


def own_snapshot(capabilities: Tm1Capabilities) -> bytes:
    """The committed snapshot of the server's own schema version: its first document, whose elements a
    later document of the schema has too (Java's MetadataSnapshotTest holds them to it). Every
    supported version has one; a server of a schema without one (a newer TM1) skips the rest of the
    test, as Java's MetadataDriftTm1Test passes it by."""
    path = REPO / "java" / "tm1-client" / "src" / "model" / "metadata" / f"tm1-schema-{capabilities.schema_version}.xml"
    if not path.exists():
        pytest.skip(f"No metadata snapshot is committed for schema {capabilities.schema_version}")
    return path.read_bytes()


def schema_version_in(metadata: str) -> str:
    """The schema version a $metadata document's Core.SchemaVersion annotation gives, read from its
    text rather than by the client's parser."""
    found = _SCHEMA_VERSION.search(metadata)
    assert found, "$metadata has no Core.SchemaVersion annotation"
    return found.group(1).strip()


# Dimensions made through the raw session, for tests that aren't about the dimension services


def dim(name: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(name)


def element(name: str, type: ElementType = ElementType.NUMERIC) -> Any:
    return ElementCreate.builder().name(name).type(type).build()


def edge(parent: str, component: str) -> Any:
    return EdgeCreate.builder().parent_name(parent).component_name(component).weight(1.0).build()


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


def dimension_of(name: str, elements: list[ElementCreate], edges: list[EdgeCreate] | None = None) -> DimensionCreate:
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


def french(attribute: str, value: str) -> LocalizedAttributesCreate:
    return LocalizedAttributesCreate.builder().locale_id("fr").attributes(AttributesPatch.builder().property(attribute, value).build()).build()


def french_value(tm1: Tm1Client, owner: AttributeOwner, attribute: str) -> object:
    return (tm1.localized_attributes.get(owner, "fr").attributes.model_extra or {}).get(attribute)


# A process with a text and a numeric parameter


def process_definition(name: str, prolog: str) -> Any:
    return (
        ProcessCreate.builder()
        .name(name)
        .parameters(
            [
                ProcessParameterPatch.builder().name("pName").value("").build(),
                ProcessParameterPatch.builder().name("pCount").value(0).build(),
            ]
        )
        .prolog_procedure(prolog)
        .build()
    )
