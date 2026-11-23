// SPDX-License-Identifier: Apache-2.0

import { Tm1Seed, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { RequiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Tm1Client } from "../../src/index.js";
import * as CellsWalkthrough from "../../examples/CellsWalkthrough.js";
import * as ConnectWalkthrough from "../../examples/ConnectWalkthrough.js";
import * as ProcessWalkthrough from "../../examples/ProcessWalkthrough.js";
import { ExampleOutput } from "../support/example-output.js";

/** Why these can't run, or undefined if they can, as Java's `@RequiresTm1`: they start a TM1 of
 * their own, so a server at `TM1_URL` doesn't do. Docker is only asked when a source is set; a
 * source that's set but wrong runs them, to fail on it. */
async function skipReason(): Promise<string | undefined> {
  try {
    if (!Tm1Source.discover()) return RequiresTm1.NO_SOURCE_MESSAGE;
  } catch {
    // Set but wrong
  }
  return RequiresTm1.skipReason();
}

const skip = await skipReason();

/** `it`, skipped without a TM1 source and Docker. */
const walkthroughTest: typeof it = (skip === undefined ? it : it.skip) as typeof it;

/** Runs the walkthroughs' examples (ConnectWalkthrough and the rest) against IBM's SData sample, and
 * records what each step prints for the docs, which show it under the step's code (ExampleOutput).
 * Run the live tests after changing a walkthrough's example, and commit the recordings in
 * api-site/example-output with it. The walkthroughs are written for SData, so these start a server
 * of their own with it rather than use the shared, empty one. The Java client's
 * `WalkthroughExamplesTm1Test`. */
describe("The walkthroughs' examples on TM1" + (skip === undefined ? "" : ` (skipped: ${skip})`), () => {
  let sdata: Tm1ServerContainer | undefined;
  let tm1: Tm1Client | undefined;

  beforeAll(async () => {
    if (skip !== undefined) return;
    sdata = new Tm1ServerContainer(Tm1Source.discover()!).withSeed(Tm1Seed.SDATA);
    await sdata.start();
    tm1 = Tm1Client.builder().baseUrl(sdata.getRestUrl()).basicAuth(sdata.getUsername(), sdata.getPassword()).build();
  });

  afterAll(async () => {
    await tm1?.close();
    await sdata?.stop();
  });

  walkthroughTest("connects and lists cubes", async () => {
    using output = new ExampleOutput("tm1-client", "ConnectWalkthrough");
    await output.record("version", () => ConnectWalkthrough.printVersion(tm1!));
    await output.record("list-cubes", () => ConnectWalkthrough.listCubes(tm1!));
    expect(output.output("version")).toEqual([`Connected to TM1 ${sdata!.getTm1Version().toString()}`]);
    expect(output.output("list-cubes")).toEqual(expect.arrayContaining(["SalesCube", "PriceCube"]));
  });

  walkthroughTest("reads and writes cells", async () => {
    using output = new ExampleOutput("tm1-client", "CellsWalkthrough");
    await output.record("read", () => CellsWalkthrough.read(tm1!));
    await output.record("write", () => CellsWalkthrough.write(tm1!));
    await output.record("read-back", () => CellsWalkthrough.readBack(tm1!));
    expect(output.output("read")).toHaveLength(3);
    expect(output.output("read-back")).toEqual(["Budget for Jan: 150"]);
  });

  walkthroughTest("runs a process and reads its result", async () => {
    using output = new ExampleOutput("tm1-client", "ProcessWalkthrough");
    await output.record("create", () => ProcessWalkthrough.create(tm1!));
    try {
      await output.record("run", () => ProcessWalkthrough.run(tm1!));
      await output.record("check", () => ProcessWalkthrough.check(tm1!));
      await output.record("fail", () => ProcessWalkthrough.fail(tm1!));
    } finally {
      await output.record("delete", () => ProcessWalkthrough.remove(tm1!));
    }
    expect(output.output("run")).toEqual(["Status: CompletedSuccessfully"]);
    expect(output.output("fail")[0]).not.toBe("Status: CompletedSuccessfully");
  });
});
