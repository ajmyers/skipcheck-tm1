// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { OnItsNetwork } from "../src/GenericContainer.js";

/** {@link OnItsNetwork}, with what Testcontainers keeps to a container's subclasses. */
class Probe extends OnItsNetwork {
  /** The networking part of the create request, as it is once Testcontainers is about to send it. */
  async networkingConfig(): Promise<unknown> {
    await this.beforeContainerCreated();
    return this.createOpts.NetworkingConfig;
  }
}

/** The container Testcontainers creates for each of this package's, without Docker: it's created
 * on its network with its aliases there, rather than on Docker's default bridge and connected
 * afterwards (a TM1 server would register its bridge address with the Admin Server). */
describe("OnItsNetwork", () => {
  it("puts the aliases on its network into the create request", async () => {
    const container = new Probe("example.com/made-up:1");
    container.aliases("planning-net", ["tm1-server", "tm1"]);
    expect(await container.networkingConfig()).toEqual({ EndpointsConfig: { "planning-net": { Aliases: ["tm1-server", "tm1"] } } });
  });

  it("keeps the aliases it was given, not the caller's list", async () => {
    const aliases = ["tm1-server"];
    const container = new Probe("example.com/made-up:1");
    container.aliases("planning-net", aliases);
    aliases.push("added-later");
    expect(await container.networkingConfig()).toEqual({ EndpointsConfig: { "planning-net": { Aliases: ["tm1-server"] } } });
  });

  it("leaves the networking of the create request unset without aliases", async () => {
    expect(await new Probe("example.com/made-up:1").networkingConfig()).toBeUndefined();
    const container = new Probe("example.com/made-up:1");
    container.aliases("planning-net", []);
    expect(await container.networkingConfig()).toBeUndefined();
  });
});
