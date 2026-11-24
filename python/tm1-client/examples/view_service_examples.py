# SPDX-License-Identifier: Apache-2.0

"""Examples for ViewService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client
from skipcheck_tm1.model import MDXView, MDXViewCreate, NativeView, NativeViewCreate, View, ViewAxisSelectionPatch, ViewTitlePatch
from skipcheck_tm1.services import ViewScope


def create_native(tm1: Tm1Client) -> View:
    # @start region = create-native
    # Months across, regions down, and the cube's other dimensions as titles: every
    # dimension of the cube must be on an axis. Each axis binds an existing subset.
    dimensions = ODataPath.of("Dimensions")
    months = dimensions.key("Month").nav("Hierarchies").key("Month").nav("Subsets").key("Quarters")
    regions = dimensions.key("Region").nav("Hierarchies").key("Region").nav("Subsets").key("Leaves")
    versions = dimensions.key("Version").nav("Hierarchies").key("Version")
    measures = dimensions.key("Measure").nav("Hierarchies").key("Measure")
    actual = ViewTitlePatch.builder().bind_subset(versions.nav("Subsets").key("All")).bind_selected(versions.nav("Elements").key("Actual")).build()
    revenue = ViewTitlePatch.builder().bind_subset(measures.nav("Subsets").key("All")).bind_selected(measures.nav("Elements").key("Revenue")).build()
    view = (
        NativeViewCreate.builder()
        .name("Revenue by Region")
        .columns([ViewAxisSelectionPatch.builder().bind_subset(months).build()])
        .rows([ViewAxisSelectionPatch.builder().bind_subset(regions).build()])
        .titles([actual, revenue])
        .suppress_empty_rows(True)
        .build()
    )
    return tm1.views.create("Sales", ViewScope.PUBLIC, view)
    # @end


def create_mdx(tm1: Tm1Client) -> View:
    # @start region = create-mdx
    # An MDX view is its query
    mdx = (
        "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0,"
        " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]"
        " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])"
    )
    return tm1.views.create("Sales", ViewScope.PRIVATE, MDXViewCreate.builder().name("My Revenue").mdx(mdx).build())
    # @end


def list_views(tm1: Tm1Client) -> list[str]:
    # @start region = list
    # Each view comes back as its own type
    described = []
    for view in tm1.views.list("Sales", ViewScope.PUBLIC):
        if isinstance(view, MDXView):
            described.append(f"{view.name}: {view.mdx}")
        elif isinstance(view, NativeView):
            described.append(f"{view.name}: native")
        else:
            described.append(f"{view.name}: unknown kind")
    return described
    # @end


def read_native(tm1: Tm1Client) -> list[str | None]:
    # @start region = read-native
    # A native view's axes are complex properties: expand them by path, through the
    # view's type
    axes = ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset")
    view = tm1.views.get_native("Sales", ViewScope.PUBLIC, "Revenue by Region", axes)
    return [axis.subset.unique_name for axis in [*(view.columns or []), *(view.rows or [])] if axis.subset is not None]
    # @end
