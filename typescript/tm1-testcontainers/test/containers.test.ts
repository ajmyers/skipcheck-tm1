// SPDX-License-Identifier: Apache-2.0

import { Tm1Version } from "@skipcheck/tm1-client";
import { describe, expect } from "vitest";
import { PawContainer, Tm1ServerContainer, Tm1WebContainer } from "../src/index.js";
import { PawSession } from "../src/PawSession.js";
import { requiresPaw, requiresTm1, requiresTm1Web } from "../src/vitest.js";
import { Tm1WebSession } from "./support/Tm1WebSession.js";

/** Against TM1 in Docker: skipped without Docker or a source, as the Java tests. */

async function get(tm1: Tm1ServerContainer, path: string): Promise<{ status: number; body: string }> {
  const auth = Buffer.from(tm1.getUsername() + ":" + tm1.getPassword()).toString("base64");
  const response = await fetch(tm1.getRestUrl() + path, { headers: { Authorization: "Basic " + auth } });
  return { status: response.status, body: await response.text() };
}

/** The server's name, which TM1 Web and PAW list it under: `tm1` unless configured. */
async function serverName(tm1: Tm1ServerContainer): Promise<string> {
  const response = await get(tm1, "Configuration/ServerName/$value");
  expect(response.status).toBe(200);
  return response.body;
}

/** Java's Tm1ServerContainerTest. */
describe("Tm1ServerContainer", () => {
  requiresTm1("starts a TM1 server and reports its version", async ({ tm1 }) => {
    expect(tm1.getRestUrl()).toMatch(/^http:\/\/.*\/api\/v1\/$/);
    expect(tm1.getTm1Version().compareTo(Tm1Version.parse("11"))).toBeGreaterThan(0);
    const version = await get(tm1, "Configuration/ProductVersion/$value");
    expect(version.status).toBe(200);
    expect(Tm1Version.parse(version.body).equals(tm1.getTm1Version())).toBe(true);
  });

  requiresTm1("starts with an empty database", async ({ tm1 }) => {
    // Only control cubes (named with a leading '}') in a new database
    const cubes = await get(tm1, "Cubes?$select=Name&$filter=not%20startswith(Name,'%7D')");
    expect(cubes.status).toBe(200);
    expect(cubes.body.replace(/\s/g, "")).toContain('"value":[]');
  });

  requiresTm1("shares one container across tests", async ({ tm1 }) => {
    expect(await Tm1ServerContainer.shared()).toBe(tm1);
    expect(tm1.isRunning()).toBe(true);
  });

  requiresTm1("refuses to start on the wrong version", async ({ tm1: shared }) => {
    await using tm1 = new Tm1ServerContainer(shared.getSource()).withExpectedVersion(Tm1Version.parse("99.0"));
    await expect(tm1.start()).rejects.toThrow("Expected TM1 99.0 but the server from");
    expect(tm1.isRunning()).toBe(false);
  });
});

/** Java's Tm1WebContainerTest. */
describe("Tm1WebContainer", () => {
  requiresTm1Web("lists and logs on to the shared TM1 server", async ({ tm1Web: web, tm1 }) => {
    const server = await serverName(tm1);
    const session = await Tm1WebSession.open(web);
    expect(await session.servers(web.getAdminHost())).toContain(server);
    expect((await session.login(web.getAdminHost(), server, tm1.getUsername(), tm1.getPassword())).trim()).not.toBe("");
  });

  requiresTm1Web("evaluates MDX through the evalservice", async ({ tm1Web: web, tm1 }) => {
    const server = await serverName(tm1);
    const session = await Tm1WebSession.open(web);
    const tm1SessionId = await session.login(web.getAdminHost(), server, tm1.getUsername(), tm1.getPassword());
    // The shared server is on Docker's default bridge, where TM1's registered host name only
    // resolves because Tm1WebContainer maps it
    const registeredHost = "http://" + tm1.getContainer().getHostname() + ":" + Tm1ServerContainer.REST_PORT;
    const result = await session.evaluateMdx(
      registeredHost,
      server,
      tm1SessionId,
      "SELECT {[}Groups].Members} ON 0, {[}Clients].Members} ON 1 FROM [}ClientGroups]",
    );
    expect(result).toContain('"success":true');
  });

  requiresTm1Web("shares one container wired to the shared server", async ({ tm1Web: web, tm1 }) => {
    expect(await Tm1WebContainer.shared()).toBe(web);
    expect(web.getTm1Server()).toBe(tm1);
    expect(web.getUrl()).toMatch(/^http:\/\/.*\/tm1web\/$/);
  });
});

/** Java's PawContainerTest. */
describe("PawContainer", () => {
  const timeout = { timeout: 30 * 60_000 };

  requiresPaw("logs on and calls TM1 through PAW", timeout, async ({ paw, tm1 }) => {
    const session = await new PawSession(paw.getUrl()).login(tm1.getUsername(), tm1.getPassword());
    const server = await serverName(tm1);
    expect(await session.servers()).toContain(server);
    const user = await session.tm1Get(server, "ActiveUser?$select=Name");
    expect(user.status).toBe(200);
    expect(user.body.toLowerCase()).toContain(('"Name":"' + tm1.getUsername() + '"').toLowerCase());
  });

  requiresPaw("serves the workspace app once logged on", timeout, async ({ paw, tm1 }) => {
    const session = await new PawSession(paw.getUrl()).login(tm1.getUsername(), tm1.getPassword());
    const app = await session.get("");
    expect(app.status).toBe(200);
    expect(app.body).toContain("Planning Analytics");
  });

  requiresPaw("refuses a wrong password", timeout, async ({ paw, tm1 }) => {
    await expect(new PawSession(paw.getUrl()).login(tm1.getUsername(), "not-the-password")).rejects.toThrow();
  });

  requiresPaw("shares one PAW wired to the shared server", timeout, async ({ paw, tm1 }) => {
    expect(await PawContainer.shared()).toBe(paw);
    expect(paw.getTm1Server()).toBe(tm1);
    expect(paw.isRunning()).toBe(true);
    expect(paw.getUrl()).toMatch(/^http:\/\/.*\/$/);
  });
});
