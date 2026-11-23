import { Tm1Operations, type CubeUpdatePatch, type UpdateOrder } from "../../generated/index.js";
import { ODataPath } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { required, type Tm1Map } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { Tuples } from "./Tuples.js";

const UPDATE_CELLS = [
  "Action Cube/UpdateCells",
  "Navigation CubeUpdate/Tuple",
  "Navigation CubeUpdate/ReferenceCube",
  "Navigation CubeUpdate/ReferenceCell",
] as const;

/** Cell writes to a cube, by coordinates. Cells are given as {@link Tuples.of} maps of dimension to
 * element; reading is through cellsets (`CellsetService`), and writing into one by ordinal is there
 * too.
 *
 * Values are numbers for numeric cells and text for string cells; `null` clears a cell. TM1 (11.8)
 * refuses a write to a consolidated cell (400, `CubeCellWriteStatusElementIsConsolidated`): it
 * doesn't spread; write the leaves.
 *
 * `tm1.Update` takes several cells, but TM1 (11.8) writes only the first, so {@link update} takes
 * one; {@link updateCells} writes many in one request.
 *
 * Write one cell, given an element for every dimension of the cube, in order:
 * ```ts
 * await tm1.cells.update("Sales", new Map([["Region", "North"], ["Measure", "Amount"]]), 42);
 * ```
 * Write many cells in one request:
 * ```ts
 * await tm1.cells.updateCells("Sales", [
 *   CubeUpdatePatch.builder().bindTuple(...Tuples.of({ Region: "North", Measure: "Amount" })).value(1).build(),
 *   CubeUpdatePatch.builder().bindTuple(...Tuples.of({ Region: "South", Measure: "Amount" })).value(2).build(),
 * ]);
 * ```
 */
export class CellService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    update: ["Action Cube/Update", "Navigation CellDescriptor/Tuple"],
    updateCells: UPDATE_CELLS,
  } as const satisfies Tm1Endpoints<CellService>;

  /** `tm1.Update`: writes one value to a cell; `tuple` maps dimension to element, as for
   * {@link Tuples.of}, and a null `value` clears the cell. */
  update(cube: string, tuple: Tm1Map<string>, value: unknown, options?: Tm1CallOptions): Promise<void> {
    const body = { Cells: [{ "Tuple@odata.bind": Tuples.of(tuple).map((p) => p.toString()) }], Value: value ?? null };
    return this.session.execute(action(Tm1Operations.CubeUpdate, cube, body), options);
  }

  /** `tm1.UpdateCells`: writes many cells, each its own value, in one request: each update is
   * `CubeUpdatePatch.builder().bindTuple(...Tuples.of(cell)).value(v)`, or with a reference cube and
   * cell to copy from. With `order`, `ByLevel` writes leaves before the consolidations above them, so
   * a spread to a consolidation isn't overwritten. Needs schema 11.0.0. TM1 (11.8) requires a
   * `Tuple` and a `Value` in every update (400 otherwise); on a leaf a `ReferenceCube` and
   * `ReferenceCell` didn't change what's written, and they don't copy that cell's value. */
  updateCells(cube: string, updates: readonly CubeUpdatePatch[], options?: Tm1CallOptions): Promise<void>;
  /** `tm1.UpdateCells` with an order: `ByLevel` writes leaves before the consolidations above them,
   * so a spread to a consolidation isn't overwritten. TM1 (11.8) requires a `Tuple` and a `Value`
   * in every update (400 otherwise). */
  updateCells(cube: string, updates: readonly CubeUpdatePatch[], order: UpdateOrder, options?: Tm1CallOptions): Promise<void>;
  updateCells(cube: string, updates: readonly CubeUpdatePatch[], order?: UpdateOrder | Tm1CallOptions, options?: Tm1CallOptions): Promise<void> {
    const body: Record<string, unknown> = { Updates: [...required(updates, "updates")] };
    if (typeof order !== "string") return this.session.execute(action(Tm1Operations.CubeUpdateCells, cube, body), order);
    // "Unknown" stands for a value this client doesn't know, and isn't sent
    if (order === "Unknown") throw new TypeError("order is required: Unknown isn't sent");
    body["Order"] = order;
    return this.session.execute(action(Tm1Operations.CubeUpdateCells, cube, body), options);
  }
}

function action(operation: Tm1Operation, cube: string, body: Record<string, unknown>): Tm1Request<void> {
  return Tm1Request.post(ODataPath.of("Cubes").key(required(cube, "cube")).action(operation.name), body).requires(operation.capability());
}
