// SPDX-License-Identifier: Apache-2.0

import { Element, ElementPatch, ElementType, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for ElementService, quoted in its documentation.

export async function consolidations(tm1: Tm1Client): Promise<string[]> {
  // @start region = filter
  // The names of the consolidated elements, in name order
  const consolidated = ODataQuery.none()
    .select(Element.Fields.Name)
    .filter(Element.Fields.Type.eq(ElementType.literal(ElementType.Consolidated)))
    .orderBy(Element.Fields.Name);
  const elements = await tm1.elements.list("Region", "Region", consolidated).toArray();
  return elements.map((element) => element.Name!);
  // @end
}

export async function addChild(tm1: Tm1Client): Promise<void> {
  // @start region = add-child
  // A new element is a root; SetComponent then puts it last under Total
  await tm1.elements.create("Region", "Region", ElementPatch.builder().name("West").type(ElementType.Numeric).build());
  await tm1.elements.setComponent("Region", "Region", "Total", "West");
  // @end
}

export async function children(tm1: Tm1Client): Promise<string[]> {
  // @start region = children
  // The children of Total, in order
  const components = await tm1.elements.components("Region", "Region", "Total", ODataQuery.none().select(Element.Fields.Name)).toArray();
  return components.map((element) => element.Name!);
  // @end
}
