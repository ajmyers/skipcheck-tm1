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
    if (r.method === "GET" && r.target.endsWith("/Dimensions?$select=Name")) return FakeTm1.collection({ Name: names["region"] }, { Name: names["month"] });
    if (r.target.endsWith("tm1.CheckRules")) return FakeTm1.collection();
    return null;
  };
  return tm1;
}

describe("the live conformance runner", () => {
  const scenario = live.scenarios().find((s) => s.name === "cubes")!;

  it("runs the cubes scenario and writes its traffic", async () => {
    const dir = mkdtempSync(join(tmpdir(), "conformance-"));
    live.trafficTo(dir);
    const names = live.namesFor(scenario);
    const tm1 = fake(names);
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    const client = tm1.client((b) => b.transport(traffic));
    expect(await live.run(client, traffic, scenario, names)).toEqual([]);
    const lines = readFileSync(traffic.write("cubes", names), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    expect(lines[0].names).toEqual(names);
    expect(lines.slice(1).map((l: any) => l.step)).toHaveLength(scenario.steps.length + (scenario.finally?.length ?? 0));
    expect(lines[1].request.target).toBe("Dimensions");
  });

  it("stops at a step that misses its expectation, and still cleans up", async () => {
    const names = live.namesFor(scenario);
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(500, { error: { code: "1", message: "down" } });
    const traffic = new live.Traffic(tm1, FakeTm1.BASE);
    const failures = await live.run(tm1.client((b) => b.transport(traffic)), traffic, scenario, names);
    expect(failures).toHaveLength(1);
    expect(failures[0]).toMatch(/^step 0 dimensions.create/);
    expect(traffic.exchanges.slice(-3).map((e) => e["step"])).toEqual(["finally[0]", "finally[1]", "finally[2]"]);
  });
});
