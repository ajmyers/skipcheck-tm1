// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { Chore, ChoreCreate, ChorePatch, CubeCreate, Element, ElementCreate, ODataPath, readAs } from "../src/index.js";

/** The Java client's `WritesTest`: the create types and `builder(read)`, a write made from a read
 * entity holding only what TM1 applies, by the probe (`tm1-probe-<schema>.json`'s writes). */
describe("writes made from what was read", () => {
  it("creates from a read entity what a POST applies", () => {
    const read = readAs(Element, { Name: "North", Type: "Consolidated", Index: 4, Level: 1, Attributes: { Caption: "North" } });
    const create = JSON.parse(JSON.stringify(ElementCreate.builder(read).build())) as Record<string, unknown>;
    // TM1 ignores Index, Level and Attributes in a create: they aren't offered, nor copied
    expect(Object.keys(create).sort()).toEqual(["Name", "Type"]);
    expect(create.Type).toBe("Consolidated");
    // A builder, to change what's copied
    expect(JSON.parse(JSON.stringify(ElementCreate.builder(read).name("South").build())).Name).toBe("South");
  });

  it("patches from a read entity leaving out what a PATCH rejects or appends", () => {
    const read = readAs(Chore, {
      Name: "Nightly",
      Active: true,
      Frequency: "P1DT00H00M00S",
      Attributes: { Caption: "Nightly" },
      Tasks: [{ Step: 0, Parameters: [{ Name: "pValue", Value: "1" }] }],
    });
    // A PATCH refuses Attributes, and appends Tasks: neither is copied
    expect(Object.keys(JSON.parse(JSON.stringify(ChorePatch.builder(read).build()))).sort()).toEqual(["Active", "Frequency", "Name"]);
    // A create ignores Attributes too (they're written through the attribute cubes). Tasks is a
    // navigation: none is copied, as a task's Process would have to be bound again
    const create = Object.keys(JSON.parse(JSON.stringify(ChoreCreate.builder(read).build())));
    expect(create).toContain("Frequency");
    expect(create).not.toContain("Attributes");
    expect(create).not.toContain("Tasks");
  });

  it("refuses a create without what TM1 needs", () => {
    expect(() => CubeCreate.builder().name("Sales").build()).toThrow(/Dimensions/);
    const dimensions = ODataPath.of("Dimensions");
    const create = CubeCreate.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build();
    expect(JSON.parse(JSON.stringify(create))["Dimensions@odata.bind"]).toHaveLength(2);
  });
});
