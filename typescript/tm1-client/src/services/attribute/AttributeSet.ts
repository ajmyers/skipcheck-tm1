// SPDX-License-Identifier: Apache-2.0

import { ODataPath } from "../../odata/index.js";
import { required } from "../../util.js";

/** A set of attribute definitions: the attributes that one kind of object has, such as every
 * element of a hierarchy. */
export class AttributeSet {
  readonly #path: ODataPath;

  private constructor(path: ODataPath) {
    this.#path = path;
  }

  /** Dimensions' attributes: `/DimensionAttributes`. */
  static dimensions(): AttributeSet {
    return new AttributeSet(ODataPath.of("DimensionAttributes"));
  }

  /** The attributes of a dimension's hierarchies: `Dimensions('d')/HierarchyAttributes`. */
  static hierarchies(dimension: string): AttributeSet {
    return new AttributeSet(ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("HierarchyAttributes"));
  }

  /** The attributes of a hierarchy's elements: `…/Hierarchies('h')/ElementAttributes`. */
  static elements(dimension: string, hierarchy: string): AttributeSet {
    return new AttributeSet(hierarchyPath(dimension, hierarchy).nav("ElementAttributes"));
  }

  /** The attributes of a hierarchy's subsets: `…/Hierarchies('h')/SubsetAttributes`. */
  static subsets(dimension: string, hierarchy: string): AttributeSet {
    return new AttributeSet(hierarchyPath(dimension, hierarchy).nav("SubsetAttributes"));
  }

  /** Cubes' attributes: `/CubeAttributes`. */
  static cubes(): AttributeSet {
    return new AttributeSet(ODataPath.of("CubeAttributes"));
  }

  /** The attributes of a cube's views: `Cubes('c')/ViewAttributes`. TM1 (11.8) refuses to create
   * one (400, "no public views") until the cube has a public *native* view; an MDX view doesn't
   * count. */
  static views(cube: string): AttributeSet {
    return new AttributeSet(ODataPath.of("Cubes").key(required(cube, "cube")).nav("ViewAttributes"));
  }

  /** Processes' attributes: `/ProcessAttributes`. */
  static processes(): AttributeSet {
    return new AttributeSet(ODataPath.of("ProcessAttributes"));
  }

  /** Chores' attributes: `/ChoreAttributes`. */
  static chores(): AttributeSet {
    return new AttributeSet(ODataPath.of("ChoreAttributes"));
  }

  /** @internal The set's collection. */
  path(): ODataPath {
    return this.#path;
  }

  /** The set's collection, for logs. */
  toString(): string {
    return this.#path.toString();
  }
}

function hierarchyPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy"));
}
