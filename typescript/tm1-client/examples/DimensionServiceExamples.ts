// SPDX-License-Identifier: Apache-2.0

import {
  Dimension,
  DimensionCreate,
  EdgeCreate,
  ElementCreate,
  ElementType,
  Hierarchy,
  HierarchyCreate,
  ODataQuery,
  type Tm1Client,
} from "@skipcheck/tm1-client";

// Examples for DimensionService, quoted in its documentation.

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // The names of the model dimensions, in name order
  const dimensions = await tm1.dimensions.modelDimensions(ODataQuery.none().select(Dimension.Fields.Name).orderBy(Dimension.Fields.Name)).toArray();
  return dimensions.map((dimension) => dimension.Name!);
  // @end
}

export function create(tm1: Tm1Client): Promise<Dimension> {
  // @start region = create
  // Region, with one hierarchy of the same name: Total = North + South
  const total = ElementCreate.builder().name("Total").type(ElementType.Consolidated).build();
  const north = ElementCreate.builder().name("North").type(ElementType.Numeric).build();
  const south = ElementCreate.builder().name("South").type(ElementType.Numeric).build();
  const region = HierarchyCreate.builder()
    .name("Region")
    .elements([total, north, south])
    .edges([
      EdgeCreate.builder().parentName("Total").componentName("North").weight(1).build(),
      EdgeCreate.builder().parentName("Total").componentName("South").weight(1).build(),
    ])
    .build();
  return tm1.dimensions.create(DimensionCreate.builder().name("Region").hierarchies([region]).build());
  // @end
}

export async function hierarchies(tm1: Tm1Client): Promise<string[]> {
  // @start region = hierarchies
  // A dimension with its hierarchies' names expanded in the same request
  const withHierarchies = ODataQuery.none().expand(Dimension.Fields.Hierarchies, (h) => h.select(Hierarchy.Fields.Name));
  const region = await tm1.dimensions.get("Region", withHierarchies);
  return (region.Hierarchies ?? []).map((hierarchy) => hierarchy.Name!);
  // @end
}
