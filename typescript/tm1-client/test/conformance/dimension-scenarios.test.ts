// SPDX-License-Identifier: Apache-2.0

/** The dimension scenarios (dimensions, hierarchies, elements, levels), offline: against a fake TM1
 * that answers each step with what the step expects, so every step's call is made, sends one
 * request, and reads its answer. */
import { describe, expect, it } from "vitest";
import { FakeTm1 } from "../support/fake-tm1.js";
import * as live from "./live.js";

/** What TM1 would answer for the call to meet the expectation. */
function answer(expectation: Record<string, any> | undefined): unknown {
  if (!expectation || expectation.none) return null;
  if (expectation.error) {
    const kind: string = expectation.error.kind ?? "";
    const statuses: Record<string, number> = { NOT_FOUND: 404, CONFLICT: 409, ALREADY_EXISTS: 409, PRECONDITION_FAILED: 412 };
    const messages: Record<string, string> = { NOT_FOUND: "'x' can not be found in collection", ALREADY_EXISTS: "'x' already exists" };
    return FakeTm1.reply(expectation.error.status ?? statuses[kind] ?? 400, { error: { code: "278", message: messages[kind] ?? "refused" } });
  }
  if (expectation.count !== undefined) return FakeTm1.collection(...Array.from({ length: expectation.count }, () => ({})));
  if (expectation.items) {
    const items: Record<string, unknown>[] = [];
    for (const [field, values] of Object.entries(expectation.items as Record<string, unknown[]>)) {
      values.forEach((v, i) => {
        items[i] ??= {};
        items[i][field] = v;
      });
    }
    return FakeTm1.collection(...items);
  }
  return expectation.fields;
}

describe("the dimension scenarios, offline", () => {
  for (const name of ["dimensions", "hierarchies", "elements", "levels"]) {
    it(`runs each step of ${name} once`, async () => {
      const scenario = live.scenarios().find((s) => s.name === name)!;
      const names = live.namesFor(scenario);
      const tm1 = new FakeTm1();
      const traffic = new live.Traffic(tm1, FakeTm1.BASE);
      tm1.respond = () => {
        const step = traffic.step;
        return answer(step.startsWith("steps[") ? live.substitute(scenario.steps[Number(step.slice(6, -1))]!.expect, names) : undefined);
      };
      expect(
        await live.run(
          tm1.client((b) => b.transport(traffic)),
          traffic,
          scenario,
          names,
        ),
      ).toEqual([]);
      const labels = [...scenario.steps.map((_, i) => `steps[${i}]`), ...(scenario.finally ?? []).map((_, i) => `finally[${i}]`)];
      expect(traffic.exchanges.map((e) => e["step"])).toEqual(labels);
    });
  }
});
