# SPDX-License-Identifier: Apache-2.0

"""The dimension services (dimensions, hierarchies, elements, edges, levels, members, subsets),
in both flavors, against a fake TM1: each method sends what the Java client's does, and reads
TM1's answer the same way."""

from __future__ import annotations

import json
from typing import Any

import httpx2
import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1UnsupportedOperationError
from skipcheck_tm1.aio.services.dimension import SubsetScope
from skipcheck_tm1.model import (
    Dimension,
    DimensionCreate,
    DimensionPatch,
    EdgeCreate,
    EdgePatch,
    Element,
    ElementCreate,
    ElementPatch,
    ElementType,
    Hierarchy,
    HierarchyCreate,
    HierarchyPatch,
    Member,
    SubsetCreate,
    SubsetPatch,
)

from ..conftest import Api, FakeTm1, target

H = "Dimensions('Region')/Hierarchies('Region')"
E = H + "/Elements"


def body(request: httpx2.Request) -> Any:
    return json.loads(request.content) if request.content else None


def sent(fake: FakeTm1) -> tuple[str, Any]:
    return target(fake.last), body(fake.last)


# DimensionService


def test_dimensions_list_get_create_update_delete(api: Api, fake: FakeTm1) -> None:
    dims = api.client.dimensions
    fake.respond = lambda r: {"value": [{"Name": "Region"}]}
    assert [d.name for d in api.run(dims.model_dimensions(ODataQuery.none().select(Dimension.NAME).order_by(Dimension.NAME)))] == ["Region"]
    assert target(fake.last) == "GET ModelDimensions()?$select=Name&$orderby=Name"
    api.run(dims.control_dimensions())
    assert target(fake.last) == "GET ControlDimensions()"
    api.run(dims.list(top=1))
    assert target(fake.last) == "GET Dimensions?$top=1"

    fake.respond = lambda r: {"Name": "Region", "Hierarchies": [{"Name": "Region"}]}
    region = api.run(dims.get("Region", ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.select(Hierarchy.NAME))))
    assert [h.name for h in region.hierarchies] == ["Region"]
    assert target(fake.last) == "GET Dimensions('Region')?$expand=Hierarchies($select%3DName)"

    total = ElementCreate.builder().name("Total").type(ElementType.CONSOLIDATED).build()
    north = ElementCreate.builder().name("North").type(ElementType.NUMERIC).build()
    edge = EdgeCreate.builder().parent_name("Total").component_name("North").weight(1.0).build()
    api.run(
        dims.create(
            DimensionCreate.builder()
            .name("Region")
            .hierarchies([HierarchyCreate.builder().name("Region").elements([total, north]).edges([edge]).build()])
            .build()
        )
    )
    assert sent(fake) == (
        "POST Dimensions",
        {
            "Name": "Region",
            "Hierarchies": [
                {
                    "Name": "Region",
                    "Elements": [{"Name": "Total", "Type": "Consolidated"}, {"Name": "North", "Type": "Numeric"}],
                    "Edges": [{"ParentName": "Total", "ComponentName": "North", "Weight": 1.0}],
                }
            ],
        },
    )

    fake.respond = lambda r: None
    api.run(dims.update("Region", DimensionPatch.builder().default_hierarchy(HierarchyCreate.builder().name("Alt").build()).build()))
    assert sent(fake) == ("PATCH Dimensions('Region')", {"DefaultHierarchy": {"Name": "Alt"}})
    assert "if-match" not in fake.last.headers
    api.run(dims.update("Region", {"Name": "Region"}, 'W/"1"'))
    assert fake.last.headers["if-match"] == 'W/"1"'
    api.run(dims.delete("Region"))
    assert target(fake.last) == "DELETE Dimensions('Region')"


def test_dimension_operations(api: Api, fake: FakeTm1) -> None:
    dims = api.client.dimensions
    fake.respond = lambda r: {"Name": "Copy"}
    assert api.run(dims.save_as("Region", "Copy")).name == "Copy"
    assert sent(fake) == ("POST Dimensions('Region')/tm1.SaveAs", {"Name": "Copy"})
    api.run(dims.save_as("Region", "Copy", True, False))
    assert body(fake.last) == {"Name": "Copy", "Overwrite": True, "KeepExistingAttributes": False}

    fake.respond = lambda r: {"Name": "Leaves"}
    assert api.run(dims.add_all_leaves_hierarchy("Region")).name == "Leaves"
    assert sent(fake) == ("POST Dimensions('Region')/tm1.AddAllLeavesHierarchy", {})
    api.run(dims.add_all_leaves_hierarchy("Region", "All", True))
    assert body(fake.last) == {"AllLeavesHierarchyName": "All", "Visible": True}

    fake.respond = lambda r: None
    for method, action in (("lock", "Lock"), ("unlock", "Unlock"), ("reserve", "Reserve"), ("release", "Release")):
        api.run(getattr(dims, method)("Region"))
        assert sent(fake) == (f"POST Dimensions('Region')/tm1.{action}", {})


# HierarchyService


def test_hierarchies(api: Api, fake: FakeTm1) -> None:
    hs = api.client.hierarchies
    fake.respond = lambda r: {"value": [{"Name": "Region"}]}
    api.run(hs.list("Region"))
    assert target(fake.last) == "GET Dimensions('Region')/Hierarchies"
    fake.respond = lambda r: {"Name": "By Manager"}
    api.run(hs.get("Region", "By Manager", select="Name"))
    assert target(fake.last) == "GET Dimensions('Region')/Hierarchies('By%20Manager')?$select=Name"
    api.run(hs.create("Region", HierarchyCreate.builder().name("By Manager").build()))
    assert sent(fake) == ("POST Dimensions('Region')/Hierarchies", {"Name": "By Manager"})
    api.run(hs.default_hierarchy("Region"))
    assert target(fake.last) == "GET Dimensions('Region')/DefaultHierarchy"
    api.run(hs.dimension("Region", "Region"))
    assert target(fake.last) == f"GET {H}/Dimension"
    api.run(hs.default_member("Region", "Region"))
    assert target(fake.last) == f"GET {H}/DefaultMember"

    fake.respond = lambda r: None
    api.run(hs.update("Region", "Region", HierarchyPatch.builder().elements_sort_sense("Descending").build(), 'W/"2"'))
    assert sent(fake) == (f"PATCH {H}", {"ElementsSortSense": "Descending"})
    assert fake.last.headers["if-match"] == 'W/"2"'
    api.run(hs.delete("Region", "Region"))
    assert target(fake.last) == f"DELETE {H}"


def test_a_hierarchy_update_refuses_structure(api: Api, fake: FakeTm1) -> None:
    hs = api.client.hierarchies
    elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements")
    for patch in (
        HierarchyCreate.builder().elements([ElementCreate.builder().name("a").build()]).build(),
        HierarchyCreate.builder().edges([]).build(),
        {"Elements@odata.bind": [str(elements.key("a"))]},
        {"Edges@odata.bind": []},
    ):
        with pytest.raises(ValueError, match="replace_structure"):
            api.run(hs.update("Region", "Region", patch))
    assert fake.requests == []


def test_replace_structure(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    all_ = ElementCreate.builder().name("All Versions").type(ElementType.CONSOLIDATED).build()
    actual = ElementCreate.builder().name("Actual").type(ElementType.NUMERIC).build()
    edge = EdgeCreate.builder().parent_name("All Versions").component_name("Actual").weight(1.0).build()
    api.run(api.client.hierarchies.replace_structure("Version", "Version", [all_, actual], [edge]))
    assert sent(fake) == (
        "PATCH Dimensions('Version')/Hierarchies('Version')",
        {
            "Elements": [{"Name": "All Versions", "Type": "Consolidated"}, {"Name": "Actual", "Type": "Numeric"}],
            "Edges": [{"ParentName": "All Versions", "ComponentName": "Actual", "Weight": 1.0}],
        },
    )


def test_all_member_is_none_with_several_roots(api: Api, fake: FakeTm1) -> None:
    # TM1 (11.8) answers 204 when the hierarchy has several roots
    fake.respond = lambda r: None
    assert api.run(api.client.hierarchies.all_member("Region", "Region")) is None
    fake.respond = lambda r: {"Name": "Total"}
    member = api.run(api.client.hierarchies.all_member("Region", "Region"))
    assert isinstance(member, Member) and member.name == "Total"
    assert target(fake.last) == f"GET {H}/AllMember"


def test_hierarchy_operations(api: Api, fake: FakeTm1) -> None:
    hs = api.client.hierarchies
    fake.respond = lambda r: {"value": [{"Name": "Total"}]}
    roots = api.run(hs.root_members("Region", "Region", ODataQuery.none().select(Member.NAME)))
    assert [m.name for m in roots] == ["Total"]
    assert target(fake.last) == f"GET {H}/tm1.RootMembers()?$select=Name"
    fake.respond = lambda r: {"Name": "Copy"}
    api.run(hs.save_as("Region", "Region", "Copy"))
    assert sent(fake) == (f"POST {H}/tm1.SaveAs", {"Name": "Copy"})
    api.run(hs.save_as("Region", "Region", "Copy", True, True))
    assert body(fake.last) == {"Name": "Copy", "Overwrite": True, "KeepExistingAttributes": True}
    fake.respond = lambda r: None
    api.run(hs.set_element("Region", "Region", "West"))
    assert sent(fake) == (f"POST {H}/tm1.SetElement", {"Element@odata.bind": f"{E}('West')"})
    api.run(hs.set_element("Region", "Region", "West", "Total"))
    assert body(fake.last) == {"Element@odata.bind": f"{E}('West')", "Before@odata.bind": f"{E}('Total')"}


# ElementService


def test_elements(api: Api, fake: FakeTm1) -> None:
    es = api.client.elements
    fake.respond = lambda r: {"value": [{"Name": "Total", "Type": "Consolidated"}]}
    query = ODataQuery.none().select(Element.NAME).filter(Element.TYPE.eq(ElementType.CONSOLIDATED)).order_by(Element.NAME)
    found = api.run(es.list("Region", "Region", query))
    assert found[0].type is ElementType.CONSOLIDATED
    assert target(fake.last) == f"GET {E}?$select=Name&$filter=(Type%20eq%20ibm.tm1.api.v1.ElementType'Consolidated')&$orderby=Name"
    for method, nav in (("parents", "Parents"), ("components", "Components"), ("edges", "Edges")):
        api.run(getattr(es, method)("Region", "Region", "Total", select="Name"))
        assert target(fake.last) == f"GET {E}('Total')/{nav}?$select=Name"
    fake.respond = lambda r: {"Name": "West"}
    api.run(es.get("Region", "Region", "West"))
    assert target(fake.last) == f"GET {E}('West')"
    api.run(es.hierarchy("Region", "Region", "West"))
    assert target(fake.last) == f"GET {E}('West')/Hierarchy"
    api.run(es.create("Region", "Region", ElementCreate.builder().name("West").type(ElementType.NUMERIC).build()))
    assert sent(fake) == (f"POST {E}", {"Name": "West", "Type": "Numeric"})
    fake.respond = lambda r: None
    api.run(es.update("Region", "Region", "West", ElementPatch.builder().type(ElementType.STRING).build(), 'W/"3"'))
    assert sent(fake) == (f"PATCH {E}('West')", {"Type": "String"})
    assert fake.last.headers["if-match"] == 'W/"3"'
    api.run(es.delete("Region", "Region", "West"))
    assert target(fake.last) == f"DELETE {E}('West')"


def test_an_element_update_refuses_what_tm1_ignores_or_rejects(api: Api, fake: FakeTm1) -> None:
    es = api.client.elements
    # An element patch offers neither: TM1 ignores a rename and rejects Attributes
    assert not hasattr(ElementPatch.Builder, "name") and not hasattr(ElementPatch.Builder, "attributes")
    with pytest.raises(ValueError, match="can't be renamed"):
        api.run(es.update("Region", "Region", "West", {"Name": "East"}))
    with pytest.raises(ValueError, match="ElementAttributes"):
        api.run(es.update("Region", "Region", "West", {"Attributes": {"Caption": "x"}}))
    assert fake.requests == []


def test_element_operations(api: Api, fake: FakeTm1) -> None:
    es = api.client.elements
    fake.respond = lambda r: None
    api.run(es.set_component("Region", "Region", "Total", "West"))
    assert sent(fake) == (f"POST {E}('Total')/tm1.SetComponent", {"Element@odata.bind": f"{E}('West')"})
    api.run(es.set_component("Region", "Region", "Total", "West", weight=-1.0))
    assert body(fake.last) == {"Element@odata.bind": f"{E}('West')", "Weight": -1.0}
    api.run(es.set_component("Region", "Region", "Total", "West", before="North", weight=2))
    assert body(fake.last) == {"Element@odata.bind": f"{E}('West')", "Before@odata.bind": f"{E}('North')", "Weight": 2.0}
    for method, action in (("lock", "Lock"), ("unlock", "Unlock"), ("reserve", "Reserve"), ("release", "Release")):
        api.run(getattr(es, method)("Region", "Region", "West"))
        assert sent(fake) == (f"POST {E}('West')/tm1.{action}", {})


# EdgeService


def test_edges(api: Api, fake: FakeTm1) -> None:
    es = api.client.edges
    key = f"{H}/Edges(ParentName='Total',ComponentName='North')"
    fake.respond = lambda r: {"value": [{"ParentName": "Total", "ComponentName": "North", "Weight": 1}]}
    edges = api.run(es.list("Region", "Region", ODataQuery.none()))
    assert (edges[0].parent_name, edges[0].component_name, edges[0].weight) == ("Total", "North", 1.0)
    assert target(fake.last) == f"GET {H}/Edges"
    fake.respond = lambda r: {"ParentName": "Total"}
    api.run(es.get("Region", "Region", "Total", "North"))
    assert target(fake.last) == f"GET {key}"
    for method, nav in (("parent", "Parent"), ("component", "Component"), ("hierarchy", "Hierarchy")):
        api.run(getattr(es, method)("Region", "Region", "Total", "North"))
        assert target(fake.last) == f"GET {key}/{nav}"
    api.run(es.create("Region", "Region", EdgeCreate.builder().parent_name("Total Sales").component_name("North").weight(1.0).build()))
    assert sent(fake) == (f"POST {H}/Edges", {"ParentName": "Total Sales", "ComponentName": "North", "Weight": 1.0})
    fake.respond = lambda r: None
    api.run(es.update("Region", "Region", "Total", "North", EdgePatch.builder().weight(-1.0).build()))
    assert sent(fake) == (f"PATCH {key}", {"Weight": -1.0})
    api.run(es.delete("Region", "Region", "Total", "North"))
    assert target(fake.last) == f"DELETE {key}"


# LevelService and MemberService


def test_levels(api: Api, fake: FakeTm1) -> None:
    ls = api.client.levels
    fake.respond = lambda r: {"value": [{"Number": 1, "Cardinality": 2}]}
    levels = api.run(ls.list("Region", "Region", ODataQuery.none()))
    assert (levels[0].number, levels[0].cardinality) == (1, 2)
    assert target(fake.last) == f"GET {H}/Levels"
    api.run(ls.members("Region", "Region", 1, ODataQuery.none().select(Member.NAME)))
    assert target(fake.last) == f"GET {H}/Levels(1)/Members?$select=Name"
    fake.respond = lambda r: {"Number": 0}
    api.run(ls.get("Region", "Region", 0))
    assert target(fake.last) == f"GET {H}/Levels(0)"
    api.run(ls.hierarchy("Region", "Region", 0))
    assert target(fake.last) == f"GET {H}/Levels(0)/Hierarchy"


def test_members(api: Api, fake: FakeTm1) -> None:
    ms = api.client.members
    m = f"{H}/Members('North')"
    fake.respond = lambda r: {"value": [{"Name": "North"}]}
    api.run(ms.list("Region", "Region"))
    assert target(fake.last) == f"GET {H}/Members"
    api.run(ms.children("Region", "Region", "North", ODataQuery.none().select(Member.NAME)))
    assert target(fake.last) == f"GET {m}/Children?$select=Name"
    fake.respond = lambda r: {"Name": "North", "UniqueName": "[Region].[Region].[North]"}
    assert api.run(ms.get("Region", "Region", "North", ODataQuery.none().select(Member.UNIQUE_NAME))).unique_name == "[Region].[Region].[North]"
    assert target(fake.last) == f"GET {m}?$select=UniqueName"
    for method, nav in (("element", "Element"), ("level", "Level"), ("hierarchy", "Hierarchy"), ("parent", "Parent")):
        api.run(getattr(ms, method)("Region", "Region", "North"))
        assert target(fake.last) == f"GET {m}/{nav}"
    # A root has no parent
    fake.respond = lambda r: None
    assert api.run(ms.parent("Region", "Region", "Total")) is None


# SubsetService


def test_subset_scopes() -> None:
    assert [s.navigation() for s in SubsetScope] == ["Subsets", "PrivateSubsets", "SessionSubsets"]


@pytest.mark.parametrize("scope", list(SubsetScope))
def test_subsets_by_scope(api: Api, fake: FakeTm1, scope: SubsetScope) -> None:
    ss = api.client.subsets
    s = f"{H}/{scope.navigation()}('Key%20Regions')"
    fake.respond = lambda r: {"value": [{"Name": "Key Regions"}]}
    api.run(ss.list("Region", "Region", scope))
    assert target(fake.last) == f"GET {H}/{scope.navigation()}"
    api.run(ss.elements("Region", "Region", scope, "Key Regions", ODataQuery.none().select(Element.NAME)))
    assert target(fake.last) == f"GET {s}/Elements?$select=Name"
    fake.respond = lambda r: {"Name": "Key Regions"}
    api.run(ss.get("Region", "Region", scope, "Key Regions"))
    assert target(fake.last) == f"GET {s}"
    api.run(ss.hierarchy("Region", "Region", scope, "Key Regions"))
    assert target(fake.last) == f"GET {s}/Hierarchy"
    elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements")
    api.run(
        ss.create("Region", "Region", scope, SubsetCreate.builder().name("Key Regions").bind_elements(elements.key("North"), elements.key("South")).build())
    )
    assert sent(fake) == (f"POST {H}/{scope.navigation()}", {"Name": "Key Regions", "Elements@odata.bind": [f"{E}('North')", f"{E}('South')"]})
    fake.respond = lambda r: None
    api.run(ss.update("Region", "Region", scope, "Key Regions", SubsetPatch.builder().expression("{[Region].[North]}").build(), 'W/"4"'))
    assert sent(fake) == (f"PATCH {s}", {"Expression": "{[Region].[North]}"})
    assert fake.last.headers["if-match"] == 'W/"4"'
    api.run(ss.clear_elements("Region", "Region", scope, "Key Regions"))
    assert target(fake.last) == f"DELETE {s}/Elements/$ref"
    api.run(ss.add_elements("Region", "Region", scope, "Key Regions", ["East", "West"]))
    assert sent(fake) == (f"PATCH {s}", {"Elements@odata.bind": [f"{E}('East')", f"{E}('West')"]})
    api.run(ss.delete("Region", "Region", scope, "Key Regions"))
    assert target(fake.last) == f"DELETE {s}"


def test_a_subset_update_refuses_elements(api: Api, fake: FakeTm1) -> None:
    # SubsetPatch offers no Elements: TM1 refuses them in a PATCH, and appends a binding
    assert not hasattr(SubsetPatch.Builder, "elements") and not hasattr(SubsetPatch.Builder, "bind_elements")
    for patch in ({"Elements": []}, {"Elements@odata.bind": []}):
        with pytest.raises(ValueError, match="add_elements"):
            api.run(api.client.subsets.update("Region", "Region", SubsetScope.PUBLIC, "s", patch))
    assert fake.requests == []


def test_subset_operations(api: Api, fake: FakeTm1) -> None:
    ss = api.client.subsets
    s = f"{H}/Subsets('s')"
    fake.respond = lambda r: {"Name": "copy"}
    api.run(ss.save_as("Region", "Region", SubsetScope.PUBLIC, "s", "copy"))
    assert sent(fake) == (f"POST {s}/tm1.SaveAs", {"Name": "copy"})
    api.run(ss.save_as("Region", "Region", SubsetScope.PUBLIC, "s", "copy", True, True))
    assert body(fake.last) == {"Name": "copy", "MakePrivate": True, "MakeStatic": True}
    fake.respond = lambda r: None
    api.run(ss.set_element("Region", "Region", SubsetScope.PUBLIC, "s", "West"))
    assert sent(fake) == (f"POST {s}/tm1.SetElement", {"Element@odata.bind": f"{E}('West')"})
    api.run(ss.set_element_before("Region", "Region", SubsetScope.PUBLIC, "s", "West", "North"))
    assert body(fake.last) == {"Element@odata.bind": f"{E}('West')", "Before@odata.bind": f"{E}('North')"}
    api.run(ss.set_element_after("Region", "Region", SubsetScope.PUBLIC, "s", "West", "North"))
    assert body(fake.last) == {"Element@odata.bind": f"{E}('West')", "After@odata.bind": f"{E}('North')"}

    # TM1 names a session subset itself, ignoring the name sent
    fake.respond = lambda r: {"Name": "yExesF4AAIABAAAQ"}
    made = api.run(
        ss.create_session_subset("Region", "Region", SubsetCreate.builder().name("Regions").expression("{[Region].[Region].[Total].Children}").build())
    )
    assert made.name == "yExesF4AAIABAAAQ"
    assert sent(fake) == (f"POST {H}/tm1.CreateSessionSubset", {"Subset": {"Name": "Regions", "Expression": "{[Region].[Region].[Total].Children}"}})
    api.run(ss.create_session_subset_from("Region", "Region", SubsetScope.PRIVATE, "Mine"))
    assert body(fake.last) == {"Subset@odata.bind": f"{H}/PrivateSubsets('Mine')"}


def test_an_operation_the_server_lacks_is_refused(fake: FakeTm1) -> None:
    from skipcheck_tm1 import Tm1Client, Tm1Session, auth

    fake.metadata = fake.metadata.replace(b'Name="Reserve"', b'Name="NoReserve"')
    client = Tm1Client(Tm1Session("http://tm1:8010/api/v1/", auth.basic("a"), client=httpx2.Client(transport=httpx2.MockTransport(fake.handler))))
    with pytest.raises(Tm1UnsupportedOperationError, match="Element.Reserve"):
        client.elements.reserve("Region", "Region", "West")
    assert fake.requests == []
