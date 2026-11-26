// SPDX-License-Identifier: Apache-2.0

import { describe, expect } from "vitest";
import { fetchTransport } from "../../src/index.js";
import * as live from "../conformance/live.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sharedInstall } from "./server-assumptions.js";

/** The live conformance scenarios (spec/conformance/live) against a real TM1: each step must meet
 * its expectation, and what the client sent is written to build/conformance-live/typescript for
 * scripts/compare-live-traffic.py to compare with the other clients'. The Java client's
 * `ConformanceScenarioTm1Test`. */
describe("live conformance scenarios on TM1" + liveReason, () => {
  tm1Test.for(live.scenarios())("runs the scenario: $name", async (scenario, context) => {
    const names = live.namesFor(scenario);
    const target = await Live.target();
    const traffic = new live.Traffic(fetchTransport(), target.url);
    const tm1 = (await Live.builder()).transport(traffic).build();
    try {
      // What the server has and is: a scenario or a step for one it isn't is skipped
      const capabilities = await tm1.session.capabilities();
      const version = capabilities.productVersion!;
      const install = sharedInstall();
      if (!live.applies(scenario, capabilities.endpoints, version, install)) {
        context.skip(
          `the scenario needs ${JSON.stringify(scenario.requires)} ${scenario.tm1From} ${scenario.tm1Before} ${scenario.install}; the server is ${version.toString()}, schema ${capabilities.schemaVersion}, install ${install}`,
        );
        return;
      }
      const failures = await live.run(tm1, traffic, scenario, names, (step) => live.applies(step, capabilities.endpoints, version, install));
      traffic.write(scenario.name, names);
      expect(failures).toEqual([]);
    } finally {
      await tm1.close();
    }
  });
});
