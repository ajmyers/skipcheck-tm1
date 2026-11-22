import { Cellset, Drillthrough, Rowset, RowsetRow, Tm1Operations, type ProcessParameterPatch } from "../../generated/index.js";
import { Json } from "../../model.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const ROWSETS = ODataPath.of("Rowsets");

/** Drillthrough: from a cell to the detail behind it, through a drillthrough process (`}Drill_…`).
 * A cube drillthrough returns a cellset, of a view the process returns; a relational one returns
 * rows from an ODBC source, directly or kept as a `Rowset`.
 *
 * A cell's drillthroughs come from the cube's `DrillthroughRules`, which name the process;
 * {@link scripts} lists them for a cell of a cellset. TM1 (11.8) passes such a process the cube's
 * name and the cell's element on each of its dimensions, so the process must declare that many
 * parameters (the cube's name, then one per dimension, in order); otherwise running the cell's
 * drillthrough fails with "process parameter count error" (code 248). Run directly
 * ({@link executeProcess}), it takes the parameters given. The returned cellsets and rowsets stay on
 * the server until deleted, like any cellset.
 *
 * `$metadata` declares a relational row (`DrillthroughRow`) with no properties, so relational rows
 * come as TM1's JSON. A rowset's rows (`RowsetRow`) are open, and keep their columns as properties.
 *
 * Drill from a cell of a query to the detail behind it:
 * ```ts
 * const [script] = await tm1.drillthrough.scripts(cellset.ID!, 0).toArray();
 * const detail = await tm1.drillthrough.execute(cellset.ID!, 0, script!.Name!, ODataQuery.none().expand("Cells", (c) => c.select("Value")));
 * await tm1.cellsets.delete(detail.ID!);
 * ```
 */
export class DrillthroughService extends Tm1Service {
  static readonly endpoints = {
    scripts: ["Navigation CellsetCell/DrillthroughScripts"],
    execute: ["Action CubeDrillthrough/Execute"],
    executeRelational: ["Action RelationalDrillthrough/Execute"],
    executeRelationalWithRowset: ["Action RelationalDrillthrough/ExecuteWithRowset"],
    executeProcess: ["ActionImport ExecuteCubeDrillthrough"],
    executeRelationalProcess: ["ActionImport ExecuteRelationalDrillthrough"],
    executeRelationalProcessWithRowset: ["ActionImport ExecuteRelationalDrillthroughWithRowset"],
    rowset: ["EntitySet Rowsets"],
    rows: ["Navigation Rowset/Rows"],
    deleteRowset: ["EntitySet Rowsets"],
  } as const satisfies Tm1Endpoints<DrillthroughService>;

  /** `GET Cellsets('id')/Cells(n)/DrillthroughScripts`: the drillthroughs a cell has, streamed, each
   * a `CubeDrillthrough` or `RelationalDrillthrough` (`typeOf(Drillthrough, d)`). */
  scripts(cellset: string, ordinal: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Drillthrough> {
    return this.session.stream(Tm1Request.list(scriptsPath(cellset, ordinal), query, Drillthrough), options);
  }

  /** `tm1.Execute` on a cell's cube drillthrough: the cellset of the view its process returns, left
   * on the server until deleted. Needs schema 10.2.2.6. The process must take the cube's name and
   * one parameter per dimension (see the class description). */
  execute(cellset: string, ordinal: number, drillthrough: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cellset> {
    const operation = Tm1Operations.CubeDrillthroughExecute;
    return this.session.execute(Tm1Request.post(script(cellset, ordinal, drillthrough).action(operation.name), {}, Cellset).withQuery(query).requires(operation.capability()), options);
  }

  /** `tm1.Execute` on a cell's relational drillthrough: its rows, as JSON, streamed. Needs schema 10.2.2.6. */
  executeRelational(cellset: string, ordinal: number, drillthrough: string, options?: Tm1CallOptions): Tm1Stream<unknown> {
    const operation = Tm1Operations.RelationalDrillthroughExecute;
    return this.session.stream(Tm1Request.postList(script(cellset, ordinal, drillthrough).action(operation.name), {}, Json).requires(operation.capability()), options);
  }

  /** `tm1.ExecuteWithRowset` on a cell's relational drillthrough: its rows, kept as a rowset to read
   * with {@link rows}. Needs schema 11.6.0. */
  executeRelationalWithRowset(cellset: string, ordinal: number, drillthrough: string, options?: Tm1CallOptions): Promise<Rowset> {
    const operation = Tm1Operations.RelationalDrillthroughExecuteWithRowset;
    return this.session.execute(Tm1Request.post(script(cellset, ordinal, drillthrough).action(operation.name), {}, Rowset).requires(operation.capability()), options);
  }

  /** `ExecuteCubeDrillthrough`: runs a cube drillthrough process (such as `}Drill_Sales`) directly,
   * with its parameters, and returns the cellset of the view it returns, left on the server until
   * deleted. Needs schema 11.2.0. */
  executeProcess(process: string, parameters: readonly ProcessParameterPatch[], query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cellset> {
    const operation = Tm1Operations.ExecuteCubeDrillthrough;
    return this.session.execute(Tm1Request.post(root(operation), processBody(process, parameters), Cellset).withQuery(query).requires(operation.capability()), options);
  }

  /** `ExecuteRelationalDrillthrough`: runs a relational drillthrough process directly; its rows, as
   * JSON, streamed. Needs schema 11.2.0. */
  executeRelationalProcess(process: string, parameters: readonly ProcessParameterPatch[], options?: Tm1CallOptions): Tm1Stream<unknown> {
    const operation = Tm1Operations.ExecuteRelationalDrillthrough;
    return this.session.stream(Tm1Request.postList(root(operation), processBody(process, parameters), Json).requires(operation.capability()), options);
  }

  /** `ExecuteRelationalDrillthroughWithRowset`: runs a relational drillthrough process directly,
   * keeping its rows as a rowset. Needs schema 11.6.0. */
  executeRelationalProcessWithRowset(process: string, parameters: readonly ProcessParameterPatch[], options?: Tm1CallOptions): Promise<Rowset> {
    const operation = Tm1Operations.ExecuteRelationalDrillthroughWithRowset;
    return this.session.execute(Tm1Request.post(root(operation), processBody(process, parameters), Rowset).requires(operation.capability()), options);
  }

  /** `GET Rowsets('id')`. */
  rowset(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Rowset> {
    return this.session.execute(Tm1Request.get(rowsetPath(id), query, Rowset), options);
  }

  /** `GET Rowsets('id')/Rows`, streamed: each row's columns are its open properties. Page with
   * `$top` and `$skip`. */
  rows(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<RowsetRow> {
    return this.session.stream(Tm1Request.list(rowsetPath(id).nav("Rows"), query, RowsetRow), options);
  }

  /** `DELETE Rowsets('id')`: frees the rowset on the server. */
  deleteRowset(id: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(rowsetPath(id)), options);
  }
}

function processBody(process: string, parameters: readonly ProcessParameterPatch[]): Record<string, unknown> {
  const body: Record<string, unknown> = { "DrillthroughProcess@odata.bind": ODataPath.of("Processes").key(required(process, "process")).toString() };
  if (required(parameters, "parameters").length > 0) body["Parameters"] = [...parameters];
  return body;
}

function root(operation: Tm1Operation): ODataPath {
  return ODataPath.of(operation.importName!);
}

function rowsetPath(id: string): ODataPath {
  return ROWSETS.key(required(id, "id"));
}

function scriptsPath(cellset: string, ordinal: number): ODataPath {
  return ODataPath.of("Cellsets").key(required(cellset, "cellset")).nav("Cells").key(required(ordinal, "ordinal")).nav("DrillthroughScripts");
}

function script(cellset: string, ordinal: number, drillthrough: string): ODataPath {
  return scriptsPath(cellset, ordinal).key(required(drillthrough, "drillthrough"));
}
