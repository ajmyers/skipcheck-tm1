import { randomUUID } from "node:crypto";
import { DimensionPatch, ElementPatch, ElementType, HierarchyPatch, ODataPath, SubsetScope, type SubsetPatch, type Tm1Client } from "../../src/index.js";

// What the attribute tests share.

export const DIMENSIONS = ODataPath.of("Dimensions");

export function createDimension(tm1: Tm1Client, name: string, elements: string[]): Promise<unknown> {
  const hierarchy = HierarchyPatch.builder()
    .name(name)
    .elements(elements.map((e) => ElementPatch.builder().name(e).type(ElementType.Numeric).build()))
    .build();
  return tm1.dimensions.create(DimensionPatch.builder().name(name).hierarchies([hierarchy]).build());
}

export function createSubset(tm1: Tm1Client, dimension: string, subset: SubsetPatch): Promise<unknown> {
  return tm1.subsets.create(dimension, dimension, SubsetScope.Public, subset);
}

export function deleteDimension(tm1: Tm1Client, name: string): Promise<void> {
  return tm1.dimensions.delete(name);
}

export const shortId = (): string => randomUUID().slice(0, 4);
