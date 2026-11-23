// SPDX-License-Identifier: Apache-2.0

/** The live conformance runner, offline: the processes, chores and server scenarios against a fake
 * TM1 that answers as TM1 would (the Java client's ProcessAndServerScenariosTest, with the same
 * fake). */
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FakeTm1 } from "../support/fake-tm1.js";
import * as live from "./live.js";

const ENTITY = /^(Processes|Chores|Loggers|ErrorLogFiles|SQLDataSources)\('([^']*)'\)(\?.*)?$/;
const TOP = /^(MessageLogEntries|MessageLog\(Reverse=true\))\?\$top=(\d+)$/;

function fake(names: Record<string, string>): FakeTm1 {
  const tm1 = new FakeTm1();
  let tasks = 0;
  tm1.respond = (r) => {
    const { method, target } = r;
    if (method === "POST" && (target === "Processes" || target === "Chores")) {
      if (target === "Chores") tasks = (r.json.Tasks ?? []).length;
      return FakeTm1.reply(201, { Name: r.json.Name });
    }
    const entity = ENTITY.exec(target);
    if (method === "GET" && entity) {
      if (entity[2] === names["missing"] || entity[2] === names["missing"] + ".log") return FakeTm1.reply(404, { error: { code: "278", message: "no" } });
      return entity[1] === "Chores" ? { Name: entity[2], Active: false, ExecutionMode: "SingleCommit" } : { Name: entity[2] };
    }
    if (method === "GET" && target.startsWith("Processes?")) return FakeTm1.collection({ Name: names["process"] });
    if (method === "GET" && target.startsWith("Chores?")) return FakeTm1.collection({ Name: names["chore"] });
    if (target.endsWith("/tm1.Compile")) return FakeTm1.collection();
    if (target === "CompileProcess") return FakeTm1.collection({ Procedure: "Prolog", LineNumber: 1, Message: "Syntax error" });
    if (target.includes("ExecuteWithReturn") || target.startsWith("ExecuteProcessWithReturn")) return { ProcessExecuteStatusCode: "CompletedSuccessfully" };
    if (method === "GET" && target.startsWith("ProcessDebugContexts")) return FakeTm1.collection();
    if (/^Chores\('[^']*'\)\/Tasks$/.test(target)) return FakeTm1.reply(201, { Step: tasks++ });
    if (method === "GET" && /^Chores\('[^']*'\)\/Tasks\?.*$/.test(target)) return FakeTm1.collection(...Array.from({ length: tasks }, (_, i) => ({ Step: i })));
    if (method === "DELETE" && /^Chores\('[^']*'\)\/Tasks\(\d+\)$/.test(target)) {
      tasks--;
      return null;
    }
    if (method === "GET" && /^Chores\('[^']*'\)\/Tasks\(\d+\)\/Process(\?.*)?$/.test(target)) return { Name: names["process"] };
    if (method === "GET" && /^Chores\('[^']*'\)\/Tasks\(\d+\)\/Chore(\?.*)?$/.test(target)) return { Name: names["chore"] };
    if (method === "GET" && target.startsWith("Server?")) return { Name: "tm1", ProductVersion: tm1.productVersion };
    if (method === "GET" && /^(StaticConfiguration|ActiveConfiguration|Configuration)(\?.*)?$/.test(target)) return { ServerName: "tm1" };
    if (method === "GET" && target.startsWith("Loggers?")) return FakeTm1.collection({ Name: "TM1.Server", Level: "Info" });
    const top = TOP.exec(target);
    if (method === "GET" && top) return FakeTm1.collection(...Array.from({ length: Number(top[2]) }, (_, i) => ({ ID: i, Message: "m" })));
    if (method === "GET" && target === "SQLDataSources") return FakeTm1.collection();
    return null;
  };
  return tm1;
}

describe("the live conformance runner, for the processes, chores and server scenarios", () => {
  for (const name of ["server"]) {
    it(`runs the scenario and records its traffic: ${name}`, async () => {
      live.trafficTo(mkdtempSync(join(tmpdir(), "conformance-")));
      const scenario = live.scenarios().find((s) => s.name === name)!;
      const names = live.namesFor(scenario);
      const tm1 = fake(names);
      const traffic = new live.Traffic(tm1, FakeTm1.BASE);
      expect(
        await live.run(
          tm1.client((b) => b.transport(traffic)),
          traffic,
          scenario,
          names,
        ),
      ).toEqual([]);
      expect(traffic.exchanges).toHaveLength(scenario.steps.length + (scenario.finally?.length ?? 0));
    });
  }
});
