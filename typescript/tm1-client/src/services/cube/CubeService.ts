// SPDX-License-Identifier: Apache-2.0

import { Primitive, type Tm1Model } from "../../model.js";
import {
  CalculationComponent,
  type CheckFeedersSelection,
  Cube,
  type CubeCreate,
  type CubePatch,
  Dimension,
  FedCellDescriptor,
  FeederTrace,
  RuleSyntaxError,
  Tm1Operations,
} from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { entriesOf, required, type Tm1Map } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const CUBES = ODataPath.of("Cubes");

/** Cubes: `/Cubes`, a cube's dimensions, and the operations bound to a cube. Views have a service of
 * their own; cell data is read and written through cellsets.
 *
 * List cubes with a query: here, the model cubes whose names start with "Sales":
 * ```ts
 * tm1.cubes.modelCubes(ODataQuery.none()
 *     .select(Cube.Fields.Name)
 *     .filter(Filter.startsWith(Cube.Fields.Name, "Sales"))
 *     .orderBy(Cube.Fields.Name))
 * ```
 * Create a cube over existing dimensions, bound in order:
 * ```ts
 * const dimensions = ODataPath.of("Dimensions");
 * await tm1.cubes.create(CubeCreate.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build());
 * ```
 * Change a cube's rules, checking them first (TM1 saves rules even when they don't compile):
 * ```ts
 * const rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
 * if ((await tm1.cubes.checkRules("Sales", rules).toArray()).length === 0) {
 *   await tm1.cubes.update("Sales", CubePatch.builder().rules(rules).build());
 * }
 * ```
 */
export class CubeService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Cubes"],
    modelCubes: ["FunctionImport ModelCubes"],
    controlCubes: ["FunctionImport ControlCubes"],
    get: ["EntitySet Cubes"],
    create: ["EntitySet Cubes"],
    update: ["EntitySet Cubes"],
    delete: ["EntitySet Cubes"],
    dimensions: ["Navigation Cube/Dimensions"],
    dimensionsStorageOrder: ["Function Cube/DimensionsStorageOrder"],
    reorderDimensions: ["Action Cube/ReorderDimensions"],
    load: ["Action Cube/Load"],
    unload: ["Action Cube/Unload"],
    lock: ["Action Cube/Lock"],
    unlock: ["Action Cube/Unlock"],
    reserve: ["Action Cube/Reserve"],
    release: ["Action Cube/Release"],
    checkRules: ["Action Cube/CheckRules"],
    traceCellCalculation: ["Action Cube/TraceCellCalculation", "Navigation CalculationComponent/Cube", "Navigation CalculationComponent/Tuple"],
    traceFeeders: ["Action Cube/TraceFeeders", "Navigation FedCellDescriptor/Cube", "Navigation FedCellDescriptor/Tuple"],
    checkFeeders: ["Action Cube/CheckFeeders", "Navigation FedCellDescriptor/Cube", "Navigation FedCellDescriptor/Tuple"],
  } as const satisfies Tm1Endpoints<CubeService>;

  /** `GET Cubes`: every cube, control cubes included, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Cube> {
    return this.session.stream(Tm1Request.list(CUBES, query, Cube), options);
  }

  /** `ModelCubes()`: the cubes that aren't control cubes (whose names start with `}`), streamed. */
  modelCubes(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Cube> {
    return this.session.stream(Tm1Request.list(ODataPath.functionImport("ModelCubes"), query, Cube), options);
  }

  /** `ControlCubes()`: the control cubes, such as `}ClientGroups`, streamed. */
  controlCubes(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Cube> {
    return this.session.stream(Tm1Request.list(ODataPath.functionImport("ControlCubes"), query, Cube), options);
  }

  /** `GET Cubes('name')`: the cube, or a `Tm1NotFoundException` if there's none. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cube> {
    return this.session.execute(Tm1Request.get(cubePath(name), query, Cube), options);
  }

  /** `POST Cubes`: creates a cube over existing dimensions, bound in order with
   * `CubeCreate.builder().bindDimensions(…)`, and optionally its rules. It needs a name and at least
   * two dimensions; returns the cube as created. */
  create(cube: CubeCreate, options?: Tm1CallOptions): Promise<Cube> {
    return this.session.execute(Tm1Request.post(CUBES, required(cube, "cube"), Cube), options);
  }

  /** `PATCH Cubes('name')`: changes the properties the patch sets, typically `Rules` or
   * `DrillthroughRules`; null clears them. With `etag` (`cube["@odata.etag"]`, from a read), sent as
   * `If-Match`: only if the cube hasn't changed since, else a `Tm1PreconditionFailedException`. TM1
   * (11.8) saves rules even when they don't compile: check them first with {@link checkRules}. */
  async update(name: string, patch: CubePatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(cubePath(name), required(patch, "patch"));
    await this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE Cubes('name')`. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(cubePath(name)), options);
  }

  /** `GET Cubes('name')/Dimensions`: the cube's dimensions, in the cube's order. TM1 (11.8) lists
   * the virtual `Sandboxes` dimension first. */
  dimensions(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Dimension> {
    return this.session.stream(Tm1Request.list(cubePath(name).nav("Dimensions"), query, Dimension), options);
  }

  /** `tm1.DimensionsStorageOrder()`: the dimensions in the order TM1 stores the cube's cells. */
  dimensionsStorageOrder(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Dimension> {
    const operation = Tm1Operations.CubeDimensionsStorageOrder;
    return this.session.stream(Tm1Request.list(cubePath(name).function(operation.name), query, Dimension).requires(operation.capability()), options);
  }

  /** `tm1.ReorderDimensions`: changes the order TM1 stores the cube's cells in, and returns the
   * change in memory use, as a percentage. */
  async reorderDimensions(name: string, dimensions: readonly string[], options?: Tm1CallOptions): Promise<number> {
    const bindings = required(dimensions, "dimensions").map((d) => ODataPath.of("Dimensions").key(d).toString());
    const result = await this.session.execute(
      action(Tm1Operations.CubeReorderDimensions, name, { "Dimensions@odata.bind": bindings }, Primitive.number),
      options,
    );
    return typeof result === "number" ? result : 0;
  }

  /** `tm1.Load`: loads the cube into memory. */
  load(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeLoad, name), options);
  }

  /** `tm1.Unload`: unloads the cube from memory. */
  unload(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeUnload, name), options);
  }

  /** `tm1.Lock`. */
  lock(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeLock, name), options);
  }

  /** `tm1.Unlock`. */
  unlock(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeUnlock, name), options);
  }

  /** `tm1.Reserve`: no one else can change the cube until it's released. */
  reserve(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeReserve, name), options);
  }

  /** `tm1.Release`. */
  release(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.CubeRelease, name), options);
  }

  /** `tm1.CheckRules`: the syntax errors in the cube's rules, or in `rules` if given (checked as if
   * they were the cube's, without saving them); none if they compile. */
  checkRules(name: string, rules?: string | null, options?: Tm1CallOptions): Tm1Stream<RuleSyntaxError> {
    const operation = Tm1Operations.CubeCheckRules;
    const body = rules == null ? {} : { Rules: rules };
    return this.session.stream(Tm1Request.postList(cubePath(name).action(operation.name), body, RuleSyntaxError).requires(operation.capability()), options);
  }

  /** `tm1.TraceCellCalculation`: how a cell's value is calculated, component by component; `tuple`
   * maps each dimension to an element. */
  traceCellCalculation(name: string, tuple: Tm1Map<string>, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<CalculationComponent> {
    return this.session.execute(
      action(Tm1Operations.CubeTraceCellCalculation, name, { "Tuple@odata.bind": bindings(tuple) }, CalculationComponent).withQuery(query),
      options,
    );
  }

  /** `tm1.TraceFeeders`: what a cell feeds. TM1 (11.8) needs a path to expand into a complex
   * property (`FedCells/Tuple`); nested options (`FedCells($expand=Tuple)`) get 400. */
  traceFeeders(name: string, tuple: Tm1Map<string>, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<FeederTrace> {
    return this.session.execute(action(Tm1Operations.CubeTraceFeeders, name, { "Tuple@odata.bind": bindings(tuple) }, FeederTrace).withQuery(query), options);
  }

  /** `tm1.CheckFeeders`: the cells under a consolidated cell that aren't fed though they have
   * values, chosen by `selection`. */
  checkFeeders(
    name: string,
    tuple: Tm1Map<string>,
    selection: CheckFeedersSelection,
    query?: ODataQuery | null,
    options?: Tm1CallOptions,
  ): Tm1Stream<FedCellDescriptor> {
    const operation = Tm1Operations.CubeCheckFeeders;
    const body = { "Tuple@odata.bind": bindings(tuple), Selection: required(selection, "selection") };
    return this.session.stream(
      Tm1Request.postList(cubePath(name).action(operation.name), body, FedCellDescriptor).withQuery(query).requires(operation.capability()),
      options,
    );
  }
}

function bindings(tuple: Tm1Map<string>): string[] {
  return entriesOf(required(tuple, "tuple")).map(([dimension, element]) =>
    ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements").key(element).toString(),
  );
}

function cubePath(name: string): ODataPath {
  return CUBES.key(required(name, "name"));
}

/** A bound action reading nothing, or, with `type`, its answer. */
function action(operation: Tm1Operation, name: string): Tm1Request<void>;
function action<T>(operation: Tm1Operation, name: string, body: Record<string, unknown>, type: Tm1Model<T>): Tm1Request<T>;
function action<T>(operation: Tm1Operation, name: string, body: Record<string, unknown> = {}, type?: Tm1Model<T>): Tm1Request<T> | Tm1Request<void> {
  const path = cubePath(name).action(operation.name);
  return (type ? Tm1Request.post(path, body, type) : Tm1Request.post(path, body)).requires(operation.capability());
}
