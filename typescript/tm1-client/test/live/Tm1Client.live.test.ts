// SPDX-License-Identifier: Apache-2.0

import { describe, expect } from "vitest";
import { Live, liveReason, tm1Test } from "./live.js";
import { SERVER } from "./raw-tm1.js";

/** The Java client's `Tm1ClientTm1Test`. */
describe("Tm1Client on TM1" + liveReason, () => {
  tm1Test("connects, reads the server and closes", async () => {
    const client = await Live.client();
    const server = await (await client.connect()).session.execute(SERVER);
    const version = await client.session.version();
    await client.close();
    expect(version.toString()).toBe(server.ProductVersion);
    expect(client.session.isClosed()).toBe(true);
    expect(client.withSandbox("x").session.sandbox()).toBe("x");
  });
});
