// SPDX-License-Identifier: Apache-2.0

import { describe, expect } from "vitest";
import { PlanningAnalyticsEnvironment } from "../src/index.js";
import { RequiresTm1, requiresTm1Engine } from "../src/vitest.js";
import { Tm1EngineRest } from "./support/Tm1EngineRest.js";
import { Tm1Rest } from "./support/Tm1Rest.js";

// In a file of its own: the environment starts its own engine, so nothing here starts the shared
// one, and none runs beside it

/** Java's Tm1EngineEnvironmentExampleTest: TM1 11 and TM1 v12 in one environment.
 * `PlanningAnalyticsEnvironment` starts a TM1 server and a TM1 v12 engine on one network, for
 * tests of both versions, or of moving from one to the other. It needs a source for each. */
describe("TM1 11 and TM1 v12 in one environment", () => {
  // As Java's @RequiresTm1 with @RequiresTm1Engine. No database of the file's own is asked for:
  // the environment starts its own engine
  requiresTm1Engine("a server and an engine side by side", { timeout: 30 * 60_000 }, async ({ skip }) => {
    const why = await RequiresTm1.skipReason();
    if (why !== undefined) skip(why);
    await using env = PlanningAnalyticsEnvironment.builder()
      .tm1("legacy")
      .tm1Engine((engine) => engine.withDatabase("planning").withUser("analyst", "Analysts"))
      .build();
    await env.start();

    {
      await using legacy = new Tm1Rest(env.tm1("legacy"));
      expect(await legacy.modelCubeCount()).toBe(0);
    }
    const planning = env.tm1Database("planning");
    const response = await Tm1EngineRest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name");
    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
  });
});
