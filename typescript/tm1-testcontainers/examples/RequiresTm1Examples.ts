// Examples for requiresTm1 (Java's @RequiresTm1), quoted in its documentation: vitest tests,
// type-checked but never run.
import { randomUUID } from "node:crypto";
import { DimensionPatch, ElementPatch, ElementType, HierarchyPatch, Tm1Client, Tm1Version } from "@skipcheck/tm1-client";
import { Tm1ServerContainer } from "@skipcheck/tm1-testcontainers";
import { RequiresTm1, requiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { afterAll, afterEach, beforeAll, describe, expect } from "vitest";

// @start region = test-class
describe("the Region dimension", async () => {
  // Setup for the whole file needs the server too, so skip it as the tests are skipped
  const skipped = (await RequiresTm1.skipReason()) !== undefined;
  let tm1: Tm1Client;

  // One client per file, on the shared server
  beforeAll(async () => {
    if (skipped) return;
    const server = await Tm1ServerContainer.shared();
    tm1 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
  });

  afterAll(async () => {
    await tm1?.close();
  });

  // The server is shared, so use a unique name and delete it afterwards
  const region = "Region-" + randomUUID();

  afterEach(async () => {
    await tm1?.dimensions.delete(region).catch(() => undefined);
  });

  requiresTm1("creates the regions", async () => {
    const elements = ["North", "South"].map((name) => ElementPatch.builder().name(name).type(ElementType.Numeric).build());
    const hierarchy = HierarchyPatch.builder().name(region).elements(elements).build();
    const created = await tm1.dimensions.create(DimensionPatch.builder().name(region).hierarchies([hierarchy]).build());
    expect(created.Name).toBe(region);
  });
});
// @end

// @start region = by-version
requiresTm1("needs a recent server", async ({ tm1, skip }) => {
  // Skips, rather than fails, on servers older than the feature
  const introducedIn = Tm1Version.parse("11.8.03000");
  if (!tm1.getTm1Version().isAtLeast(introducedIn)) skip(`needs TM1 ${introducedIn.toString()} or newer`);
  // ...
});
// @end
