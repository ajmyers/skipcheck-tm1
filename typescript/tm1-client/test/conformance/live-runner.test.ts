// SPDX-License-Identifier: Apache-2.0

/** The live conformance runner, offline: the cubes scenario against a fake TM1 that answers as TM1
 * would, writing traffic without the session's own requests. */
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FakeTm1 } from "../support/fake-tm1.js";
import * as live from "./live.js";

function fake(names: Record<string, string>): FakeTm1 {
  const tm1 = new FakeTm1();
  tm1.respond = (r) => {
    const body = r.body.length ? r.json : {};
    if (r.method === "POST" && (r.target === "Dimensions" || r.target === "Cubes")) return FakeTm1.reply(201, { Name: body.Name });
    const get = /^Cubes\('([^']*)'\)(\?.*)?$/.exec(r.target);
    if (r.method === "GET" && get) return get[1] === names["missing"] ? FakeTm1.reply(404, { error: { code: "278", message: "no" } }) : { Name: get[1] };
    if (r.method === "GET" && r.target.endsWith("/Dimensions?$select=Name"))
      return FakeTm1.collection({ Name: "Sandboxes" }, { Name: names["region"] }, { Name: names["month"] });
    if (r.target.endsWith("tm1.CheckRules")) return FakeTm1.collection();
    return null;
  };
  return tm1;
}

/** Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of 3 × 2 cells
 * (and the slicer), each worth its ordinal until an update writes it, names that exist but `missing`. */
function dataFake(names: Record<string, string>): FakeTm1 {
  const tm1 = new FakeTm1();
  // The cells' values, by ordinal, as the scenario's updates write them
  const values = new Map<number, unknown>();
  const cells = (begin: number, end: number) =>
    Array.from({ length: end - begin + 1 }, (_, i) => ({ Ordinal: begin + i, Value: values.has(begin + i) ? values.get(begin + i) : begin + i }));
  // By the update's ordinal, or its tuple's elements (a region, then a month)
  const ordinal = (update: Record<string, unknown>): number => {
    if ("Ordinal" in update) return Number(update.Ordinal);
    const [region, month] = (update["Tuple@odata.bind"] as string[]).map((path) => /Elements\('([^']*)'\)$/.exec(path)![1]);
    return ["North", "South"].indexOf(region!) * 3 + ["Jan", "Feb", "Mar"].indexOf(month!);
  };
  tm1.respond = (r) => {
    const { method, target } = r;
    const body = r.body.length ? r.json : {};
    let m: RegExpExecArray | null;
    if (target.includes(`('${names["missing"]}')`) || (method === "POST" && target.startsWith("ExecuteRelationalDrillthrough"))) {
      return FakeTm1.reply(404, { error: { code: "278", message: "no" } }, { "content-type": "application/json" });
    }
    if (method === "POST" && /^(Dimensions|Cubes|Sandboxes|Cubes\('[^']*'\)\/(Private)?Views)$/.test(target))
      return FakeTm1.reply(201, { Name: body.Name }, { "content-type": "application/json" });
    if (method === "POST" && target.split("?")[0] === "ExecuteMDX") {
      if (String(body.MDX).includes("SELEKT")) return FakeTm1.reply(400, { error: { code: "501", message: "syntax" } }, { "content-type": "application/json" });
      const top = Number(/\$top%3D(\d+)/.exec(target)?.[1] ?? 0);
      return {
        ID: "cs1",
        Axes: [
          { Ordinal: 0, Cardinality: 3 },
          { Ordinal: 1, Cardinality: 2 },
          { Ordinal: 2, Cardinality: 1 },
        ],
        Cells: cells(0, top - 1),
      };
    }
    if (method === "POST" && /^(Cellsets\('cs1'\)|Cubes\('[^']*'\))\/tm1\.UpdateCells$/.test(target)) {
      for (const update of body.Updates as Record<string, unknown>[]) values.set(ordinal(update), update.Value);
      return FakeTm1.reply(204);
    }
    if (method === "POST" && target.endsWith("/tm1.Execute?$select=ID")) return { ID: "cs2" };
    if (method === "GET" && (m = /^Cellsets\('cs1'\)\/tm1\.GetPartition\(Begin=(\d+),End=(\d+)\)/.exec(target)))
      return FakeTm1.collection(...cells(Number(m[1]), Number(m[2])));
    if (method === "GET" && (m = /^Cubes\('([^']*)'\)\/[A-Za-z]+\('[^']*'\)\/Cube/.exec(target))) return { Name: m[1] };
    if (method === "GET" && (m = /^Cubes\('[^']*'\)\/(Private)?Views\('([^']*)'\)/.exec(target))) {
      return target.includes("NativeView") ? { "@odata.type": "#ibm.tm1.api.v1.NativeView", SuppressEmptyRows: true, Name: m[2] } : { Name: m[2] };
    }
    if (method === "GET" && /^Cubes\('[^']*'\)\/PrivateViews\?/.test(target)) return FakeTm1.collection({ Name: names["native"] });
    if (method === "GET" && (m = /^Sandboxes\('([^']*)'\)/.exec(target))) return { Name: m[1], IsLoaded: true };
    return null;
  };
  return tm1;
}

describe("the live conformance runner's comparison", () => {
  it("treats a null as missing at any depth", () => {
    const expected = { A: { B: 1, C: [1.0, { D: null }] } };
    expect(live.same(expected, { A: { B: 1.0, C: [1, {}], E: null } })).toBe(true);
    expect(live.same(expected, { A: { B: 1, C: [1, { D: 2 }] } })).toBe(false);
    expect(live.same(expected, { A: { C: [1, {}] } })).toBe(false);
    expect(live.same(expected, { A: { B: 1, C: [1] } })).toBe(false);
    expect(live.same(true, 1)).toBe(false);
  });
});

describe("the live conformance runner, on the data scenarios", () => {
  for (const name of ["cellsets", "views", "sandboxes"]) {
    it(`runs the data scenarios: ${name}`, async () => {
      const scenario = live.scenarios().find((s) => s.name === name)!;
      const names = live.namesFor(scenario);
      const tm1 = dataFake(names);
      const traffic = new live.Traffic(tm1, FakeTm1.BASE);
      expect(
        await live.run(
          tm1.client((b) => b.transport(traffic)),
          traffic,
          scenario,
          names,
        ),
      ).toEqual([]);
      expect(new Set(traffic.exchanges.map((e) => e["step"])).size).toBe(scenario.steps.length + (scenario.finally?.length ?? 0));
    });
  }
});

describe("the live conformance runner", () => {
  const scenario = live.scenarios().find((s) => s.name === "cubes")!;

  it("runs the cubes scenario and records its traffic", async () => {
    const dir = mkdtempSync(join(tmpdir(), "conformance-"));
    live.trafficTo(dir);
    const names = live.namesFor(scenario);
    const tm1 = fake(names);
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    const client = tm1.client((b) => b.transport(traffic));
    expect(await live.run(client, traffic, scenario, names)).toEqual([]);
    const lines = readFileSync(traffic.write("cubes", names), "utf8")
      .trim()
      .split("\n")
      .map((l) => JSON.parse(l));
    expect(lines[0].names).toEqual(names);
    expect(lines.slice(1).map((l: any) => l.step)).toHaveLength(scenario.steps.length + (scenario.finally?.length ?? 0));
    expect(lines[1].request.target).toBe("Dimensions");
  });

  it("stops at a step that misses its expectation, and still cleans up", async () => {
    const names = live.namesFor(scenario);
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(500, { error: { code: "1", message: "down" } });
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    const failures = await live.run(
      tm1.client((b) => b.transport(traffic)),
      traffic,
      scenario,
      names,
    );
    expect(failures).toHaveLength(1);
    expect(failures[0]).toMatch(/^step 0 dimensions.create/);
    expect(traffic.exchanges.slice(-3).map((e) => e["step"])).toEqual(["finally[0]", "finally[1]", "finally[2]"]);
  });

  /** What a step expects, as TM1's answer to its one request. */
  function expected(expect: Record<string, any>): unknown {
    const json = { "content-type": "application/json" };
    if (expect.error) {
      const statuses: Record<string, number> = { NOT_FOUND: 404, CONFLICT: 409, PRECONDITION_FAILED: 412 };
      const status = expect.error.status ?? statuses[expect.error.kind] ?? 400;
      return FakeTm1.reply(
        status,
        {
          error: {
            code: expect.error.kind === "INVALID_UNIQUE_NAME" ? "248" : "278",
            message: expect.error.kind === "NOT_FOUND" ? "can not be found" : "refused",
          },
        },
        json,
      );
    }
    if (expect.fields?.$bytes !== undefined)
      return FakeTm1.reply(200, Uint8Array.from(Buffer.from(expect.fields.$bytes, "base64")), { "content-type": "application/octet-stream" });
    if (expect.fields) return FakeTm1.reply(200, expect.fields, json);
    if (expect.count !== undefined || expect.items) {
      const value: Record<string, unknown>[] = Array.from({ length: expect.count ?? 0 }, () => ({}));
      for (const [field, values] of Object.entries((expect.items ?? {}) as Record<string, unknown[]>)) {
        values.forEach((v, i) => ((value[i] ??= {})[field] = v));
      }
      return FakeTm1.reply(200, { value }, json);
    }
    return null;
  }

  /** Whether a step sends a request: all but those refused before one (an argument error). */
  function sends(step: live.Scenario["steps"][number]): boolean {
    const error = (step.expect as Record<string, any>).error;
    return !error || error.category === "api";
  }

  /** The expectation with the fields the step saves, as `saved_<name>`, if it lacks them. */
  function saving(step: live.Step, expect: Record<string, any>): Record<string, any> {
    if (!step.save) return expect;
    const fields: Record<string, unknown> = { ...(expect.fields ?? {}) };
    for (const [name, field] of Object.entries(step.save)) if (!(field in fields)) fields[field] = "saved_" + name;
    return { ...expect, fields };
  }

  /** Answers each step's one request with what the step expects (with what it saves too, when it
   * doesn't expect it), and a finally step's with 204. */
  function answeringAsExpected(s: live.Scenario, names: Record<string, string>): FakeTm1 {
    const answers = [...s.steps.filter(sends).map((step) => expected(saving(step, live.substitute(step.expect, names)))), ...(s.finally ?? []).map(() => null)];
    const tm1 = new FakeTm1();
    tm1.respond = () => (answers.length ? answers.shift() : FakeTm1.reply(599, "unscripted"));
    return tm1;
  }

  it("builds a write from what a step kept", async () => {
    // The patch carries what the read held (Rules) with the change; a finally step setting back a
    // read no step kept sends nothing
    const s = JSON.parse(
      '{"names":["cube"],"steps":[{"service":"cubes","method":"get","params":["name","query"],"args":["${cube}",{"$query":{}}],"keep":"cube","expect":{"fields":{"Name":"${cube}","Rules":"SKIPCHECK;"}}},{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"cube","json":{"DrillthroughRules":"x"}}],"expect":{"none":true}}],"finally":[{"service":"cubes","method":"update","params":["name","patch"],"args":["${cube}",{"$fromRead":"CubePatch","read":"never","json":{}}]}]}',
    ) as live.Scenario;
    const names = live.namesFor(s);
    const tm1 = answeringAsExpected(s, names);
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    expect(
      await live.run(
        tm1.client((b) => b.transport(traffic)),
        traffic,
        s,
        names,
      ),
    ).toEqual([]);
    expect(traffic.exchanges).toHaveLength(2);
    expect((traffic.exchanges[1]!["request"] as { json: unknown }).json).toEqual({ Rules: "SKIPCHECK;", DrillthroughRules: "x" });
  });

  it("saves a field of a result for the steps after it", async () => {
    const s = live.scenarios().find((x) => x.name === "annotations")!;
    const names = live.namesFor(s);
    const tm1 = answeringAsExpected(s, names);
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    expect(
      await live.run(
        tm1.client((b) => b.transport(traffic)),
        traffic,
        s,
        names,
      ),
    ).toEqual([]);
    expect(names).toMatchObject({ note: "saved_note", built: "saved_built" });
    const sent = traffic.exchanges.map((e) => {
      const request = e["request"] as { method: string; target: string };
      return `${request.method} ${request.target}`;
    });
    expect(sent).toEqual(expect.arrayContaining(["PATCH Annotations('saved_built')", "DELETE Annotations('saved_note')"]));

    expect(live.save({ service: "", method: "", params: [], args: [], save: { note: "ID" } }, { result: { Text: "no ID" } }, names)).toMatch(
      /^no ID to save as note/,
    );
  });

  it.each([
    "subsets",
    "members",
    "edges",
    "member-sets",
    "content",
    "encryption",
    "git",
    "users",
    "sessions",
    "attributes",
    "annotations",
    "processes",
    "chores",
    "round-trips",
  ])("runs a scenario sending one request per step: %s", async (name) => {
    const s = live.scenarios().find((x) => x.name === name)!;
    const names = live.namesFor(s);
    const tm1 = answeringAsExpected(s, names);
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    expect(
      await live.run(
        tm1.client((b) => b.transport(traffic)),
        traffic,
        s,
        names,
      ),
    ).toEqual([]);
    expect(traffic.exchanges).toHaveLength(s.steps.filter(sends).length + (s.finally?.length ?? 0));
  });
});
