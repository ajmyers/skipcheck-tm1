// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import * as api from "../src/index.js";
import { readAs } from "../src/index.js";
import { Repo } from "./support/repo.js";

interface Case {
  id: string;
  type: string;
  write: "create" | "patch";
  read: Record<string, unknown>;
  expect?: unknown;
  error?: string;
}

const CASES = (JSON.parse(readFileSync(join(Repo.conformance, "writes.json"), "utf8")) as { cases: Case[] }).cases;

/** The Java client's `WritesConformanceTest`: the writes made from what was read that every client
 * must produce identically (`spec/conformance/writes.json`). */
describe("writes conformance", () => {
  it("every case writes what it expects", () => {
    const exports = api as unknown as Record<string, unknown>;
    const wrong: string[] = [];
    for (const c of CASES) {
      const read = readAs(exports[c.type] as Parameters<typeof readAs>[0], c.read);
      const write = exports[c.type + (c.write === "create" ? "Create" : "Patch")] as { builder(read: unknown): { build(): unknown } };
      let got: unknown;
      try {
        got = JSON.parse(JSON.stringify(write.builder(read).build()));
      } catch (e) {
        if (!c.error || !String((e as Error).message).includes(c.error)) wrong.push(`${c.id}: failed with ${String(e)}`);
        continue;
      }
      if (c.error) wrong.push(`${c.id}: built ${JSON.stringify(got)}, but should fail with ${c.error}`);
      else
        try {
          expect(got).toEqual(c.expect);
        } catch {
          wrong.push(`${c.id}: wrote ${JSON.stringify(got)}, expected ${JSON.stringify(c.expect)}`);
        }
    }
    expect(CASES.length).toBeGreaterThan(0);
    expect(wrong).toEqual([]);
  });
});
