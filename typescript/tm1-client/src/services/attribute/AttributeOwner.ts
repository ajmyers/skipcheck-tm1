import { ODataPath } from "../../odata/index.js";
import { required } from "../../util.js";
import { ViewScope } from "../cube/ViewScope.js";
import { SubsetScope } from "../dimension/SubsetScope.js";

/** An object whose attribute values can be translated: it has `LocalizedAttributes`. */
export class AttributeOwner {
  readonly #path: ODataPath;

  private constructor(path: ODataPath) {
    this.#path = path;
  }

  /** A dimension. */
  static dimension(dimension: string): AttributeOwner {
    return new AttributeOwner(ODataPath.of("Dimensions").key(required(dimension, "dimension")));
  }

  /** A hierarchy. */
  static hierarchy(dimension: string, hierarchy: string): AttributeOwner {
    return new AttributeOwner(hierarchyPath(dimension, hierarchy));
  }

  /** An element. */
  static element(dimension: string, hierarchy: string, element: string): AttributeOwner {
    return new AttributeOwner(hierarchyPath(dimension, hierarchy).nav("Elements").key(required(element, "element")));
  }

  /** A member. */
  static member(dimension: string, hierarchy: string, member: string): AttributeOwner {
    return new AttributeOwner(hierarchyPath(dimension, hierarchy).nav("Members").key(required(member, "member")));
  }

  /** A subset, in `scope`'s collection: `Subsets`, `PrivateSubsets` or `SessionSubsets`. */
  static subset(dimension: string, hierarchy: string, scope: SubsetScope, subset: string): AttributeOwner {
    const path = hierarchyPath(dimension, hierarchy);
    return new AttributeOwner(path.nav(SubsetScope.navigation(required(scope, "scope"))).key(required(subset, "subset")));
  }

  /** A cube. */
  static cube(cube: string): AttributeOwner {
    return new AttributeOwner(ODataPath.of("Cubes").key(required(cube, "cube")));
  }

  /** A view, in `scope`'s collection: `Views` (`"Public"`) or `PrivateViews` (`"Private"`). */
  static view(cube: string, scope: ViewScope, view: string): AttributeOwner {
    const path = ODataPath.of("Cubes").key(required(cube, "cube"));
    return new AttributeOwner(path.nav(ViewScope.navigation(required(scope, "scope"))).key(required(view, "view")));
  }

  /** A process. */
  static process(process: string): AttributeOwner {
    return new AttributeOwner(ODataPath.of("Processes").key(required(process, "process")));
  }

  /** A chore. */
  static chore(chore: string): AttributeOwner {
    return new AttributeOwner(ODataPath.of("Chores").key(required(chore, "chore")));
  }

  /** @internal The owner's path. */
  path(): ODataPath {
    return this.#path;
  }

  /** The owner's path, for logs. */
  toString(): string {
    return this.#path.toString();
  }
}

function hierarchyPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy"));
}
