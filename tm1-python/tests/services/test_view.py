"""ViewService and ViewScope, in both flavors, against a fake TM1."""

from __future__ import annotations

import json

import httpx
import pytest

from tm1_client import ODataPath, ODataQuery, Tm1PreconditionFailedError
from tm1_client.aio.services.cube import ViewScope
from tm1_client.model import MDXView, MDXViewPatch, NativeView, NativeViewPatch, ViewAxisSelectionPatch, ViewTitlePatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> object:
    return json.loads(request.content) if request.content else None


def test_scopes_name_the_cubes_collections() -> None:
    assert ViewScope.PUBLIC.navigation() == "Views"
    assert ViewScope.PRIVATE.navigation() == "PrivateViews"


def test_lists_and_gets_each_view_as_its_own_type(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {
        "value": [
            {"@odata.type": "#ibm.tm1.api.v1.MDXView", "Name": "m", "MDX": "SELECT"},
            {"@odata.type": "#ibm.tm1.api.v1.NativeView", "Name": "n"},
        ]
    }
    views = api.run(api.client.views.list("Sales", ViewScope.PUBLIC, expand="tm1.NativeView/Columns/Subset"))
    assert [type(v) for v in views] == [MDXView, NativeView]
    assert views[0].mdx == "SELECT"
    assert target(fake.last) == "GET Cubes('Sales')/Views?$expand=tm1.NativeView/Columns/Subset"
    api.run(api.client.views.list("Sales", ViewScope.PRIVATE))
    assert target(fake.last) == "GET Cubes('Sales')/PrivateViews"

    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.MDXView", "Name": "m"}
    assert isinstance(api.run(api.client.views.get("Sales", ViewScope.PRIVATE, "m", select="Name")), MDXView)
    assert target(fake.last) == "GET Cubes('Sales')/PrivateViews('m')?$select=Name"


def test_reads_a_native_view_through_its_type(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Name": "n", "Columns": [{"Subset": {"UniqueName": "[Month].[Quarters]"}}]}
    view = api.run(api.client.views.get_native("Sales", ViewScope.PUBLIC, "n", ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset")))
    assert isinstance(view, NativeView)
    assert view.columns[0].subset.unique_name == "[Month].[Quarters]"
    assert target(fake.last) == "GET Cubes('Sales')/Views('n')/ibm.tm1.api.v1.NativeView?$expand=Columns/Subset,Rows/Subset"


def test_creates_updates_and_deletes(api: Api, fake: FakeTm1) -> None:
    dimensions = ODataPath.of("Dimensions")
    months = dimensions.key("Month").nav("Hierarchies").key("Month").nav("Subsets").key("Quarters")
    versions = dimensions.key("Version").nav("Hierarchies").key("Version")
    patch = (
        NativeViewPatch.builder()
        .name("Revenue")
        .columns([ViewAxisSelectionPatch.builder().bind_subset(months).build()])
        .titles([ViewTitlePatch.builder().bind_subset(versions.nav("Subsets").key("All")).bind_selected(versions.nav("Elements").key("Actual")).build()])
        .suppress_empty_rows(True)
        .build()
    )
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.NativeView", "Name": "Revenue"}
    created = api.run(api.client.views.create("Sales", ViewScope.PUBLIC, patch))
    assert isinstance(created, NativeView)
    assert target(fake.last) == "POST Cubes('Sales')/Views"
    assert body(fake.last) == {
        "@odata.type": "#ibm.tm1.api.v1.NativeView",
        "Name": "Revenue",
        "Columns": [{"Subset@odata.bind": "Dimensions('Month')/Hierarchies('Month')/Subsets('Quarters')"}],
        "Titles": [
            {
                "Subset@odata.bind": "Dimensions('Version')/Hierarchies('Version')/Subsets('All')",
                "Selected@odata.bind": "Dimensions('Version')/Hierarchies('Version')/Elements('Actual')",
            }
        ],
        "SuppressEmptyRows": True,
    }

    fake.respond = lambda r: None
    api.run(api.client.views.update("Sales", ViewScope.PRIVATE, "m", MDXViewPatch.builder().mdx("SELECT 1").build()))
    assert (target(fake.last), body(fake.last)) == (
        "PATCH Cubes('Sales')/PrivateViews('m')",
        {"@odata.type": "#ibm.tm1.api.v1.MDXView", "MDX": "SELECT 1"},
    )
    assert "if-match" not in fake.last.headers
    api.run(api.client.views.update("Sales", ViewScope.PRIVATE, "m", MDXViewPatch.builder().mdx("x").build(), 'W/"2"'))
    assert fake.last.headers["if-match"] == 'W/"2"'
    api.run(api.client.views.delete("Sales", ViewScope.PUBLIC, "Revenue"))
    assert target(fake.last) == "DELETE Cubes('Sales')/Views('Revenue')"


def test_a_stale_etag_is_refused(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: httpx.Response(412, json={"error": {"code": "278", "message": "stale"}})
    with pytest.raises(Tm1PreconditionFailedError):
        api.run(api.client.views.update("Sales", ViewScope.PUBLIC, "v", MDXViewPatch.builder().mdx("x").build(), "e"))


def test_reads_a_views_cube(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Name": "Sales"}
    assert api.run(api.client.views.cube("Sales", ViewScope.PUBLIC, "v", select="Name")).name == "Sales"
    assert target(fake.last) == "GET Cubes('Sales')/Views('v')/Cube?$select=Name"
