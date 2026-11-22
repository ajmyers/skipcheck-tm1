import { describe, expect, it } from "vitest";
import { CellsetUpdatePatch, ODataQuery, ViewScope, type Cellset, type CellsetCell, type Tm1Stream } from "../../src/index.js";
import { FakeTm1, type Recorded } from "../support/fake-tm1.js";

const EXPAND = "$expand=Axes($expand=Hierarchies($select=Name),Tuples($expand=Members($select=Name,UniqueName))),Cells($select=Ordinal,Value;$top=";

const cells = (): ODataQuery => ODataQuery.none().select("Ordinal", "Value");

const ordinals = (_: Cellset, all: Tm1Stream<CellsetCell>): Tm1Stream<number> => all.map((c) => c.Ordinal!);

function range(from: number, to: number): { Ordinal: number; Value: number }[] {
  const out = [];
  for (let i = from; i <= to; i++) out.push({ Ordinal: i, Value: i * 10 });
  return out;
}

/** A fake cellset of `total` cells, on axes of these cardinalities, whose first `inline` cells come
 * with it; its pages answer by their range. */
function cellset(total: number, cardinalities: number[], inline: number): FakeTm1 {
  const tm1 = new FakeTm1();
  tm1.respond = (r: Recorded) => {
    if (r.method === "DELETE") return null;
    if (r.target.startsWith("ExecuteMDX")) {
      return { ID: "c1", Axes: cardinalities.map((c, i) => ({ Ordinal: i, Cardinality: c })), Cells: range(0, Math.min(inline, total) - 1) };
    }
    const p = /Begin=(\d+),End=(\d+)/.exec(r.target);
    if (p) return FakeTm1.collection(...range(Number(p[1]), Number(p[2])));
    const skip = Number(/\$skip=(\d+)/.exec(r.target)![1]);
    const top = Number(/\$top=(\d+)/.exec(r.target)![1]);
    return FakeTm1.collection(...range(skip, Math.min(skip + top, total) - 1));
  };
  return tm1;
}

const targets = (tm1: FakeTm1): string[] => tm1.requests.map((r) => (r.method === "DELETE" ? "DELETE " + r.target : decodeURIComponent(r.target)));

const sequence = (n: number): number[] => Array.from({ length: n }, (_, i) => i);

/** CellsetService against a fake TM1: the 1:1 methods send what the Java client's do, and `stream`
 * pages a cellset's cells in order, as they're iterated, and deletes it however the iteration ends
 * (the .NET client's CellsetServiceTests). */
describe("CellsetService", () => {
  it("executes MDX, statements and views", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ ID: "c1" });
    const client = tm1.client();
    expect((await client.cellsets.execute("SELECT FROM [Sales]", ODataQuery.none().expand("Cells"))).ID).toBe("c1");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "ExecuteMDX?$expand=Cells", '{"MDX":"SELECT FROM [Sales]"}']);

    expect((await client.cellsets.executeView("Sales", ViewScope.Private, "Mine", ODataQuery.none().expand("Axes"))).ID).toBe("c1");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Cubes('Sales')/PrivateViews('Mine')/tm1.Execute?$expand=Axes", "{}"]);
    await client.cellsets.executeView("Sales", ViewScope.Public, "Default");
    expect(tm1.last.target).toBe("Cubes('Sales')/Views('Default')/tm1.Execute");

    tm1.respond = () => null;
    await client.cellsets.executeStatement("SELECT FROM [Sales]");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "ExecuteMDXStatement", '{"MDX":"SELECT FROM [Sales]"}']);
  });

  it("lists, gets and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ ID: "c1" });
    expect((await client.cellsets.list(ODataQuery.none().top(1)).toArray()).map((c) => c.ID)).toEqual(["c1"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cellsets?$top=1");

    tm1.respond = () => ({ ID: "c1" });
    expect((await client.cellsets.get("c1", ODataQuery.none().expand("Axes"))).ID).toBe("c1");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cellsets('c1')?$expand=Axes");

    tm1.respond = () => null;
    await client.cellsets.delete("c1");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("DELETE Cellsets('c1')");
  });

  it("navigates axes, tuples, members and cells", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Ordinal: 0, Cardinality: 2, Name: "Region", UniqueName: "[Region].[Region].[North]" });
    const get = (): string => `${tm1.last.method} ${tm1.last.target}`;

    expect((await client.cellsets.axes("c1", ODataQuery.none().expand("Tuples")).toArray()).map((a) => a.Cardinality)).toEqual([2]);
    expect(get()).toBe("GET Cellsets('c1')/Axes?$expand=Tuples");
    expect((await client.cellsets.hierarchies("c1", 1).toArray()).map((h) => h.Name)).toEqual(["Region"]);
    expect(get()).toBe("GET Cellsets('c1')/Axes(1)/Hierarchies");
    expect((await client.cellsets.tuples("c1", 0, ODataQuery.none().top(5)).toArray()).map((t) => t.Ordinal)).toEqual([0]);
    expect(get()).toBe("GET Cellsets('c1')/Axes(0)/Tuples?$top=5");
    expect((await client.cellsets.tupleMembers("c1", 0, 3).toArray()).map((m) => m.UniqueName)).toEqual(["[Region].[Region].[North]"]);
    expect(get()).toBe("GET Cellsets('c1')/Axes(0)/Tuples(3)/Members");
    expect((await client.cellsets.cells("c1", ODataQuery.none().select("Value").skip(2)).toArray()).map((c) => c.Ordinal)).toEqual([0]);
    expect(get()).toBe("GET Cellsets('c1')/Cells?$select=Value&$skip=2");
    expect((await client.cellsets.cellMembers("c1", 7).toArray()).map((m) => m.Name)).toEqual(["Region"]);
    expect(get()).toBe("GET Cellsets('c1')/Cells(7)/Members");

    tm1.respond = () => ({ Name: "Sales" });
    expect((await client.cellsets.cube("c1", ODataQuery.none().select("Name"))).Name).toBe("Sales");
    expect(get()).toBe("GET Cellsets('c1')/Cube?$select=Name");
  });

  it("reads a partition", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Ordinal: 2 }, { Ordinal: 4 });
    const read = await tm1.client().cellsets.partition("c1", 2, 4, ODataQuery.none().select("Ordinal")).toArray();
    expect(read.map((c) => c.Ordinal)).toEqual([2, 4]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cellsets('c1')/tm1.GetPartition(Begin=2,End=4)?$select=Ordinal");
  });

  it("writes cells and saves views", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    await client.cellsets.update("c1", 5, 42);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Cellsets('c1')/tm1.Update", '{"BeginOrdinal":5,"EndOrdinal":5,"Value":42}']);
    await client.cellsets.update("c1", 5, null);
    expect(tm1.last.text).toBe('{"BeginOrdinal":5,"EndOrdinal":5,"Value":null}');

    await client.cellsets.updateCells("c1", [CellsetUpdatePatch.builder().ordinal(0).value("x").build(), CellsetUpdatePatch.builder().ordinal(1).value(2.5).build()]);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Cellsets('c1')/tm1.UpdateCells", '{"Updates":[{"Ordinal":0,"Value":"x"},{"Ordinal":1,"Value":2.5}]}']);

    tm1.respond = () => ({ Name: "Saved" });
    expect((await client.cellsets.saveViewAs("c1", "Saved")).Name).toBe("Saved");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Cellsets('c1')/tm1.SaveViewAs", '{"Name":"Saved"}']);
    await client.cellsets.saveViewAs("c1", "Saved", true, true);
    expect(tm1.last.text).toBe('{"Name":"Saved","Overwrite":true,"MakePrivate":true}');
    await client.cellsets.saveViewAs("c1", "Mine", false, true);
    expect(tm1.last.text).toBe('{"Name":"Mine","Overwrite":false,"MakePrivate":true}');
  });

  it("streams two axes in pages of whole rows", async () => {
    // 3 columns × 4 rows; the first page of 5 ends mid-row, so row 1 comes again with the partitions
    const tm1 = cellset(12, [3, 4, 1], 5);
    expect(await tm1.client().cellsets.stream("SELECT", cells(), 5, ordinals).toArray()).toEqual(sequence(12));
    const sent = targets(tm1);
    expect(sent[0]).toBe("ExecuteMDX?" + EXPAND + "5)");
    expect(tm1.requests[0]!.text).toBe('{"MDX":"SELECT"}');
    expect(sent.slice(1, -1)).toEqual([
      "Cellsets('c1')/tm1.GetPartition(Begin=3,End=5)?$select=Ordinal,Value",
      "Cellsets('c1')/tm1.GetPartition(Begin=6,End=8)?$select=Ordinal,Value",
      "Cellsets('c1')/tm1.GetPartition(Begin=9,End=11)?$select=Ordinal,Value",
    ]);
    expect(sent[sent.length - 1]).toBe("DELETE Cellsets('c1')");
  });

  it("streams many rows per page", async () => {
    // 2 columns × 7 rows in pages of 4 cells: 2 rows a page
    const tm1 = cellset(14, [2, 7], 4);
    expect(await tm1.client().cellsets.stream("SELECT", cells(), 4, ordinals).toArray()).toEqual(sequence(14));
    expect(targets(tm1).filter((t) => t.includes("GetPartition")).map((t) => /Begin=\d+,End=\d+/.exec(t)![0])).toEqual(["Begin=4,End=7", "Begin=8,End=11", "Begin=12,End=13"]);
  });

  it("streams at least a row per page", async () => {
    // 3 columns in pages of 1 cell: a row a page
    const tm1 = cellset(6, [3, 2], 1);
    expect(await tm1.client().cellsets.stream("SELECT", cells(), 1, ordinals).toArray()).toEqual(sequence(6));
    expect(targets(tm1).filter((t) => t.includes("GetPartition")).map((t) => /Begin=\d+,End=\d+/.exec(t)![0])).toEqual(["Begin=0,End=2", "Begin=3,End=5"]);
  });

  it("streams more axes by $skip and $top", async () => {
    // 2 × 2 × 2: a third axis isn't a rectangle, so the pages are $skip and $top of Cells
    const tm1 = cellset(8, [2, 2, 2], 3);
    expect(await tm1.client().cellsets.stream("SELECT", cells(), 3, ordinals).toArray()).toEqual(sequence(8));
    expect(targets(tm1).filter((t) => t.includes("/Cells?"))).toEqual(["Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=3", "Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=6"]);
    expect(targets(tm1).at(-1)).toBe("DELETE Cellsets('c1')");
  });

  it("makes no more requests for a stream that fits in one page", async () => {
    const tm1 = cellset(4, [2, 2], 10);
    const summary = await tm1
      .client()
      .cellsets.stream("SELECT", cells(), 10, async (c, all) => [c.ID, (await all.toArray()).length] as const)
      .toArray();
    expect(summary).toEqual([["c1", 4]]);
    expect(targets(tm1)).toEqual(["ExecuteMDX?" + EXPAND + "10)", "DELETE Cellsets('c1')"]);
  });

  it("fetches pages as they're iterated, a few at a time", async () => {
    // 1 column × 20 rows in pages of 2: 9 partitions after the first page
    const tm1 = cellset(20, [1, 20], 2);
    const seen: number[] = [];
    for await (const ordinal of tm1.client().cellsets.stream("SELECT", cells(), 2, ordinals)) {
      seen.push(ordinal);
      if (ordinal === 2) {
        // The page being read, and at most two more in flight: not all nine
        expect(targets(tm1).filter((t) => t.includes("GetPartition")).length).toBeLessThanOrEqual(3);
      }
    }
    expect(seen).toEqual(sequence(20));
    expect(targets(tm1).filter((t) => t.includes("GetPartition")).length).toBe(9);
  });

  it("deletes its cellset when abandoned or failed", async () => {
    const tm1 = cellset(12, [3, 4], 3);
    const client = tm1.client();
    for await (const _ of client.cellsets.stream("SELECT", cells(), 3, (_, all) => all)) break;
    expect(targets(tm1).at(-1)).toBe("DELETE Cellsets('c1')");
    expect(tm1.requests.filter((r) => r.method === "DELETE").length).toBe(1);

    tm1.requests.length = 0;
    const failing = client.cellsets.stream<number>("SELECT", cells(), 3, () => {
      throw new Error("handler");
    });
    await expect(failing.toArray()).rejects.toThrow("handler");
    expect(targets(tm1).at(-1)).toBe("DELETE Cellsets('c1')");

    tm1.requests.length = 0;
    const abort = new AbortController();
    const cancelled = (async () => {
      for await (const _ of client.cellsets.stream("SELECT", cells(), 3, (_, all) => all, { signal: abort.signal })) abort.abort(new Error("cancelled"));
    })();
    await expect(cancelled).rejects.toThrow("cancelled");
    expect(targets(tm1).at(-1)).toBe("DELETE Cellsets('c1')");
  });

  it("needs a positive page size", () => {
    const tm1 = new FakeTm1();
    expect(() => tm1.client().cellsets.stream("SELECT", cells(), 0, (_, all) => all)).toThrow(RangeError);
    expect(tm1.requests).toEqual([]);
  });
});
