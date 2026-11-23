import { Edge, Element, Hierarchy, Tm1Operations, type ElementPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Elements: `/Dimensions('d')/Hierarchies('h')/Elements`, an element's parents, components and
 * edges, and the operations bound to an element.
 *
 * TM1 (11.8) ignores `Name` in an element PATCH, so elements can't be renamed, and rejects
 * `Attributes`, whose values are cells of the `}ElementAttributes_<dimension>` cube. {@link update}
 * refuses both, saying why.
 *
 * List elements with a query: here, the consolidations:
 * ```ts
 * tm1.elements.list("Region", "Region", ODataQuery.none()
 *     .select(Element.Fields.Name)
 *     .filter(Filter.eq(Element.Fields.Type, ElementType.literal("Consolidated"))))
 * ```
 * Add an element under a consolidation. A new element is a root until it has a parent:
 * ```ts
 * await tm1.elements.create("Region", "Region", ElementPatch.builder().name("West").type(ElementType.Numeric).build());
 * await tm1.elements.setComponent("Region", "Region", "Total", "West");
 * ```
 * Read a consolidation's children:
 * ```ts
 * tm1.elements.components("Region", "Region", "Total", ODataQuery.none().select(Element.Fields.Name))
 * ```
 */
export class ElementService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["Navigation Hierarchy/Elements"],
    get: ["Navigation Hierarchy/Elements"],
    create: ["Navigation Hierarchy/Elements"],
    update: ["Navigation Hierarchy/Elements"],
    delete: ["Navigation Hierarchy/Elements"],
    parents: ["Navigation Element/Parents"],
    components: ["Navigation Element/Components"],
    edges: ["Navigation Element/Edges"],
    hierarchy: ["Navigation Element/Hierarchy"],
    setComponent: ["Action Element/SetComponent"],
    lock: ["Action Element/Lock"],
    unlock: ["Action Element/Unlock"],
    reserve: ["Action Element/Reserve"],
    release: ["Action Element/Release"],
  } as const satisfies Tm1Endpoints<ElementService>;

  /** `GET …/Hierarchies('h')/Elements`: the hierarchy's elements, streamed. */
  list(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Element> {
    return this.session.stream(Tm1Request.list(elementsPath(dimension, hierarchy), query, Element), options);
  }

  /** `GET …/Elements('e')`: the element, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, element: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Element> {
    return this.session.execute(Tm1Request.get(elementPath(dimension, hierarchy, element), query, Element), options);
  }

  /** `POST …/Elements`: adds an element, as a root of the hierarchy. It needs a name, and a type
   * unless it's numeric; returns the element as created. */
  create(dimension: string, hierarchy: string, element: ElementPatch, options?: Tm1CallOptions): Promise<Element> {
    return this.session.execute(Tm1Request.post(elementsPath(dimension, hierarchy), required(element, "element"), Element), options);
  }

  /** `PATCH …/Elements('e')`: changes the properties the patch sets, such as its type. With `etag`
   * (`element["@odata.etag"]`, from a read), sent as `If-Match`: only if the element hasn't changed
   * since, else a `Tm1PreconditionFailedException`.
   *
   * Throws a `RangeError` at once if the patch sets `Name`, which TM1 ignores, or `Attributes`,
   * which it rejects. */
  update(dimension: string, hierarchy: string, element: string, patch: ElementPatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(elementPath(dimension, hierarchy, element), checked(required(patch, "patch")));
    return this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE …/Elements('e')`, with the element's edges and data. */
  delete(dimension: string, hierarchy: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(elementPath(dimension, hierarchy, element)), options);
  }

  /** `GET …/Elements('e')/Parents`: the consolidations the element is in, streamed. */
  parents(dimension: string, hierarchy: string, element: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Element> {
    return this.session.stream(Tm1Request.list(elementPath(dimension, hierarchy, element).nav("Parents"), query, Element), options);
  }

  /** `GET …/Elements('e')/Components`: the element's children, in order, streamed. */
  components(dimension: string, hierarchy: string, element: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Element> {
    return this.session.stream(Tm1Request.list(elementPath(dimension, hierarchy, element).nav("Components"), query, Element), options);
  }

  /** `GET …/Elements('e')/Edges`: the edges to the element's children, streamed. */
  edges(dimension: string, hierarchy: string, element: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Edge> {
    return this.session.stream(Tm1Request.list(elementPath(dimension, hierarchy, element).nav("Edges"), query, Edge), options);
  }

  /** `GET …/Elements('e')/Hierarchy`: the element's hierarchy. */
  hierarchy(dimension: string, hierarchy: string, element: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(elementPath(dimension, hierarchy, element).nav("Hierarchy"), query, Hierarchy), options);
  }

  /** `tm1.SetComponent`: makes `child` a child of `parent`, with weight 1, placed *last* among its
   * children. (`$metadata` doesn't say; TM1 11.8 puts it last.) */
  setComponent(dimension: string, hierarchy: string, parent: string, child: string, options?: Tm1CallOptions): Promise<void>;
  /** `tm1.SetComponent`: makes `child` a child of `parent` with a weight, placed last among its children. */
  setComponent(dimension: string, hierarchy: string, parent: string, child: string, weight: number, options?: Tm1CallOptions): Promise<void>;
  /** `tm1.SetComponent`: makes `child` a child of `parent` with a weight, placed just before the child `before`. */
  setComponent(dimension: string, hierarchy: string, parent: string, child: string, before: string, weight: number, options?: Tm1CallOptions): Promise<void>;
  setComponent(
    dimension: string,
    hierarchy: string,
    parent: string,
    child: string,
    before?: string | number | Tm1CallOptions,
    weight?: number | Tm1CallOptions,
    options?: Tm1CallOptions,
  ): Promise<void> {
    if (typeof before === "string") {
      if (typeof weight !== "number") throw new TypeError("weight is required");
      return this.sendSetComponent(dimension, hierarchy, parent, child, before, weight, options);
    }
    if (typeof before === "number") return this.sendSetComponent(dimension, hierarchy, parent, child, undefined, before, weight as Tm1CallOptions | undefined);
    return this.sendSetComponent(dimension, hierarchy, parent, child, undefined, undefined, before);
  }

  /** `tm1.Lock`: stops everyone else changing the element until it's unlocked. Needs schema 11.8.23. */
  lock(dimension: string, hierarchy: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ElementLock, dimension, hierarchy, element, {}), options);
  }

  /** `tm1.Unlock`. Needs schema 11.8.23. */
  unlock(dimension: string, hierarchy: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ElementUnlock, dimension, hierarchy, element, {}), options);
  }

  /** `tm1.Reserve`: stops everyone else changing the element's data until it's released. Needs schema 11.8.34. */
  reserve(dimension: string, hierarchy: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ElementReserve, dimension, hierarchy, element, {}), options);
  }

  /** `tm1.Release`. Needs schema 11.8.34. */
  release(dimension: string, hierarchy: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ElementRelease, dimension, hierarchy, element, {}), options);
  }

  private sendSetComponent(
    dimension: string,
    hierarchy: string,
    parent: string,
    child: string,
    before: string | undefined,
    weight: number | undefined,
    options: Tm1CallOptions | undefined,
  ): Promise<void> {
    const body: Record<string, unknown> = { "Element@odata.bind": elementPath(dimension, hierarchy, child).toString() };
    if (before !== undefined) body["Before@odata.bind"] = elementPath(dimension, hierarchy, before).toString();
    if (weight !== undefined) body["Weight"] = weight;
    return this.session.execute(action(Tm1Operations.ElementSetComponent, dimension, hierarchy, parent, body), options);
  }
}

function checked(patch: ElementPatch): ElementPatch {
  const keys = new Set(patch.values().map(([key]) => key));
  if (keys.has("Name")) throw new RangeError("TM1 ignores Name in an element PATCH: elements can't be renamed");
  if (keys.has("Attributes")) throw new RangeError("TM1 rejects Attributes in an element PATCH: attribute values are cells of }ElementAttributes_<dimension>");
  return patch;
}

function elementsPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy")).nav("Elements");
}

function elementPath(dimension: string, hierarchy: string, element: string): ODataPath {
  return elementsPath(dimension, hierarchy).key(required(element, "element"));
}

function action(operation: Tm1Operation, dimension: string, hierarchy: string, element: string, body: Record<string, unknown>): Tm1Request<void> {
  return Tm1Request.post(elementPath(dimension, hierarchy, element).action(operation.name), body).requires(operation.capability());
}
