// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  Dimension,
  DimensionCreate,
  DimensionPatch,
  ElementCreate,
  ElementType,
  Filter,
  Hierarchy,
  HierarchyCreate,
  ODataPath,
  ODataQuery,
  type Tm1Client,
  Tm1NotFoundException,
  Tm1PreconditionFailedException,
  Tm1Request,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const element = (name: string): ElementCreate => ElementCreate.builder().name(name).type(ElementType.Numeric).build();

const dimension = (name: string): DimensionCreate =>
  DimensionCreate.builder()
    .name(name)
    .hierarchies([
      HierarchyCreate.builder()
        .name(name)
        .elements([element("North"), element("South")])
        .build(),
    ])
    .build();

const withElements = ODataQuery.none().expand(Dimension.Fields.Hierarchies, (h) => h.expand(Hierarchy.Fields.Elements));

/** DimensionService against a real TM1, on dimensions of its own: the Java client's `DimensionServiceTm1Test`. */
describe("DimensionService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  /** Runs `body` on a new dimension, deleted afterwards. */
  async function withDimension(body: (name: string) => Promise<void>): Promise<void> {
    const name = Live.name();
    await tm1.dimensions.create(dimension(name));
    try {
      await body(name);
    } finally {
      await tm1.dimensions.delete(name);
    }
  }

  tm1Test("creates, reads, lists and deletes a dimension", async () => {
    const name = Live.name();
    expect((await tm1.dimensions.create(dimension(name))).Name).toBe(name);

    const read = await tm1.dimensions.get(name, withElements);
    expect(read["@odata.etag"]?.trim()).toBeTruthy();
    expect(read.Hierarchies).toHaveLength(1);
    expect((read.Hierarchies![0]!.Elements ?? []).map((e) => e.Name).sort()).toEqual(["North", "South"]);

    const listed = await tm1.dimensions.list(ODataQuery.none().select(Dimension.Fields.Name).filter(Filter.startsWith(Dimension.Fields.Name, name))).toArray();
    expect(listed.map((d) => d.Name)).toEqual([name]);

    await tm1.dimensions.delete(name);
    await expect(tm1.dimensions.get(name)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("updates only when the ETag is current", () =>
    withDimension(async (name) => {
      const etag = (await tm1.dimensions.get(name))["@odata.etag"]!;
      // A dimension PATCH applies no scalar property TM1 keeps: an empty one still checks the ETag
      const patch = DimensionPatch.builder().build();
      await tm1.dimensions.update(name, patch, etag);

      await tm1.dimensions.addAllLeavesHierarchy(name);
      // A new hierarchy changes it
      expect((await tm1.dimensions.get(name))["@odata.etag"]).not.toBe(etag);
      await expect(tm1.dimensions.update(name, patch, etag)).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
    }),
  );

  tm1Test("ignores hierarchies and the all-leaves name in a patch", () =>
    withDimension(async (name) => {
      // DimensionPatch offers neither, as TM1 ignores both: sent as they are, to pin it
      const hierarchy = HierarchyCreate.builder()
        .name(name)
        .elements([element("North"), element("South"), element("East")])
        .build();
      await tm1.session.execute(Tm1Request.patch(ODataPath.of("Dimensions").key(name), { AllLeavesHierarchyName: "AllLeaves", Hierarchies: [hierarchy] }));
      const read = await tm1.dimensions.get(name, withElements);
      // TM1 accepts the PATCH and ignores it
      expect(read.AllLeavesHierarchyName ?? "").toBe("");
      expect(read.Hierarchies).toHaveLength(1);
      expect(read.Hierarchies![0]!.Elements).toHaveLength(2);
    }),
  );

  tm1Test("saves a copy and adds an all-leaves hierarchy", () =>
    withDimension(async (name) => {
      expect((await tm1.dimensions.saveAs(name, name + "-copy")).Name).toBe(name + "-copy");
      expect((await tm1.dimensions.saveAs(name, name + "-copy", true, false)).Name).toBe(name + "-copy");
      await tm1.dimensions.delete(name + "-copy");

      const leaves = await tm1.dimensions.addAllLeavesHierarchy(name);
      expect(leaves.Name).toBe("Leaves");
      const hierarchies = (await tm1.dimensions.get(name, ODataQuery.none().expand(Dimension.Fields.Hierarchies))).Hierarchies ?? [];
      expect(hierarchies.map((h) => h.Name)).toEqual(expect.arrayContaining([name, "Leaves"]));
    }),
  );

  tm1Test("locks and reserves", () =>
    withDimension(async (name) => {
      await tm1.dimensions.lock(name);
      await tm1.dimensions.unlock(name);
      await tm1.dimensions.reserve(name);
      await tm1.dimensions.release(name);
    }),
  );
});
