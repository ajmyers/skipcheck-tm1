// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { DimensionCreate, ElementCreate, ElementType, HierarchyCreate, ODataPath, SubsetScope, type SubsetCreate, type Tm1Client } from "../../src/index.js";

// What the attribute tests share.

export const DIMENSIONS = ODataPath.of("Dimensions");

export function createDimension(tm1: Tm1Client, name: string, elements: string[]): Promise<unknown> {
  const hierarchy = HierarchyCreate.builder()
    .name(name)
    .elements(elements.map((e) => ElementCreate.builder().name(e).type(ElementType.Numeric).build()))
    .build();
  return tm1.dimensions.create(DimensionCreate.builder().name(name).hierarchies([hierarchy]).build());
}

export function createSubset(tm1: Tm1Client, dimension: string, subset: SubsetCreate): Promise<unknown> {
  return tm1.subsets.create(dimension, dimension, SubsetScope.Public, subset);
}

export function deleteDimension(tm1: Tm1Client, name: string): Promise<void> {
  return tm1.dimensions.delete(name);
}

export const shortId = (): string => randomUUID().slice(0, 4);
