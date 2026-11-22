import { Cellset, CellsetAxis, CellsetAxisTuple, CellsetCell, Cube, Hierarchy, Member, Tm1Operations, View, type CellsetUpdatePatch } from "../../generated/index.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Model } from "../../model.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { ViewScope } from "../cube/ViewScope.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { items, type Tm1Work } from "./ChangeSetService.js";

const CELLSETS = ODataPath.of("Cellsets");

/** How many pages {@link CellsetService.stream} fetches at once, beyond the first. */
const PAGES_IN_FLIGHT = 2;

/** Cellsets: the server-side results of an MDX query or a view, with their axes and cells.
 *
 * A cellset lives on the server until it's deleted, or the session ends; one that isn't deleted
 * holds the server's memory. The 1:1 methods ({@link execute}, {@link cells}, {@link partition},
 * {@link delete}) leave that to the caller. {@link stream} does it all: runs the query, pages the
 * cells in order, and deletes the cellset when the iteration completes, fails or is abandoned.
 *
 * Read the cells of an MDX query, placing each by its ordinal on the axes:
 * ```ts
 * const mdx = "SELECT {[Month].[Jan],[Month].[Feb]} ON 0, {[Region].[North],[Region].[South]} ON 1 FROM [Sales]";
 * for await (const cell of tm1.cellsets.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 500, (cellset, cells) => cells)) {
 *   console.log(cell.Ordinal, cell.Value);
 * }
 * ```
 * Write into the cells of a query by ordinal, deleting its cellset after:
 * ```ts
 * const cellset = await tm1.cellsets.execute(mdx);
 * try {
 *   await tm1.cellsets.update(cellset.ID!, 0, 42);
 * } finally {
 *   await tm1.cellsets.delete(cellset.ID!);
 * }
 * ```
 * Read the values of a saved view:
 * ```ts
 * const cellset = await tm1.cellsets.executeView("Sales", ViewScope.Public, "Default", ODataQuery.none().expand("Cells", (c) => c.select("Value")));
 * await tm1.cellsets.delete(cellset.ID!);
 * ```
 */
export class CellsetService extends Tm1Service {
  static readonly endpoints = {
    execute: ["ActionImport ExecuteMDX"],
    executeStatement: ["ActionImport ExecuteMDXStatement"],
    executeView: ["Action NativeView/Execute", "Action MDXView/Execute"],
    list: ["EntitySet Cellsets"],
    get: ["EntitySet Cellsets"],
    delete: ["EntitySet Cellsets"],
    axes: ["Navigation Cellset/Axes"],
    hierarchies: ["Navigation CellsetAxis/Hierarchies"],
    tuples: ["Navigation CellsetAxis/Tuples"],
    tupleMembers: ["Navigation CellsetAxisTuple/Members"],
    cells: ["Navigation Cellset/Cells"],
    cellMembers: ["Navigation CellsetCell/Members"],
    cube: ["Navigation Cellset/Cube"],
    partition: ["Function Cellset/GetPartition"],
    update: ["Action Cellset/Update"],
    updateCells: ["Action Cellset/UpdateCells", "Navigation CellsetUpdate/ReferenceCube", "Navigation CellsetUpdate/ReferenceCell"],
    saveViewAs: ["Action Cellset/SaveViewAs"],
    stream: ["ActionImport ExecuteMDX", "Function Cellset/GetPartition", "EntitySet Cellsets"],
  } as const satisfies Tm1Endpoints<CellsetService>;

  /** `ExecuteMDX`: runs an MDX query, leaving its cellset on the server until {@link delete}. Expand
   * `Axes` and `Cells` (with `$top` and `$select`) to get them in the same round trip. The cellset
   * has its `ID`. */
  execute(mdx: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cellset> {
    const operation = Tm1Operations.ExecuteMdx;
    return this.session.execute(Tm1Request.post(ODataPath.of(operation.importName!), { MDX: required(mdx, "mdx") }, Cellset).withQuery(query).requires(operation.capability()), options);
  }

  /** `ExecuteMDXStatement`: runs an MDX statement for its effect, keeping no cellset. Needs schema
   * 11.8.20. TM1 (11.8) runs a `SELECT` and returns nothing, and rejects `CREATE SET` as a syntax
   * error (code 501). */
  executeStatement(mdx: string, options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.ExecuteMdxStatement;
    return this.session.execute(Tm1Request.post(ODataPath.of(operation.importName!), { MDX: required(mdx, "mdx") }).requires(operation.capability()), options);
  }

  /** `tm1.Execute` on a native or MDX view: its cellset, left on the server until {@link delete}. */
  executeView(cube: string, scope: ViewScope, view: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cellset> {
    const path = ODataPath.of("Cubes")
      .key(required(cube, "cube"))
      .nav(ViewScope.navigation(required(scope, "scope")))
      .key(required(view, "view"))
      .action(Tm1Operations.NativeViewExecute.name);
    return this.session.execute(Tm1Request.post(path, {}, Cellset).withQuery(query), options);
  }

  /** `GET Cellsets`, streamed. TM1 (11.8) lists none, though each cellset reads by its ID with
   * {@link get}: keep the IDs of the cellsets you create. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Cellset> {
    return this.session.stream(Tm1Request.list(CELLSETS, query, Cellset), options);
  }

  /** `GET Cellsets('id')`: the cellset, or a `Tm1NotFoundException` if it's gone. */
  get(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cellset> {
    return this.session.execute(Tm1Request.get(cellset(id), query, Cellset), options);
  }

  /** `DELETE Cellsets('id')`: frees the cellset's memory on the server. */
  delete(id: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(cellset(id)), options);
  }

  /** `GET Cellsets('id')/Axes`, streamed: columns (0), rows (1), and so on, then the slicer. TM1
   * (11.8) adds the slicer even without a `WHERE`: it holds the cube's other dimensions, such as its
   * virtual `Sandboxes`, with cardinality 1. Expand `Tuples($expand=Members)` for the members. */
  axes(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<CellsetAxis> {
    return this.session.stream(Tm1Request.list(cellset(id).nav("Axes"), query, CellsetAxis), options);
  }

  /** `GET Cellsets('id')/Axes(n)/Hierarchies`, streamed. */
  hierarchies(id: string, axis: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Hierarchy> {
    return this.session.stream(Tm1Request.list(axisPath(id, axis).nav("Hierarchies"), query, Hierarchy), options);
  }

  /** `GET Cellsets('id')/Axes(n)/Tuples`, streamed, in order: an axis can be large. */
  tuples(id: string, axis: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<CellsetAxisTuple> {
    return this.session.stream(Tm1Request.list(axisPath(id, axis).nav("Tuples"), query, CellsetAxisTuple), options);
  }

  /** `GET Cellsets('id')/Axes(n)/Tuples(t)/Members`, streamed: one member per hierarchy on the axis. */
  tupleMembers(id: string, axis: number, tuple: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(axisPath(id, axis).nav("Tuples").key(required(tuple, "tuple")).nav("Members"), query, Member), options);
  }

  /** `GET Cellsets('id')/Cells`, streamed, in ordinal order. Use `$top` and `$skip`, or
   * {@link partition}, for pages. */
  cells(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<CellsetCell> {
    return this.session.stream(Tm1Request.list(cellset(id).nav("Cells"), query, CellsetCell), options);
  }

  /** `GET Cellsets('id')/Cells(n)/Members`, streamed: the members that place a cell, one per
   * hierarchy of the cube. TM1 (11.8) includes the virtual `Sandboxes` dimension's, `Base`, first. */
  cellMembers(id: string, ordinal: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(cellset(id).nav("Cells").key(required(ordinal, "ordinal")).nav("Members"), query, Member), options);
  }

  /** `GET Cellsets('id')/Cube`: the cube the cellset is from. */
  cube(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cube> {
    return this.session.execute(Tm1Request.get(cellset(id).nav("Cube"), query, Cube), options);
  }

  /** `tm1.GetPartition(Begin=b,End=e)`: a rectangle of cells, streamed. Needs schema 10.2.2.3.
   *
   * TM1 (11.8) reads `Begin` and `End` as the top-left and bottom-right cells of a rectangle on the
   * first two axes, not as a range of ordinals: with two columns, `partition(id, 2, 4)` is column 0
   * of rows 1 and 2, cells 2 and 4, not 3. A run of whole rows, from a row's first cell to a row's
   * last, is a range. Across pages, keep `query` the same. */
  partition(id: string, begin: number, end: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<CellsetCell> {
    const operation = Tm1Operations.CellsetGetPartition;
    const range = new Map<string, unknown>([
      ["Begin", required(begin, "begin")],
      ["End", required(end, "end")],
    ]);
    return this.session.stream(Tm1Request.list(cellset(id).function(operation.name, range), query, CellsetCell).requires(operation.capability()), options);
  }

  /** `tm1.Update`: writes one value to a cell of the cellset, by ordinal; null clears the cell. The
   * action takes a range (`BeginOrdinal`, `EndOrdinal`), but TM1 (11.8) writes only the first cell
   * of it, so this takes one ordinal; {@link updateCells} writes many. */
  update(id: string, ordinal: number, value: unknown, options?: Tm1CallOptions): Promise<void> {
    const body = { BeginOrdinal: required(ordinal, "ordinal"), EndOrdinal: ordinal, Value: value ?? null };
    return this.session.execute(cellsetAction(Tm1Operations.CellsetUpdate, id, body), options);
  }

  /** `tm1.UpdateCells`: writes many of the cellset's cells, each its own value, by ordinal:
   * `CellsetUpdatePatch.builder().ordinal(n).value(v)`, or with a reference cube and cell to copy
   * from. Needs schema 11.0.0. */
  updateCells(id: string, updates: readonly CellsetUpdatePatch[], options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(cellsetAction(Tm1Operations.CellsetUpdateCells, id, { Updates: [...required(updates, "updates")] }), options);
  }

  /** `tm1.SaveViewAs`: saves the cellset's query as a view of its cube: public, failing if one has
   * the name; or with `overwrite` (replacing a view that has the name) and `makePrivate`. Needs
   * schema 11.0.0. */
  saveViewAs(id: string, name: string, options?: Tm1CallOptions): Promise<View>;
  saveViewAs(id: string, name: string, overwrite: boolean, makePrivate: boolean, options?: Tm1CallOptions): Promise<View>;
  saveViewAs(id: string, name: string, overwrite?: boolean | Tm1CallOptions, makePrivate?: boolean, options?: Tm1CallOptions): Promise<View> {
    const body: Record<string, unknown> = { Name: required(name, "name") };
    if (typeof overwrite !== "boolean") return this.session.execute(cellsetAction(Tm1Operations.CellsetSaveViewAs, id, body, View), overwrite);
    if (typeof makePrivate !== "boolean") throw new TypeError("makePrivate is required");
    body["Overwrite"] = overwrite;
    body["MakePrivate"] = makePrivate;
    return this.session.execute(cellsetAction(Tm1Operations.CellsetSaveViewAs, id, body, View), options);
  }

  /** Runs an MDX query and streams its cells to `handler`, then deletes the cellset: when the
   * iteration completes, fails, or is abandoned (a `break`, an abort).
   *
   * One round trip brings the axes (hierarchies by name, tuples with their members' names and
   * unique names) and the first `pageSize` cells; the rest come in pages of `GetPartition`, a few at
   * a time and in ordinal order, as the cells are iterated, so memory stays bounded on both sides
   * however large the cellset.
   *
   * `query` selects what of each cell (include `Ordinal` to place them); `handler` is given the
   * cellset (its `ID` and expanded `Axes`) and its cells, in ordinal order, and what it gives (a
   * stream, or a promise of one value) is what this streams. Nothing is sent until it's iterated.
   */
  stream<R>(mdx: string, query: ODataQuery, pageSize: number, handler: (cellset: Cellset, cells: Tm1Stream<CellsetCell>) => Tm1Work<R>, options?: Tm1CallOptions): Tm1Stream<R> {
    if (!(pageSize >= 1) || !Number.isInteger(pageSize)) throw new RangeError("pageSize must be positive: " + pageSize);
    required(mdx, "mdx");
    required(query, "query");
    required(handler, "handler");
    const first = ODataQuery.none()
      .expand("Axes", (a) => a.expand("Hierarchies", (h) => h.select("Name")).expand("Tuples", (t) => t.expand("Members", (m) => m.select("Name", "UniqueName"))))
      .expand("Cells", () => query.top(pageSize));
    const self = this;
    return Tm1Stream.from(async function* () {
      const result = await self.execute(mdx, first, options);
      try {
        yield* items(handler(result, self.pages(result, query, pageSize, options)));
      } finally {
        // However it ended, and not under a signal that may be what ended it; a cellset TM1 sent
        // without an ID has nothing to delete
        if (result.ID != null) await self.delete(result.ID);
      }
    });
  }

  /** The cells of a streamed cellset: the first page, which came inline, then the rest.
   * `GetPartition` takes a rectangle, so with two axes the pages are whole rows: the first page's
   * last row, if it came incomplete, is dropped and fetched again with the next. With more axes,
   * rows aren't rectangles, so pages come from `Cells` with `$skip` and `$top`. */
  private pages(result: Cellset, cellQuery: ODataQuery, pageSize: number, options: Tm1CallOptions | undefined): Tm1Stream<CellsetCell> {
    const self = this;
    return Tm1Stream.from(async function* () {
      const inline = result.Cells ?? [];
      const axes = result.Axes ?? [];
      let total = 1;
      for (const axis of axes) total *= cardinality(axis);
      if (inline.length >= total) {
        yield* inline;
        return;
      }
      const id = required(result.ID, "cellset ID");
      const pages: ((signal: AbortSignal) => Tm1Stream<CellsetCell>)[] = [];
      let head: CellsetCell[];
      if (axes.slice(2).some((axis) => cardinality(axis) > 1)) {
        const from = inline.length;
        head = inline;
        for (let page = 0; page < Math.ceil((total - from) / pageSize); page++) {
          const skip = from + page * pageSize;
          pages.push((signal) => self.cells(id, cellQuery.skip(skip).top(pageSize), { signal }));
        }
      } else {
        const columns = axes.length === 0 ? 1 : cardinality(axes[0]!);
        const rowsPerPage = Math.max(1, Math.floor(pageSize / columns));
        const firstRow = Math.floor(inline.length / columns);
        const rows = total / columns;
        head = inline.slice(0, firstRow * columns);
        for (let page = 0; page < Math.ceil((rows - firstRow) / rowsPerPage); page++) {
          const top = firstRow + page * rowsPerPage;
          const bottom = Math.min(top + rowsPerPage, rows) - 1;
          pages.push((signal) => self.partition(id, top * columns, bottom * columns + columns - 1, cellQuery, { signal }));
        }
      }
      yield* head;

      // A few pages in flight, yielded in order; abandoned, they're stopped and waited for, so the
      // cellset isn't deleted under them
      const stop = new AbortController();
      const outer = options?.signal;
      const onAbort = (): void => stop.abort(outer?.reason);
      if (outer?.aborted) onAbort();
      else outer?.addEventListener("abort", onAbort, { once: true });
      const inFlight: Promise<CellsetCell[]>[] = [];
      let next = 0;
      const fill = (): void => {
        while (next < pages.length && inFlight.length < PAGES_IN_FLIGHT) {
          const fetching = pages[next++]!(stop.signal).toArray();
          fetching.catch(() => undefined);
          inFlight.push(fetching);
        }
      };
      try {
        fill();
        while (inFlight.length > 0) {
          const page = inFlight.shift()!;
          const cells = await page;
          fill();
          yield* cells;
        }
      } finally {
        stop.abort();
        outer?.removeEventListener("abort", onAbort);
        for (const pending of inFlight) await pending.catch(() => undefined);
      }
    });
  }
}

function cardinality(axis: CellsetAxis): number {
  return axis.Cardinality ?? 1;
}

function cellset(id: string): ODataPath {
  return CELLSETS.key(required(id, "id"));
}

function axisPath(id: string, axis: number): ODataPath {
  return cellset(id).nav("Axes").key(required(axis, "axis"));
}

function cellsetAction(operation: Tm1Operation, id: string, body: Record<string, unknown>): Tm1Request<void>;
function cellsetAction<T>(operation: Tm1Operation, id: string, body: Record<string, unknown>, type: Tm1Model<T>): Tm1Request<T>;
function cellsetAction<T>(operation: Tm1Operation, id: string, body: Record<string, unknown>, type?: Tm1Model<T>): Tm1Request<T> | Tm1Request<void> {
  const path = cellset(id).action(operation.name);
  return (type ? Tm1Request.post(path, body, type) : Tm1Request.post(path, body)).requires(operation.capability());
}
