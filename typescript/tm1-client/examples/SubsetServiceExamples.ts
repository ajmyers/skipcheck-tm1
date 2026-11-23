// SPDX-License-Identifier: Apache-2.0

import { Element, ODataPath, ODataQuery, SubsetPatch, SubsetScope, type Subset, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for SubsetService, quoted in its documentation.

export function createStatic(tm1: Tm1Client): Promise<Subset> {
  // @start region = create-static
  // A static subset lists its elements, bound by their paths
  const elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements");
  const keyRegions = SubsetPatch.builder().name("Key Regions").bindElements(elements.key("North"), elements.key("South")).build();
  return tm1.subsets.create("Region", "Region", SubsetScope.Public, keyRegions);
  // @end
}

export function createDynamic(tm1: Tm1Client): Promise<Subset> {
  // @start region = create-dynamic
  // A dynamic subset has an MDX expression, evaluated each time it's read
  const allLeaves = SubsetPatch.builder().name("All Leaves").expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}").build();
  return tm1.subsets.create("Region", "Region", SubsetScope.Public, allLeaves);
  // @end
}

export async function replaceElements(tm1: Tm1Client): Promise<void> {
  // @start region = replace
  // A PATCH appends elements, so empty the list, then add the new ones in order
  await tm1.subsets.clearElements("Region", "Region", SubsetScope.Public, "Key Regions");
  await tm1.subsets.addElements("Region", "Region", SubsetScope.Public, "Key Regions", ["East", "West"]);
  // @end
}

export async function session(tm1: Tm1Client): Promise<string[]> {
  // @start region = session
  // TM1 names a session subset itself, ignoring the name sent: read it by the name it
  // gives back
  const regions = SubsetPatch.builder().name("Regions").expression("{[Region].[Region].[Total].Children}").build();
  const subset = await tm1.subsets.createSessionSubset("Region", "Region", regions);
  const elements = await tm1.subsets.elements("Region", "Region", SubsetScope.Session, subset.Name!, ODataQuery.none().select(Element.Fields.Name)).toArray();
  return elements.map((element) => element.Name!);
  // @end
}
