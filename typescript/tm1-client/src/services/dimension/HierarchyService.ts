import type { Tm1Model } from "../../model.js";
import { Dimension, Hierarchy, HierarchyPatch, Member, Tm1Operations, type EdgePatch, type ElementPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Hierarchies: `/Dimensions('d')/Hierarchies`, a dimension's default hierarchy, and the operations
 * bound to a hierarchy. Elements, edges, members, levels and subsets have services of their own.
 *
 * A hierarchy PATCH that sets `Elements` or `Edges` *replaces* them: TM1 deletes the elements it
 * doesn't list. So {@link update} refuses such a patch, and {@link replaceStructure} does it by name.
 *
 * Add a hierarchy to a dimension, with its elements and edges:
 * ```ts
 * await tm1.hierarchies.create("Region", HierarchyPatch.builder().name("ByManager")
 *   .elements([ElementPatch.builder().name("All").type(ElementType.Consolidated).build(), ElementPatch.builder().name("North").build()])
 *   .edges([EdgePatch.builder().parentName("All").componentName("North").weight(1).build()])
 *   .build());
 * ```
 * Replace a hierarchy's structure, knowing that TM1 deletes what isn't listed:
 * ```ts
 * await tm1.hierarchies.replaceStructure("Region", "Region", elements, edges);
 * ```
 * Read the roots of a hierarchy:
 * ```ts
 * tm1.hierarchies.rootMembers("Region", "Region", ODataQuery.none().select(Member.Fields.Name))
 * ```
 */
export class HierarchyService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["Navigation Dimension/Hierarchies"],
    get: ["Navigation Dimension/Hierarchies"],
    create: ["Navigation Dimension/Hierarchies"],
    update: ["Navigation Dimension/Hierarchies"],
    replaceStructure: ["Navigation Dimension/Hierarchies"],
    delete: ["Navigation Dimension/Hierarchies"],
    defaultHierarchy: ["Navigation Dimension/DefaultHierarchy"],
    dimension: ["Navigation Hierarchy/Dimension"],
    allMember: ["Navigation Hierarchy/AllMember"],
    defaultMember: ["Navigation Hierarchy/DefaultMember"],
    rootMembers: ["Function Hierarchy/RootMembers"],
    saveAs: ["Action Hierarchy/SaveAs"],
    setElement: ["Action Hierarchy/SetElement"],
  } as const satisfies Tm1Endpoints<HierarchyService>;

  /** `GET Dimensions('d')/Hierarchies`: the dimension's hierarchies, streamed. */
  list(dimension: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Hierarchy> {
    return this.session.stream(Tm1Request.list(hierarchiesPath(dimension), query, Hierarchy), options);
  }

  /** `GET Dimensions('d')/Hierarchies('h')`: the hierarchy, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(hierarchyPath(dimension, hierarchy), query, Hierarchy), options);
  }

  /** `POST Dimensions('d')/Hierarchies`: adds a hierarchy, with its elements and edges if the patch
   * has them. It needs a name; returns the hierarchy as created. */
  create(dimension: string, hierarchy: HierarchyPatch, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.post(hierarchiesPath(dimension), required(hierarchy, "hierarchy"), Hierarchy), options);
  }

  /** `PATCH Dimensions('d')/Hierarchies('h')`: changes the properties the patch sets. TM1 (11.8)
   * ignores `Visible`. With `etag` (`hierarchy["@odata.etag"]`, from a read), sent as `If-Match`:
   * only if the hierarchy hasn't changed since, else a `Tm1PreconditionFailedException`.
   *
   * Throws a `RangeError` at once if the patch sets `Elements` or `Edges` (or binds them), which
   * would replace them: use {@link replaceStructure}. */
  update(dimension: string, hierarchy: string, patch: HierarchyPatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(hierarchyPath(dimension, hierarchy), withoutStructure(required(patch, "patch")));
    return this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `PATCH Dimensions('d')/Hierarchies('h')` with `Elements` and `Edges`: makes them exactly these.
   * TM1 deletes every element not listed, with its data. */
  replaceStructure(
    dimension: string,
    hierarchy: string,
    elements: readonly ElementPatch[],
    edges: readonly EdgePatch[],
    options?: Tm1CallOptions,
  ): Promise<void> {
    const patch = HierarchyPatch.builder().elements(required(elements, "elements")).edges(required(edges, "edges")).build();
    return this.session.execute(Tm1Request.patch(hierarchyPath(dimension, hierarchy), patch), options);
  }

  /** `DELETE Dimensions('d')/Hierarchies('h')`. */
  delete(dimension: string, hierarchy: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(hierarchyPath(dimension, hierarchy)), options);
  }

  /** `GET Dimensions('d')/DefaultHierarchy`: the dimension's default hierarchy. */
  defaultHierarchy(dimension: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(dimensionPath(dimension).nav("DefaultHierarchy"), query, Hierarchy), options);
  }

  /** `GET Dimensions('d')/Hierarchies('h')/Dimension`: the hierarchy's dimension. */
  dimension(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Dimension> {
    return this.session.execute(Tm1Request.get(hierarchyPath(dimension, hierarchy).nav("Dimension"), query, Dimension), options);
  }

  /** `GET Dimensions('d')/Hierarchies('h')/AllMember`: the member that stands for the whole
   * hierarchy. TM1 (11.8) has one only when the hierarchy has a single root, which is then that
   * root; with several roots it sends no content, and this is null. */
  async allMember(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Member | null> {
    return (await this.session.execute(Tm1Request.get(hierarchyPath(dimension, hierarchy).nav("AllMember"), query, Member), options)) ?? null;
  }

  /** `GET Dimensions('d')/Hierarchies('h')/DefaultMember`: the default member; null if TM1 sends
   * no content. */
  async defaultMember(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Member | null> {
    return (await this.session.execute(Tm1Request.get(hierarchyPath(dimension, hierarchy).nav("DefaultMember"), query, Member), options)) ?? null;
  }

  /** `tm1.RootMembers()`: the members with no parent, streamed. Needs schema 11.4.7. */
  rootMembers(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    const operation = Tm1Operations.HierarchyRootMembers;
    return this.session.stream(
      Tm1Request.list(hierarchyPath(dimension, hierarchy).function(operation.name), query, Member).requires(operation.capability()),
      options,
    );
  }

  /** `tm1.SaveAs`: copies a hierarchy under a new name in the same dimension, failing if one
   * exists; returns the copy. */
  saveAs(dimension: string, hierarchy: string, newName: string, options?: Tm1CallOptions): Promise<Hierarchy>;
  /** `tm1.SaveAs`: copies a hierarchy under a new name in the same dimension; `overwrite` replaces
   * a hierarchy that already has it, and `keepExistingAttributes`, when overwriting, keeps the
   * attributes of what's replaced (when not, true creates no attributes for the copy). */
  saveAs(
    dimension: string,
    hierarchy: string,
    newName: string,
    overwrite: boolean,
    keepExistingAttributes: boolean,
    options?: Tm1CallOptions,
  ): Promise<Hierarchy>;
  saveAs(
    dimension: string,
    hierarchy: string,
    newName: string,
    overwrite?: boolean | Tm1CallOptions,
    keepExistingAttributes?: boolean,
    options?: Tm1CallOptions,
  ): Promise<Hierarchy> {
    if (typeof overwrite !== "boolean") {
      return this.session.execute(action(Tm1Operations.HierarchySaveAs, dimension, hierarchy, { Name: required(newName, "newName") }, Hierarchy), overwrite);
    }
    const body = { Name: newName, Overwrite: overwrite, KeepExistingAttributes: required(keepExistingAttributes, "keepExistingAttributes") };
    return this.session.execute(action(Tm1Operations.HierarchySaveAs, dimension, hierarchy, body, Hierarchy), options);
  }

  /** `tm1.SetElement`: makes an element a root of the hierarchy, placed just before the root
   * `before`, or, without it, *last* among the roots. (`$metadata` says first; TM1 11.8 puts it
   * last.) */
  setElement(dimension: string, hierarchy: string, element: string, before?: string | null, options?: Tm1CallOptions): Promise<void> {
    const body: Record<string, unknown> = { "Element@odata.bind": elementPath(dimension, hierarchy, element).toString() };
    if (before != null) body["Before@odata.bind"] = elementPath(dimension, hierarchy, before).toString();
    return this.session.execute(action(Tm1Operations.HierarchySetElement, dimension, hierarchy, body), options);
  }
}

function withoutStructure(patch: HierarchyPatch): HierarchyPatch {
  // Bindings to the collections (Elements@odata.bind) are structure changes too
  if (patch.values().some(([key]) => key === "Elements" || key === "Edges" || key.startsWith("Elements@") || key.startsWith("Edges@"))) {
    throw new RangeError("A hierarchy PATCH with Elements or Edges replaces them, deleting what it doesn't list: use replaceStructure");
  }
  return patch;
}

function dimensionPath(dimension: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension"));
}

function hierarchiesPath(dimension: string): ODataPath {
  return dimensionPath(dimension).nav("Hierarchies");
}

function hierarchyPath(dimension: string, hierarchy: string): ODataPath {
  return hierarchiesPath(dimension).key(required(hierarchy, "hierarchy"));
}

function elementPath(dimension: string, hierarchy: string, element: string): ODataPath {
  return hierarchyPath(dimension, hierarchy).nav("Elements").key(required(element, "element"));
}

/** A bound action reading nothing, or, with `type`, its answer. */
function action(operation: Tm1Operation, dimension: string, hierarchy: string, body: Record<string, unknown>): Tm1Request<void>;
function action<T>(operation: Tm1Operation, dimension: string, hierarchy: string, body: Record<string, unknown>, type: Tm1Model<T>): Tm1Request<T>;
function action<T>(
  operation: Tm1Operation,
  dimension: string,
  hierarchy: string,
  body: Record<string, unknown>,
  type?: Tm1Model<T>,
): Tm1Request<T> | Tm1Request<void> {
  const path = hierarchyPath(dimension, hierarchy).action(operation.name);
  return (type ? Tm1Request.post(path, body, type) : Tm1Request.post(path, body)).requires(operation.capability());
}
