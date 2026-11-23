import { ODataPath } from "../../odata/index.js";
import { entriesOf, required, type Tm1Map } from "../../util.js";

/** Cell coordinates, as TM1 binds them: one element path per dimension of the cube, in the cube's
 * order (Java's `Tuples`).
 * ```ts
 * const cell = new Map([["Region", "North"], ["Measure", "Amount"]]);
 * CubeUpdatePatch.builder().bindTuple(...Tuples.of(cell)).value(42).build();
 * ```
 */
export const Tuples = {
  /** The element paths for a cell: `tuple` maps dimension to element, for every dimension of the
   * cube in its order (a `Map` if a name may look like an integer); each element is in its
   * dimension's same-named hierarchy. One path per element, such as
   * `Dimensions('Region')/Hierarchies('Region')/Elements('North')`. */
  of(tuple: Tm1Map<string>): ODataPath[] {
    return entriesOf(required(tuple, "tuple")).map(([dimension, element]) => Tuples.element(dimension, dimension, element));
  },

  /** One element's path, `Dimensions('d')/Hierarchies('h')/Elements('e')`. */
  element(dimension: string, hierarchy: string, element: string): ODataPath {
    return ODataPath.of("Dimensions")
      .key(required(dimension, "dimension"))
      .nav("Hierarchies")
      .key(required(hierarchy, "hierarchy"))
      .nav("Elements")
      .key(required(element, "element"));
  },
} as const;
