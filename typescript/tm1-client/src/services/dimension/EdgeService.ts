import { Edge, Element, Hierarchy, type EdgePatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Edges: `/Dimensions('d')/Hierarchies('h')/Edges`, the parent-child links of a hierarchy, each
 * keyed by its parent's and its component's names.
 *
 * List a hierarchy's edges:
 * ```ts
 * for await (const edge of tm1.edges.list("Region", "Region")) console.log(edge.ParentName, edge.ComponentName, edge.Weight);
 * ```
 * Change an edge's weight:
 * ```ts
 * await tm1.edges.update("Region", "Region", "Total", "West", EdgePatch.builder().weight(-1).build());
 * ```
 * Move an element to another parent:
 * ```ts
 * await tm1.edges.delete("Region", "Region", "North", "Ohio");
 * await tm1.edges.create("Region", "Region", EdgePatch.builder().parentName("Midwest").componentName("Ohio").weight(1).build());
 * ```
 */
export class EdgeService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["Navigation Hierarchy/Edges"],
    get: ["Navigation Hierarchy/Edges"],
    create: ["Navigation Hierarchy/Edges"],
    update: ["Navigation Hierarchy/Edges"],
    delete: ["Navigation Hierarchy/Edges"],
    parent: ["Navigation Edge/Parent"],
    component: ["Navigation Edge/Component"],
    hierarchy: ["Navigation Edge/Hierarchy"],
  } as const satisfies Tm1Endpoints<EdgeService>;

  /** `GET …/Hierarchies('h')/Edges`, streamed. */
  list(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Edge> {
    return this.session.stream(Tm1Request.list(edgesPath(dimension, hierarchy), query, Edge), options);
  }

  /** `GET …/Edges(ParentName='p',ComponentName='c')`: the edge, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, parent: string, component: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Edge> {
    return this.session.execute(Tm1Request.get(edgePath(dimension, hierarchy, parent, component), query, Edge), options);
  }

  /** `POST …/Edges`: makes an element a child of a consolidation (parent, component and, optionally,
   * weight); returns the edge as created. */
  create(dimension: string, hierarchy: string, edge: EdgePatch, options?: Tm1CallOptions): Promise<Edge> {
    return this.session.execute(Tm1Request.post(edgesPath(dimension, hierarchy), required(edge, "edge"), Edge), options);
  }

  /** `PATCH …/Edges(ParentName='p',ComponentName='c')`: changes the edge's weight. */
  update(dimension: string, hierarchy: string, parent: string, component: string, patch: EdgePatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(edgePath(dimension, hierarchy, parent, component), required(patch, "patch")), options);
  }

  /** `DELETE …/Edges(ParentName='p',ComponentName='c')`: takes an element out of a consolidation; the
   * element stays in the hierarchy. */
  delete(dimension: string, hierarchy: string, parent: string, component: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(edgePath(dimension, hierarchy, parent, component)), options);
  }

  /** `GET …/Edges(…)/Parent`: the edge's parent. */
  parent(dimension: string, hierarchy: string, parent: string, component: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Element> {
    return this.session.execute(Tm1Request.get(edgePath(dimension, hierarchy, parent, component).nav("Parent"), query, Element), options);
  }

  /** `GET …/Edges(…)/Component`: the edge's component. */
  component(dimension: string, hierarchy: string, parent: string, component: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Element> {
    return this.session.execute(Tm1Request.get(edgePath(dimension, hierarchy, parent, component).nav("Component"), query, Element), options);
  }

  /** `GET …/Edges(…)/Hierarchy`: the edge's hierarchy. */
  hierarchy(dimension: string, hierarchy: string, parent: string, component: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(edgePath(dimension, hierarchy, parent, component).nav("Hierarchy"), query, Hierarchy), options);
  }
}

function edgesPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy")).nav("Edges");
}

function edgePath(dimension: string, hierarchy: string, parent: string, component: string): ODataPath {
  return edgesPath(dimension, hierarchy).key(
    new Map<string, unknown>([
      ["ParentName", required(parent, "parent")],
      ["ComponentName", required(component, "component")],
    ]),
  );
}
