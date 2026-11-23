// SPDX-License-Identifier: Apache-2.0

import {
  MDXViewPatch,
  NativeViewPatch,
  ODataPath,
  ODataQuery,
  View,
  ViewAxisSelectionPatch,
  ViewScope,
  ViewTitlePatch,
  typeOf,
  type MDXView,
  type Tm1Client,
} from "@skipcheck/tm1-client";

// Examples for ViewService, quoted in its documentation.

export function createNative(tm1: Tm1Client): Promise<View> {
  // @start region = create-native
  // Months across, regions down, and the cube's other dimensions as titles: every dimension
  // of the cube must be on an axis. Each axis binds an existing subset.
  const dimensions = ODataPath.of("Dimensions");
  const months = dimensions.key("Month").nav("Hierarchies").key("Month").nav("Subsets").key("Quarters");
  const regions = dimensions.key("Region").nav("Hierarchies").key("Region").nav("Subsets").key("Leaves");
  const versions = dimensions.key("Version").nav("Hierarchies").key("Version");
  const measures = dimensions.key("Measure").nav("Hierarchies").key("Measure");
  const view = NativeViewPatch.builder()
    .name("Revenue by Region")
    .columns([ViewAxisSelectionPatch.builder().bindSubset(months).build()])
    .rows([ViewAxisSelectionPatch.builder().bindSubset(regions).build()])
    .titles([
      ViewTitlePatch.builder().bindSubset(versions.nav("Subsets").key("All")).bindSelected(versions.nav("Elements").key("Actual")).build(),
      ViewTitlePatch.builder().bindSubset(measures.nav("Subsets").key("All")).bindSelected(measures.nav("Elements").key("Revenue")).build(),
    ])
    .suppressEmptyRows(true)
    .build();
  return tm1.views.create("Sales", ViewScope.Public, view);
  // @end
}

export function createMdx(tm1: Tm1Client): Promise<View> {
  // @start region = create-mdx
  // An MDX view is its query
  const mdx =
    "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0," +
    " {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1 FROM [Sales]" +
    " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
  return tm1.views.create("Sales", ViewScope.Private, MDXViewPatch.builder().name("My Revenue").mdx(mdx).build());
  // @end
}

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // Each view says which type it is
  const views = await tm1.views.list("Sales", ViewScope.Public).toArray();
  return views.map((view) => {
    switch (typeOf(View, view)) {
      case "MDXView":
        return `${view.Name}: ${(view as MDXView).MDX}`;
      case "NativeView":
        return `${view.Name}: native`;
      default:
        return `${view.Name}: unknown kind`;
    }
  });
  // @end
}

export async function readNative(tm1: Tm1Client): Promise<string[]> {
  // @start region = read-native
  // A native view's axes are complex properties: expand them by path, through the view's
  // type
  const withSubsets = ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset");
  const view = await tm1.views.getNative("Sales", ViewScope.Public, "Revenue by Region", withSubsets);
  return [...(view.Columns ?? []), ...(view.Rows ?? [])].map((axis) => axis.Subset!.UniqueName!);
  // @end
}
