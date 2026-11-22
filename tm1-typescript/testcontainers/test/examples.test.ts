import { Tm1Client, Tm1Version } from "@ajmyers/tm1-client";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { DockerHost, Tm1ServerContainer, Tm1Source } from "../src/index.js";
import { RequiresTm1, requiresTm1 } from "../src/vitest.js";

/** The Java module's examples that need nothing but the shared server's own source (the others
 * need IBM's SData sample, Cognos or TM1 Web of their own): each is a real test, so it always
 * works as written. */

async function modelCubeCount(client: Tm1Client): Promise<number> {
  return (await client.cubes.modelCubes().toArray()).length;
}

/** Java's SharedServerExampleTest: one server across all your tests, and a client set up once per
 * file. */
describe("Sharing one server across tests", async () => {
  // A file-level setup that needs the server: skip it as the tests are skipped
  const skipped = (await RequiresTm1.skipReason()) !== undefined;
  let client: Tm1Client;

  beforeAll(async () => {
    if (skipped) return;
    const server = await Tm1ServerContainer.shared();
    client = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
  });

  afterAll(async () => {
    await client?.close();
  });

  requiresTm1("uses the client set up once", async () => {
    expect(await modelCubeCount(client)).toBe(0); // a new, empty database
  });

  requiresTm1("gets the server as a fixture", async ({ tm1 }) => {
    expect(tm1).toBe(await Tm1ServerContainer.shared()); // always the same shared server
    expect(tm1.getRestUrl()).toMatch(/\/api\/v1\/$/);
    expect(tm1.getUsername()).toBe("admin");
    expect(tm1.getPassword()).toBe("");
  });
});

/** Java's WithoutTheExtensionExampleTest: plain checks and a throwaway server, e.g. for a test that
 * damages its server. */
describe("Without the vitest helpers", () => {
  it("starts a throwaway server", async ({ skip }) => {
    if (!(await DockerHost.available())) skip("needs Docker");
    const source = Tm1Source.discover();
    if (!source) return skip("needs a TM1 source");

    await using tm1 = new Tm1ServerContainer(source);
    await tm1.start();
    const response = await fetch(tm1.getRestUrl() + "Configuration/ProductVersion/$value", { headers: { Authorization: tm1.getAuthorization() } });
    expect(await response.text()).toBe(tm1.getTm1Version().toString());
  }); // the container, and anything the test did to it, is removed here
});

/** Java's PinnedVersionExampleTest: tests run against the TM1 version you mean. */
describe("Pinning a version", () => {
  const pa2119 = Tm1Version.parse("11.8.03500.6");

  requiresTm1("refuses to start on any other version", async ({ tm1: shared, skip }) => {
    if (!shared.getTm1Version().equals(pa2119)) skip("this example pins to " + pa2119.toString());

    await using server = new Tm1ServerContainer(shared.getSource()).withExpectedVersion(pa2119);
    await server.start(); // throws "Expected TM1 11.8.03500.6 but the server from ...
    expect(server.getTm1Version().equals(pa2119)).toBe(true);
  });

  requiresTm1("skips where the feature doesn't exist", async ({ tm1, skip }) => {
    // Say the behavior under test first appeared in this (made-up) release
    const introducedIn = Tm1Version.parse("11.8.03000");
    if (!tm1.getTm1Version().isAtLeast(introducedIn)) skip("needs TM1 " + introducedIn.toString() + " or newer");

    expect(tm1.getTm1Version().compareTo(introducedIn)).toBeGreaterThanOrEqual(0); // ...then test the behavior
  });
});
