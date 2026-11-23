// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import * as services from "../src/services/index.js";
import { Repo } from "./support/repo.js";

const COVERAGE = join(Repo.typescript, "coverage");

export function endpoints(): Set<string> {
  const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as { endpoints: { id: string }[] };
  return new Set(spec.endpoints.map((e) => e.id));
}

/** Every endpoint some service's table (`CubeService.endpoints`) says it implements. */
export function implemented(): Set<string> {
  const out = new Set<string>();
  for (const value of Object.values(services)) {
    const table = (value as { endpoints?: Record<string, readonly string[]> }).endpoints;
    if (typeof value === "function" && table) for (const ids of Object.values(table)) for (const id of ids) out.add(id);
  }
  return out;
}

export function lines(file: string): string[] {
  return readFileSync(join(COVERAGE, file), "utf8")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith("#"));
}

/** The endpoint coverage gate, as the Java client's CoverageGateTest: every endpoint of the spec is
 * implemented (in a service's endpoint table), excluded with a reason, or pending, under the same
 * rules, against the same inventory. */
describe("coverage gate", () => {
  it("every endpoint is accounted for", () => {
    const all = endpoints();
    const done = implemented();
    const excluded = new Map<string, string>();
    for (const line of lines("excluded.txt")) {
      const bar = line.indexOf("|");
      expect(bar > 0 && line.slice(bar + 1).trim().length > 0, `'${line}' needs a reason: <endpoint> | <reason>`).toBe(true);
      expect(excluded.has(line.slice(0, bar).trim()), "duplicate exclusion " + line).toBe(false);
      excluded.set(line.slice(0, bar).trim(), line.slice(bar + 1).trim());
    }
    const pending = lines("pending.txt");
    const untriaged = [...all].filter((e) => !done.has(e) && !excluded.has(e) && !pending.includes(e)).sort();
    expect(untriaged, "endpoints not implemented, excluded or pending").toEqual([]);
    const unknown = [...pending, ...excluded.keys(), ...done].filter((e) => !all.has(e)).sort();
    expect(unknown, "endpoints the spec doesn't define").toEqual([]);
    const stillPending = pending.filter((e) => done.has(e)).sort();
    expect(stillPending, "implemented endpoints still in pending.txt: take them off").toEqual([]);
    expect(pending.filter((e) => excluded.has(e))).toEqual([]);
    expect(new Set(pending).size).toBe(pending.length);
  });

  it("excludes what the Java client excludes", () => {
    const java = readFileSync(join(Repo.root, "java", "tm1-client", "src", "test", "resources", "metadata", "coverage-excluded.txt"), "utf8");
    const ids = (text: string): string[] =>
      text
        .split("\n")
        .filter((l) => l.trim() && !l.startsWith("#"))
        .map((l) => l.split("|")[0]!.trim())
        .sort();
    expect(ids(readFileSync(join(COVERAGE, "excluded.txt"), "utf8"))).toEqual(ids(java));
  });

  it("counts every kind of endpoint", () => {
    const all = endpoints();
    expect(all.has("EntitySet Cubes")).toBe(true);
    expect(all.has("Navigation ViewTitle/Subset")).toBe(true);
    // The same count as the Java client's CoverageGateTest
    expect(all.size).toBe(242);
  });
});
