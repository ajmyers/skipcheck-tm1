// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { DEPARTURES, Process, ProcessPatch, readAs, Subset, SubsetPatch } from "../src/index.js";
import { Repo } from "./support/repo.js";

/** The Java client's `DeparturesTest`: the properties of the model that depart from TM1's `$metadata`,
 * ones it doesn't declare (found by probing TM1, `tm1-probe-<schema>.json`), and the overlay's
 * departures and lenient dates. Java tags them with `@Tm1Departure`; here `DEPARTURES` lists them. */
describe("departures from $metadata", () => {
  it("reads and writes back the properties TM1 does not declare", () => {
    const process = readAs(Process, {
      Name: "Load",
      UIData: "CubeAction=1511\f",
      VariablesUIData: ["VarType=32\f"],
      Code: "#region Prolog\nx = 1;\n#endregion",
    });
    expect(process.UIData).toBe("CubeAction=1511\f");
    expect(process.VariablesUIData).toEqual(["VarType=32\f"]);
    expect(process.Code).toMatch(/^#region Prolog/);
    expect(readAs(Subset, { Name: "Top", Alias: "Caption" }).Alias).toBe("Caption");

    const patch = ProcessPatch.builder()
      .name("Load")
      .uiData(process.UIData ?? null)
      .variablesUIData(process.VariablesUIData ?? null)
      .build();
    expect(JSON.stringify(patch)).toBe('{"Name":"Load","UIData":"CubeAction=1511\\f","VariablesUIData":["VarType=32\\f"]}');
    expect(JSON.stringify(SubsetPatch.builder().alias("Caption").build())).toBe('{"Alias":"Caption"}');
  });

  it("tags every departure the spec lists", () => {
    const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as {
      types: { name: string; properties?: { name: string; undeclared?: unknown; departure?: unknown; lenientDate?: unknown }[] }[];
    };
    const listed = new Map<string, string>();
    for (const t of spec.types) {
      for (const p of t.properties ?? []) {
        const kind = p.undeclared ? "UNDECLARED" : p.departure ? "TYPE" : p.lenientDate ? "LENIENT_DATE" : undefined;
        if (kind) listed.set(`${t.name}/${p.name}`, kind);
      }
    }
    expect(listed.get("Process/UIData")).toBe("UNDECLARED");
    expect(listed.get("Subset/Alias")).toBe("UNDECLARED");
    expect(listed.get("CAMSettings/ClientPingCAMPassport")).toBe("TYPE");
    expect(listed.get("MessageLogEntry/TimeStamp")).toBe("LENIENT_DATE");

    const tagged = new Map(DEPARTURES.map((d) => [`${d.type}/${d.property}`, d.kind as string]));
    expect([...tagged].sort()).toEqual([...listed].sort());
    expect(DEPARTURES.find((d) => d.property === "UIData")?.observedIn).toEqual(["11.8.35"]);
  });
});
