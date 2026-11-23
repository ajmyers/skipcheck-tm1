// SPDX-License-Identifier: Apache-2.0

import { Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { RequiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { describe, expect, it } from "vitest";
import { Tm1Client } from "../../src/index.js";
import * as HelloWorld from "../../examples/HelloWorld.js";
import { ExampleOutput } from "../support/example-output.js";

/** Why this can't run, or undefined if it can, as Java's `@RequiresTm1`: it starts a TM1 of its own,
 * so a server at `TM1_URL` doesn't do. Docker is only asked when a source is set; a source that's
 * set but wrong runs it, to fail on it. */
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
const helloTest: typeof it = (skip === undefined ? it : it.skip) as typeof it;

/** Runs the quick start's example (HelloWorld) against a real TM1, and records what it prints for the
 * landing page, which shows it under the code (ExampleOutput). Run it after changing the example, and
 * commit the recording in api-site/example-output with it. The example logs in as a new tm1-server
 * does (admin, no password), so this starts a server of its own with the empty database rather than
 * use the shared one, which other tests fill. It has no cubes, so the example prints the version
 * alone. The Java client's `HelloWorldTm1Test`. */
describe("The quick start's example on TM1" + (skip === undefined ? "" : ` (skipped: ${skip})`), () => {
  helloTest(
    "prints the version and the cubes",
    async () => {
      const server = new Tm1ServerContainer(Tm1Source.discover()!);
      await server.start();
      try {
        using output = new ExampleOutput("tm1-client", "HelloWorld");
        const tm1 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
        try {
          await output.record("hello-world", () => HelloWorld.hello(tm1));
        } finally {
          await tm1.close();
        }
        expect(output.output("hello-world")).toEqual([`Hello from TM1 ${server.getTm1Version().toString()}`]);
      } finally {
        await server.stop();
      }
    },
    600_000,
  );
});
