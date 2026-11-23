import { Element, Hierarchy, Subset, Tm1Operations, type SubsetPatch } from "../../generated/index.js";
import type { Tm1Model } from "../../model.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { SubsetScope } from "./SubsetScope.js";

const SUBSETS = ["Navigation Hierarchy/Subsets", "Navigation Hierarchy/PrivateSubsets", "Navigation Hierarchy/SessionSubsets"] as const;

/** Subsets: a hierarchy's public, private and session subsets ({@link SubsetScope}), their elements,
 * and the operations bound to a subset or creating a session subset.
 *
 * A static subset lists its elements; a dynamic one has an MDX `Expression`. Binding `Elements` in a
 * subset PATCH *appends* to them, so {@link update} refuses it: {@link addElements} appends by name
 * and {@link clearElements} empties the list.
 *
 * Create a static subset, binding its elements:
 * ```ts
 * const elements = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements");
 * await tm1.subsets.create("Region", "Region", SubsetScope.Public,
 *     SubsetPatch.builder().name("West Coast").bindElements(elements.key("California"), elements.key("Oregon")).build());
 * ```
 * Create a dynamic subset from an MDX expression:
 * ```ts
 * await tm1.subsets.create("Region", "Region", SubsetScope.Public,
 *     SubsetPatch.builder().name("Leaves").expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}").build());
 * ```
 * Replace a static subset's elements:
 * ```ts
 * await tm1.subsets.clearElements("Region", "Region", SubsetScope.Public, "West Coast");
 * await tm1.subsets.addElements("Region", "Region", SubsetScope.Public, "West Coast", ["Washington", "Oregon"]);
 * ```
 * Create a session subset and read its elements by the name TM1 gave it:
 * ```ts
 * const subset = await tm1.subsets.createSessionSubset("Region", "Region", SubsetPatch.builder().expression("{[Region].[Region].[West]}").build());
 * const elements = await tm1.subsets.elements("Region", "Region", SubsetScope.Session, subset.Name!).toArray();
 * ```
 */
export class SubsetService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: SUBSETS,
    get: SUBSETS,
    create: SUBSETS,
    update: SUBSETS,
    addElements: SUBSETS,
    clearElements: ["Navigation Subset/Elements"],
    delete: SUBSETS,
    elements: ["Navigation Subset/Elements"],
    hierarchy: ["Navigation Subset/Hierarchy"],
    saveAs: ["Action Subset/SaveAs"],
    setElement: ["Action Subset/SetElement"],
    setElementBefore: ["Action Subset/SetElement"],
    setElementAfter: ["Action Subset/SetElement"],
    createSessionSubset: ["Action Hierarchy/CreateSessionSubset"],
    createSessionSubsetFrom: ["Action Hierarchy/CreateSessionSubset"],
  } as const satisfies Tm1Endpoints<SubsetService>;

  /** `GET …/Hierarchies('h')/Subsets` (or the private or session ones), streamed. TM1 (11.8) lists no
   * session subsets, though each reads by the name {@link createSessionSubset} returned. */
  list(dimension: string, hierarchy: string, scope: SubsetScope, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Subset> {
    return this.session.stream(Tm1Request.list(subsetsPath(dimension, hierarchy, scope), query, Subset), options);
  }

  /** `GET …/Subsets('s')`: the subset, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Subset> {
    return this.session.execute(Tm1Request.get(subsetPath(dimension, hierarchy, scope, subset), query, Subset), options);
  }

  /** `POST …/Subsets`: creates a subset: static with `Elements@odata.bind`
   * (`SubsetPatch.builder().bindElements(…)`), dynamic with an `Expression`. It needs a name; returns
   * the subset as created. */
  create(dimension: string, hierarchy: string, scope: SubsetScope, subset: SubsetPatch, options?: Tm1CallOptions): Promise<Subset> {
    return this.session.execute(Tm1Request.post(subsetsPath(dimension, hierarchy, scope), required(subset, "subset"), Subset), options);
  }

  /** `PATCH …/Subsets('s')`: changes the properties the patch sets, such as a dynamic subset's
   * expression. With `etag` (`subset["@odata.etag"]`, from a read), sent as `If-Match`: only if the
   * subset hasn't changed since, else a `Tm1PreconditionFailedException`. Throws a `RangeError`, at
   * once and sending nothing, if the patch sets or binds `Elements`, which TM1 appends: use
   * {@link addElements} or {@link clearElements}. */
  update(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    patch: SubsetPatch,
    etag?: string | null,
    options?: Tm1CallOptions,
  ): Promise<void> {
    const request = Tm1Request.patch(subsetPath(dimension, hierarchy, scope, subset), withoutElements(patch));
    return this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `PATCH …/Subsets('s')` binding `Elements`: appends elements to a static subset, in order. */
  addElements(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, elements: readonly string[], options?: Tm1CallOptions): Promise<void> {
    const bindings = required(elements, "elements").map((e) => elementPath(dimension, hierarchy, e).toString());
    return this.session.execute(Tm1Request.patch(subsetPath(dimension, hierarchy, scope, subset), { "Elements@odata.bind": bindings }), options);
  }

  /** `DELETE …/Subsets('s')/Elements/$ref`: removes every element from a static subset; the elements
   * stay in the hierarchy. */
  clearElements(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(subsetPath(dimension, hierarchy, scope, subset).nav("Elements").ref()), options);
  }

  /** `DELETE …/Subsets('s')`. */
  delete(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(subsetPath(dimension, hierarchy, scope, subset)), options);
  }

  /** `GET …/Subsets('s')/Elements`, streamed: a static subset's elements in order, or what a dynamic
   * one's expression gives now. */
  elements(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Element> {
    return this.session.stream(Tm1Request.list(subsetPath(dimension, hierarchy, scope, subset).nav("Elements"), query, Element), options);
  }

  /** `GET …/Subsets('s')/Hierarchy`: the subset's hierarchy. */
  hierarchy(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(subsetPath(dimension, hierarchy, scope, subset).nav("Hierarchy"), query, Hierarchy), options);
  }

  /** `tm1.SaveAs`: copies a subset under a new name. Without `makePrivate` and `makeStatic` the copy
   * is public and keeps whether it's static or dynamic; with them, it's private if `makePrivate`, and
   * static, holding the elements the subset has now, if `makeStatic`. Returns the copy. */
  saveAs(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, newName: string, options?: Tm1CallOptions): Promise<Subset>;
  /** `tm1.SaveAs`, choosing whether the copy is private (`makePrivate`) and whether it's static,
   * holding the elements the subset has now (`makeStatic`). Returns the copy. */
  saveAs(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    newName: string,
    makePrivate: boolean,
    makeStatic: boolean,
    options?: Tm1CallOptions,
  ): Promise<Subset>;
  saveAs(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    newName: string,
    makePrivate?: boolean | Tm1CallOptions,
    makeStatic?: boolean,
    options?: Tm1CallOptions,
  ): Promise<Subset> {
    const path = subsetPath(dimension, hierarchy, scope, subset);
    if (typeof makePrivate !== "boolean") {
      return this.session.execute(action(Tm1Operations.SubsetSaveAs, path, { Name: newName }, Subset), makePrivate);
    }
    const body = { Name: newName, MakePrivate: makePrivate, MakeStatic: required(makeStatic, "makeStatic") };
    return this.session.execute(action(Tm1Operations.SubsetSaveAs, path, body, Subset), options);
  }

  /** `tm1.SetElement`: adds an element to a static subset, last, or moves it there if it's in it. */
  setElement(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, element: string, options?: Tm1CallOptions): Promise<void> {
    return this.sendSetElement(dimension, hierarchy, scope, subset, element, undefined, undefined, options);
  }

  /** `tm1.SetElement` with `Before`: places an element just before another in a static subset. */
  setElementBefore(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    element: string,
    before: string,
    options?: Tm1CallOptions,
  ): Promise<void> {
    return this.sendSetElement(dimension, hierarchy, scope, subset, element, "Before@odata.bind", required(before, "before"), options);
  }

  /** `tm1.SetElement` with `After`: places an element just after another in a static subset. */
  setElementAfter(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    element: string,
    after: string,
    options?: Tm1CallOptions,
  ): Promise<void> {
    return this.sendSetElement(dimension, hierarchy, scope, subset, element, "After@odata.bind", required(after, "after"), options);
  }

  /** `tm1.CreateSessionSubset` with a definition (an `Expression`, or bound `Elements`): creates a
   * subset visible only to this session. TM1 (11.8) ignores the definition's `Name` and names it
   * itself; the result has the name to use. */
  createSessionSubset(dimension: string, hierarchy: string, definition: SubsetPatch, options?: Tm1CallOptions): Promise<Subset> {
    const body = { Subset: required(definition, "definition") };
    return this.session.execute(action(Tm1Operations.HierarchyCreateSessionSubset, hierarchyPath(dimension, hierarchy), body, Subset), options);
  }

  /** `tm1.CreateSessionSubset` with a reference: copies a subset into one visible only to this
   * session; the result has the name TM1 gave it. */
  createSessionSubsetFrom(dimension: string, hierarchy: string, scope: SubsetScope, subset: string, options?: Tm1CallOptions): Promise<Subset> {
    const body = { "Subset@odata.bind": subsetPath(dimension, hierarchy, scope, subset).toString() };
    return this.session.execute(action(Tm1Operations.HierarchyCreateSessionSubset, hierarchyPath(dimension, hierarchy), body, Subset), options);
  }

  private sendSetElement(
    dimension: string,
    hierarchy: string,
    scope: SubsetScope,
    subset: string,
    element: string,
    position: string | undefined,
    other: string | undefined,
    options: Tm1CallOptions | undefined,
  ): Promise<void> {
    const body: Record<string, unknown> = { "Element@odata.bind": elementPath(dimension, hierarchy, element).toString() };
    if (position !== undefined && other !== undefined) body[position] = elementPath(dimension, hierarchy, other).toString();
    const operation = Tm1Operations.SubsetSetElement;
    return this.session.execute(
      Tm1Request.post(subsetPath(dimension, hierarchy, scope, subset).action(operation.name), body).requires(operation.capability()),
      options,
    );
  }
}

function withoutElements(patch: SubsetPatch): SubsetPatch {
  if (
    required(patch, "patch")
      .values()
      .some(([key]) => key === "Elements" || key.startsWith("Elements@"))
  ) {
    throw new RangeError("A subset PATCH with Elements appends to them: use addElements, or clearElements first");
  }
  return patch;
}

function hierarchyPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy"));
}

function subsetsPath(dimension: string, hierarchy: string, scope: SubsetScope): ODataPath {
  return hierarchyPath(dimension, hierarchy).nav(SubsetScope.navigation(required(scope, "scope")));
}

function subsetPath(dimension: string, hierarchy: string, scope: SubsetScope, subset: string): ODataPath {
  return subsetsPath(dimension, hierarchy, scope).key(required(subset, "subset"));
}

function elementPath(dimension: string, hierarchy: string, element: string): ODataPath {
  return hierarchyPath(dimension, hierarchy).nav("Elements").key(required(element, "element"));
}

function action<T>(operation: Tm1Operation, target: ODataPath, body: Record<string, unknown>, type: Tm1Model<T>): Tm1Request<T> {
  return Tm1Request.post(target.action(operation.name), body, type).requires(operation.capability());
}
