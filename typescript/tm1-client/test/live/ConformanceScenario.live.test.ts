import { describe, expect } from "vitest";
import { fetchTransport } from "../../src/index.js";
import * as live from "../conformance/live.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** The live conformance scenarios (spec/conformance/live) against a real TM1: each step must meet
 * its expectation, and what the client sent is written to build/conformance-live/typescript for
 * scripts/compare-live-traffic.py to compare with the other clients'. The Java client's
 * `ConformanceScenarioTm1Test`. */
describe("live conformance scenarios on TM1" + liveReason, () => {
  tm1Test.each(live.scenarios())("runs the scenario: $name", async (scenario) => {
    const names = live.namesFor(scenario);
    const target = await Live.target();
    const traffic = new live.Traffic(fetchTransport(), target.url);
    const tm1 = (await Live.builder()).transport(traffic).build();
    try {
      const failures = await live.run(tm1, traffic, scenario, names);
      traffic.write(scenario.name, names);
      expect(failures).toEqual([]);
    } finally {
      await tm1.close();
    }
  });
});
