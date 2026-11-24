// SPDX-License-Identifier: Apache-2.0

import type { Tm1Model } from "../../model.js";
import { Dimension, type DimensionCreate, type DimensionPatch, Hierarchy, Tm1Operations } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const DIMENSIONS = ODataPath.of("Dimensions");

/** Dimensions: `/Dimensions` and the operations bound to a dimension. A dimension's hierarchies,
 * elements and subsets have services of their own.
 *
 * List dimensions with a query: here, the names of the model dimensions:
 * ```ts
 * tm1.dimensions.modelDimensions(ODataQuery.none().select(Dimension.Fields.Name))
 * ```
 * Create a dimension with its hierarchy, elements and edges in one request:
 * ```ts
 * await tm1.dimensions.create(DimensionCreate.builder().name("Region").hierarchies([
 *   HierarchyCreate.builder().name("Region")
 *     .elements([ElementCreate.builder().name("Total").type(ElementType.Consolidated).build(), ElementCreate.builder().name("North").build()])
 *     .edges([EdgePatch.builder().parentName("Total").componentName("North").weight(1).build()])
 *     .build(),
 * ]).build());
 * ```
 * Read a dimension with its hierarchies expanded:
 * ```ts
 * await tm1.dimensions.get("Region", ODataQuery.none().expand(Dimension.Fields.Hierarchies))
 * ```
 */
export class DimensionService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Dimensions"],
    modelDimensions: ["FunctionImport ModelDimensions"],
    controlDimensions: ["FunctionImport ControlDimensions"],
    get: ["EntitySet Dimensions"],
    create: ["EntitySet Dimensions"],
    update: ["EntitySet Dimensions"],
    delete: ["EntitySet Dimensions"],
    saveAs: ["Action Dimension/SaveAs"],
    addAllLeavesHierarchy: ["Action Dimension/AddAllLeavesHierarchy"],
    lock: ["Action Dimension/Lock"],
    unlock: ["Action Dimension/Unlock"],
    reserve: ["Action Dimension/Reserve"],
    release: ["Action Dimension/Release"],
  } as const satisfies Tm1Endpoints<DimensionService>;

  /** `GET Dimensions`: every dimension, control dimensions included, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Dimension> {
    return this.session.stream(Tm1Request.list(DIMENSIONS, query, Dimension), options);
  }

  /** `ModelDimensions()`: the dimensions that aren't control dimensions (whose names start with `}`), streamed. */
  modelDimensions(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Dimension> {
    return this.session.stream(Tm1Request.list(ODataPath.functionImport("ModelDimensions"), query, Dimension), options);
  }

  /** `ControlDimensions()`: the control dimensions, such as `}Clients`, streamed. */
  controlDimensions(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Dimension> {
    return this.session.stream(Tm1Request.list(ODataPath.functionImport("ControlDimensions"), query, Dimension), options);
  }

  /** `GET Dimensions('name')`: the dimension, or a `Tm1NotFoundException` if there's none. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Dimension> {
    return this.session.execute(Tm1Request.get(dimensionPath(name), query, Dimension), options);
  }

  /** `POST Dimensions`: creates a dimension, with its hierarchies, elements and edges if the patch
   * has them (a deep insert). It needs a name; returns the dimension as created. */
  create(dimension: DimensionCreate, options?: Tm1CallOptions): Promise<Dimension> {
    return this.session.execute(Tm1Request.post(DIMENSIONS, required(dimension, "dimension"), Dimension), options);
  }

  /** `PATCH Dimensions('name')`: changes the properties the patch sets. With `etag`
   * (`dimension["@odata.etag"]`, from a read), sent as `If-Match`: only if the dimension hasn't
   * changed since, else a `Tm1PreconditionFailedException`. TM1 (11.8) accepts `Hierarchies` and
   * `AllLeavesHierarchyName` in a dimension PATCH and ignores them: change hierarchies through their
   * own service, and add an all-leaves hierarchy with {@link addAllLeavesHierarchy}. */
  async update(name: string, patch: DimensionPatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(dimensionPath(name), required(patch, "patch"));
    await this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE Dimensions('name')`. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(dimensionPath(name)), options);
  }

  /** `tm1.SaveAs`: copies a dimension under a new name, failing if one exists; returns the copy. */
  saveAs(name: string, newName: string, options?: Tm1CallOptions): Promise<Dimension>;
  /** `tm1.SaveAs`: copies a dimension under a new name; `overwrite` replaces a dimension that already
   * has it, and `keepExistingAttributes`, when overwriting, keeps the attributes of what's replaced
   * (when not, true creates no attributes for the copy). Returns the copy. */
  saveAs(name: string, newName: string, overwrite: boolean, keepExistingAttributes: boolean, options?: Tm1CallOptions): Promise<Dimension>;
  saveAs(name: string, newName: string, overwrite?: boolean | Tm1CallOptions, keepExistingAttributes?: boolean, options?: Tm1CallOptions): Promise<Dimension> {
    if (typeof overwrite !== "boolean") {
      return this.session.execute(action(Tm1Operations.DimensionSaveAs, name, { Name: required(newName, "newName") }, Dimension), overwrite);
    }
    const body = { Name: newName, Overwrite: overwrite, KeepExistingAttributes: required(keepExistingAttributes, "keepExistingAttributes") };
    return this.session.execute(action(Tm1Operations.DimensionSaveAs, name, body, Dimension), options);
  }

  /** `tm1.AddAllLeavesHierarchy`: adds a hierarchy of all the dimension's leaves, named `Leaves` and
   * not visible, which makes it a multi-hierarchy dimension; returns the new hierarchy. */
  addAllLeavesHierarchy(name: string, options?: Tm1CallOptions): Promise<Hierarchy>;
  /** `tm1.AddAllLeavesHierarchy`: adds a hierarchy of all the dimension's leaves, named
   * `hierarchyName`, visible or not, which makes it a multi-hierarchy dimension. */
  addAllLeavesHierarchy(name: string, hierarchyName: string, visible: boolean, options?: Tm1CallOptions): Promise<Hierarchy>;
  addAllLeavesHierarchy(name: string, hierarchyName?: string | Tm1CallOptions, visible?: boolean, options?: Tm1CallOptions): Promise<Hierarchy> {
    if (typeof hierarchyName !== "string") {
      return this.session.execute(action(Tm1Operations.DimensionAddAllLeavesHierarchy, name, {}, Hierarchy), hierarchyName);
    }
    const body = { AllLeavesHierarchyName: hierarchyName, Visible: required(visible, "visible") };
    return this.session.execute(action(Tm1Operations.DimensionAddAllLeavesHierarchy, name, body, Hierarchy), options);
  }

  /** `tm1.Lock`: stops everyone else changing the dimension until it's unlocked. */
  lock(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.DimensionLock, name), options);
  }

  /** `tm1.Unlock`. */
  unlock(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.DimensionUnlock, name), options);
  }

  /** `tm1.Reserve`: stops everyone else changing the dimension until it's released. Needs schema 11.8.34. */
  reserve(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.DimensionReserve, name), options);
  }

  /** `tm1.Release`. Needs schema 11.8.34. */
  release(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.DimensionRelease, name), options);
  }
}

function dimensionPath(name: string): ODataPath {
  return DIMENSIONS.key(required(name, "name"));
}

/** A bound action reading nothing, or, with `type`, its answer. */
function action(operation: Tm1Operation, name: string): Tm1Request<void>;
function action<T>(operation: Tm1Operation, name: string, body: Record<string, unknown>, type: Tm1Model<T>): Tm1Request<T>;
function action<T>(operation: Tm1Operation, name: string, body: Record<string, unknown> = {}, type?: Tm1Model<T>): Tm1Request<T> | Tm1Request<void> {
  const path = dimensionPath(name).action(operation.name);
  return (type ? Tm1Request.post(path, body, type) : Tm1Request.post(path, body)).requires(operation.capability());
}
