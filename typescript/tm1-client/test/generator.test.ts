// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { generate } from "../codegen/main.js";
import type { SpecDocument } from "../codegen/spec.js";
import { allOperations, Tm1Operations } from "../src/index.js";
import { Repo } from "./support/repo.js";

const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as SpecDocument;

/** The generator: deterministic, current, and named as the Java client names things. */
describe("codegen", () => {
  it("generates the same files twice, and what's in src/generated", () => {
    const a = generate(spec);
    expect(generate(JSON.parse(readFileSync(Repo.spec, "utf8")) as SpecDocument)).toEqual(a);
    const dir = join(Repo.typescript, "src", "generated");
    expect(readdirSync(dir).sort()).toEqual(Object.keys(a).sort());
    for (const [name, text] of Object.entries(a)) expect(readFileSync(join(dir, name), "utf8"), name).toBe(text);
  });

  it("refuses a spec version it doesn't read", () => {
    expect(() => generate({ ...spec, specVersion: 2 })).toThrow(/spec version 2/);
  });

  it("names operations as the Java client does", () => {
    expect(Tm1Operations.ChoreActivate.endpoint()).toBe("Action Chore/Activate");
    expect(Tm1Operations.ExecuteMdx.endpoint()).toBe("ActionImport ExecuteMDX");
    expect(Tm1Operations.CubeReserve.capability().addedInVersion()).toBe("11.8.34");
    const fromSpec = spec.operations.length;
    expect(allOperations.length).toBeGreaterThanOrEqual(fromSpec - 1);
    const ids = new Set(spec.endpoints.map((e) => e.id));
    for (const op of allOperations) expect(ids.has(op.endpoint()), op.endpoint()).toBe(true);
  });
});
