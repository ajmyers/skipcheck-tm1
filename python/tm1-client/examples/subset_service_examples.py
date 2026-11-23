"""Examples for SubsetService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client
from skipcheck_tm1.model import Element, Subset, SubsetPatch
from skipcheck_tm1.services import SubsetScope


def create_static(tm1: Tm1Client) -> Subset:
    # @start region = create-static
    # A static subset lists its elements, bound by their paths
    elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements")
    key_regions = SubsetPatch.builder().name("Key Regions").bind_elements(elements.key("North"), elements.key("South")).build()
    return tm1.subsets.create("Region", "Region", SubsetScope.PUBLIC, key_regions)
    # @end


def create_dynamic(tm1: Tm1Client) -> Subset:
    # @start region = create-dynamic
    # A dynamic subset has an MDX expression, evaluated each time it's read
    leaves = SubsetPatch.builder().name("All Leaves").expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}").build()
    return tm1.subsets.create("Region", "Region", SubsetScope.PUBLIC, leaves)
    # @end


def replace_elements(tm1: Tm1Client) -> None:
    # @start region = replace
    # A PATCH appends elements, so empty the list, then add the new ones in order
    tm1.subsets.clear_elements("Region", "Region", SubsetScope.PUBLIC, "Key Regions")
    tm1.subsets.add_elements("Region", "Region", SubsetScope.PUBLIC, "Key Regions", ["East", "West"])
    # @end


def session(tm1: Tm1Client) -> list[str | None]:
    # @start region = session
    # TM1 names a session subset itself, ignoring the name sent: read it by the name
    # it gives back
    regions = SubsetPatch.builder().name("Regions").expression("{[Region].[Region].[Total].Children}").build()
    subset = tm1.subsets.create_session_subset("Region", "Region", regions)
    assert subset.name is not None
    elements = tm1.subsets.elements("Region", "Region", SubsetScope.SESSION, subset.name, ODataQuery.none().select(Element.NAME))
    return [element.name for element in elements]
    # @end
