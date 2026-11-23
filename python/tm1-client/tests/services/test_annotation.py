# SPDX-License-Identifier: Apache-2.0

"""AnnotationService, in both flavors, against a fake TM1."""

from __future__ import annotations

import json

import httpx2

from skipcheck_tm1.model import AnnotationPatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx2.Request) -> object:
    return json.loads(request.content) if request.content else None


def test_annotates_a_cell_as_paw_does(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"ID": "C}Sales~B.B~1", "Text": "x", "commentValue": "x"}
    cell = {"Version": "Forecast", "Region": "North", "Measure": "Amount"}
    made = api.run(api.client.annotations.create("Sales", cell, "Includes the March promotion"))
    assert made.id == "C}Sales~B.B~1"
    # An open type: undeclared properties are kept
    assert (made.model_extra or {})["commentValue"] == "x"
    assert target(fake.last) == "POST Annotations"
    assert body(fake.last) == {
        "Text": "Includes the March promotion",
        # The key is encoded in the path, as the Java client does
        "ApplicationContext": [{"Facet@odata.bind": "ApplicationContextFacets('%7DCubes')", "Value": "Sales"}],
        "DimensionalContext@odata.bind": [
            "Dimensions('Version')/Hierarchies('Version')/Members('Forecast')",
            "Dimensions('Region')/Hierarchies('Region')/Members('North')",
            "Dimensions('Measure')/Hierarchies('Measure')/Members('Amount')",
        ],
        "objectName": "Sales",
        "commentValue": "Includes the March promotion",
        "commentType": "ANNOTATION",
        "commentLocation": "Forecast,North,Amount",
    }


def test_crud_by_id(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"ID": "a"}
    api.run(api.client.annotations.create(AnnotationPatch.builder().text("t").build()))
    assert body(fake.last) == {"Text": "t"}
    api.run(api.client.annotations.create(annotation=AnnotationPatch.builder().text("u").build()))
    assert body(fake.last) == {"Text": "u"}
    api.run(api.client.annotations.get("C}Sales~1", select="Text"))
    assert target(fake.last) == "GET Annotations('C%7DSales~1')?$select=Text"
    fake.respond = lambda r: None
    text = "Includes the March and April promotions"
    api.run(api.client.annotations.update("a", AnnotationPatch.builder().text(text).property("commentValue", text).build()))
    assert (target(fake.last), body(fake.last)) == ("PATCH Annotations('a')", {"Text": text, "commentValue": text})
    api.run(api.client.annotations.delete("a"))
    assert target(fake.last) == "DELETE Annotations('a')"
    fake.respond = lambda r: {"value": []}
    assert api.run(api.client.annotations.list()) == []
    assert target(fake.last) == "GET Annotations"


def test_navigation(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": []}
    for method, nav in (("application_context", "ApplicationContext"), ("dimensional_context", "DimensionalContext"), ("attachments", "Attachments")):
        api.run(getattr(api.client.annotations, method)("a"))
        assert target(fake.last) == f"GET Annotations('a')/{nav}"
    api.run(api.client.annotations.cube_annotations("Sales"))
    assert target(fake.last) == "GET Cubes('Sales')/Annotations"
    api.run(api.client.annotations.cell_annotations("cs1", 7))
    assert target(fake.last) == "GET Cellsets('cs1')/Cells(7)/Annotations"
    api.run(api.client.annotations.facets(expand="Values"))
    assert target(fake.last) == "GET ApplicationContextFacets?$expand=Values"
    api.run(api.client.annotations.facet_values("}Cubes"))
    assert target(fake.last) == "GET ApplicationContextFacets('%7DCubes')/Values"
    fake.respond = lambda r: {"Name": "}Cubes"}
    api.run(api.client.annotations.facet("}Cubes", "Sales"))
    assert target(fake.last) == "GET ApplicationContextFacets('%7DCubes')/Values('Sales')/Facet"
    fake.respond = lambda r: None
    api.run(api.client.annotations.delete_annotation_artifacts())
    assert (target(fake.last), body(fake.last)) == ("POST DeleteAnnotationArtifacts", {})
