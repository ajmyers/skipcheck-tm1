// SPDX-License-Identifier: Apache-2.0

import { statSync } from "node:fs";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { Tm1Client, Tm1Version } from "@skipcheck/tm1-client";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  CognosContainer,
  DockerHost,
  KeycloakContainer,
  PlanningAnalyticsEnvironment,
  Tm1AdminServerContainer,
  Tm1Seed,
  Tm1ServerContainer,
  Tm1Source,
  Tm1WebContainer,
  Tm1WebSource,
} from "../src/index.js";
import { RequiresTm1, requiresCognos, requiresTm1, requiresTm1Web } from "../src/vitest.js";
import { Tm1Rest } from "./support/Tm1Rest.js";
import { Tm1WebSession } from "./support/Tm1WebSession.js";

/** The Java module's examples (its *ExampleTest classes), test for test: each is a real test, so it
 * always works as written. Those that start IBM's SData sample, TM1 Web, Cognos or Keycloak of
 * their own take minutes, and run only where there's a source for each. */

/** Starting Cognos (and Keycloak) takes minutes, more so beside TM1. */
const cognosTimeout = { timeout: 45 * 60_000 };

/** Several containers of a test's own, each installed and started. */
const severalContainers = { timeout: 30 * 60_000 };

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

  // Java's test method parameter is a fixture here
  requiresTm1("gets the server as a parameter", async ({ tm1 }) => {
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

/** Java's SampleDatabaseExampleTest: a server of this file's own, with IBM's SData sample in it,
 * started before its tests and stopped after them. SData's login is admin / apple. */
describe("A server with the sample database", async () => {
  // A file-level setup that needs the server: skip it as the tests are skipped
  const skipped = (await RequiresTm1.skipReason()) !== undefined;
  let sdata: Tm1ServerContainer;
  let tm1: Tm1Rest;

  beforeAll(async () => {
    if (skipped) return;
    const shared = await Tm1ServerContainer.shared(); // its source tells this one where TM1 is
    sdata = new Tm1ServerContainer(shared.getSource()).withSeed(Tm1Seed.SDATA);
    await sdata.start();
    tm1 = new Tm1Rest(sdata);
  });

  afterAll(async () => {
    await tm1?.close();
    await sdata?.stop();
  });

  requiresTm1("reads the sample cubes", async () => {
    expect(sdata.getPassword()).toBe("apple");
    expect(await tm1.modelCubeCount()).toBeGreaterThan(0);
    expect((await tm1.get("Cubes('SalesCube')?$select=Name")).toLowerCase()).toContain('"name":"salescube"');
  });
});

/** Java's OwnDatabaseExampleTest: a server running your own database, a TM1 data folder copied
 * into the container before TM1 starts. This uses IBM's SData sample folder from the TM1 install as
 * "your" database, so it only runs when TM1 comes from an install folder (TM1_HOME_DIR). */
describe("A server running your own database", () => {
  requiresTm1("runs a database from a folder", async ({ tm1: shared, skip }) => {
    const source = shared.getSource();
    if (!(source instanceof Tm1Source.InstallDir)) return skip("needs TM1_HOME_DIR, to find a sample database folder");
    const myDatabase = join(source.dir, "samples", "tm1", "SData");
    if (!statSync(myDatabase, { throwIfNoEntry: false })?.isDirectory()) skip("this install has no SData sample");

    await using server = new Tm1ServerContainer(source).withData(myDatabase).withCredentials("admin", "apple");
    await server.start();

    await using tm1 = new Tm1Rest(server);
    expect((await tm1.get("Cubes('SalesCube')?$select=Name")).toLowerCase()).toContain("salescube");
  });
});

/** Java's TwoServersExampleTest: two TM1 servers at once, for code that works across servers. Each
 * container is an independent TM1 server with its own Admin Server, on its own mapped ports. */
describe("Two servers at once", () => {
  requiresTm1("compares a source and a target", async ({ tm1: target }) => {
    // The shared, empty server is the target. Start a second one, with data, as the source.
    await using source = new Tm1ServerContainer(target.getSource()).withSeed(Tm1Seed.SDATA);
    await source.start();

    await using from = new Tm1Rest(source);
    await using to = new Tm1Rest(target);
    expect(source.getRestUrl()).not.toBe(target.getRestUrl());
    expect(await from.modelCubeCount()).toBeGreaterThan(0);
    expect(await to.modelCubeCount()).toBe(0);
    // ...then run your migration from `source` to `target` and compare again
  });
});

/** Java's ExplicitSourceExampleTest: choosing where TM1 comes from in code, instead of with
 * settings. Each test runs only where that kind of source is available, read from the same
 * environment variables `Tm1Source.discover()` uses. */
describe("Choosing the source in code", () => {
  /** The variable's value, as Java's `@EnabledIfEnvironmentVariable`: the test is skipped without it. */
  const setting = (name: string, skip: (note?: string) => never): string => process.env[name] || skip(name + " isn't set");

  async function expectStarts(container: Tm1ServerContainer): Promise<void> {
    await using started = container;
    await started.start();
    expect(started.getTm1Version()).toBeDefined();
  }

  requiresTm1("from an installed folder", async ({ skip }) => {
    // The tm1_64 folder of a Linux TM1 Server install. Fastest to start: nothing to install.
    const source = new Tm1Source.InstallDir(setting(Tm1Source.HOME_DIR, skip));
    await expectStarts(new Tm1ServerContainer(source));
  });

  requiresTm1("from IBM's bundle file", async ({ skip }) => {
    // IBM's download, as is. Installed on first use into a shared cache volume.
    // Pass a file name as the second argument to pick one of several bundles.
    const source = new Tm1Source.Bundle(setting(Tm1Source.BUNDLE_DIR, skip));
    await expectStarts(new Tm1ServerContainer(source));
  });

  requiresTm1("from a download link", async ({ skip }) => {
    // A pre-signed S3/R2/GCS link, or any URL curl can fetch. The SHA-256 is optional but
    // recommended. The URL's query string (where pre-signed credentials live) is never logged.
    const source = new Tm1Source.BundleUrl(setting(Tm1Source.BUNDLE_URL, skip), process.env[Tm1Source.BUNDLE_SHA256]);
    await expectStarts(new Tm1ServerContainer(source));
  });

  requiresTm1("from a Docker volume", async ({ skip }) => {
    // A volume that already holds an install, such as the one the tm1-server compose setup fills
    // (tm1_tm1-install), so tests reuse the TM1 you already run.
    const source = new Tm1Source.InstallVolume(setting(Tm1Source.INSTALL_VOLUME, skip));
    await expectStarts(new Tm1ServerContainer(source));
  });

  requiresTm1("from a private image with TM1 inside", async ({ skip }) => {
    // Built from docker/tm1-server/examples/private-image and pushed to a PRIVATE registry (it
    // contains IBM software). Fastest for CI: nothing to mount or install.
    const source = new Tm1Source.PrivateImage(setting(Tm1Source.PRIVATE_IMAGE, skip));
    await expectStarts(new Tm1ServerContainer(source));
  });
});

/** Java's SharedAdminServerExampleTest: several TM1 servers, one Admin Server, as in a real
 * deployment. `withAdminServer` puts each container on the Admin Server's Docker network and starts
 * the Admin Server first. Each server is named with the image's TM1_SERVER_NAME. */
describe("Several servers on one Admin Server", () => {
  function discovered(): Tm1Source {
    const source = Tm1Source.discover();
    if (!source) throw new Error("needs a TM1 source");
    return source;
  }

  /** The Admin Server's list of registered servers. TM1 servers register within seconds. */
  async function servers(admin: Tm1AdminServerContainer): Promise<string> {
    let body = "";
    for (let i = 0; i < 30 && !(body.includes("planning") && body.includes("reporting")); i++) {
      await sleep(1000);
      body = await (await fetch(admin.getRestUrl() + "Servers?$select=Name")).text();
    }
    return body;
  }

  requiresTm1("servers register with one Admin Server", severalContainers, async () => {
    const source = discovered();
    await using admin = new Tm1AdminServerContainer(source);
    await using planning = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "planning").withAdminServer(admin);
    await using reporting = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "reporting").withAdminServer(admin);
    await planning.start();
    await reporting.start();

    const registered = await servers(admin);
    expect(registered).toContain('"Name":"planning"');
    expect(registered).toContain('"Name":"reporting"');
    await using rest = new Tm1Rest(planning);
    expect(await rest.get("Configuration/ServerName/$value")).toBe("planning");
  });

  requiresTm1Web("TM1 Web lists every registered server", severalContainers, async () => {
    const source = discovered();
    const webSource = Tm1WebSource.discover();
    if (!webSource) throw new Error("needs a TM1 Web source");
    await using admin = new Tm1AdminServerContainer(source);
    await using planning = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "planning").withAdminServer(admin);
    await using reporting = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "reporting").withAdminServer(admin);
    await using web = new Tm1WebContainer(webSource).withAdminServer(admin);
    await planning.start();
    await reporting.start();
    await web.start();

    const listed = await (await Tm1WebSession.open(web)).servers(web.getAdminHost());
    expect(listed).toEqual(expect.arrayContaining(["planning", "reporting"]));
  });
});

/** Java's Tm1WebExampleTest: TM1 Web against a TM1 server of its own, here with IBM's SData sample.
 * `withTm1Server` gives the TM1 server a Docker network shared with TM1 Web, and starting TM1 Web
 * starts the server first. */
describe("TM1 Web with a server of its own", () => {
  requiresTm1Web("logs on to its own server through TM1 Web", severalContainers, async ({ tm1Web: shared, tm1: sharedTm1 }) => {
    // Same sources as the shared containers; pass your own Tm1Source/Tm1WebSource instead
    await using tm1 = new Tm1ServerContainer(sharedTm1.getSource()).withSeed(Tm1Seed.SDATA);
    await using web = new Tm1WebContainer(shared.getSource()).withTm1Server(tm1);
    await web.start();

    // TM1 Web drives a browser; Tm1WebSession stands in for one here
    const session = await Tm1WebSession.open(web);
    expect(await session.servers(web.getAdminHost())).toEqual(["tm1"]);
    const tm1SessionId = await session.login(web.getAdminHost(), "tm1", tm1.getUsername(), tm1.getPassword());
    expect(tm1SessionId.trim()).not.toBe("");

    // What a universal report does: evaluate MDX through TM1 Web's evalservice
    const serviceRoot = "http://" + tm1.getContainer().getHostname() + ":" + Tm1ServerContainer.REST_PORT;
    const result = await session.evaluateMdx(
      serviceRoot,
      "tm1",
      tm1SessionId,
      "SELECT {[Model].[Model].Members} ON 0 FROM [SalesCube] WHERE ([Actvsbud].[Actual], [Region].[World], [Account1].[Sales], [Month].[Year])",
    );
    expect(result).toContain('"success":true');
  });
});

/** Java's CamSecurityExampleTest: three users, three TM1 security profiles, through Cognos (CAM)
 * security: an administrator, an analyst who may write to a cube, and a viewer who may only read
 * it. Cognos takes a couple of minutes to start, so this has one test. */
describe("CAM security", () => {
  const analysts = 'CAMID(\\"LDAP:g:cn=analysts,ou=groups\\")';
  const readers = 'CAMID(\\"LDAP:g:cn=readers,ou=groups\\")';

  /** A TurboIntegrator process that creates the Sales cube and gives the groups access. */
  function setUpSecurity(): string {
    const prolog = [
      "DimensionCreate('Region');",
      "DimensionElementInsert('Region', '', 'North', 'N');",
      "DimensionCreate('Measure');",
      "DimensionElementInsert('Measure', '', 'Amount', 'N');",
      "CubeCreate('Sales', 'Region', 'Measure');",
      "If(CubeExists('}CubeSecurity') = 0);",
      "  CubeCreate('}CubeSecurity', '}Cubes', '}Groups');",
      "EndIf;",
      "CellPutS('WRITE', '}CubeSecurity', 'Sales', '" + analysts + "');",
      "CellPutS('READ', '}CubeSecurity', 'Sales', '" + readers + "');",
      "SecurityRefresh;",
    ].join("\\n");
    return '{"Process":{"Name":"zSetUpSecurity","PrologProcedure":"' + prolog + '"}}';
  }

  const write = (value: string): string =>
    `{"Cells":[{"Tuple@odata.bind":["Dimensions('Region')/Hierarchies('Region')/Elements('North')",` +
    `"Dimensions('Measure')/Hierarchies('Measure')/Elements('Amount')"]}],"Value":"${value}"}`;

  requiresCognos("each user gets their group's rights", cognosTimeout, async ({ cognosSource }) => {
    const tm1Source = Tm1Source.discover();
    if (!tm1Source) throw new Error("needs a TM1 source");
    await using cognos = new CognosContainer(cognosSource)
      .withUser("admin", "adminpw", "Admins")
      .withUser("analyst", "analystpw", "Analysts", "Readers")
      .withUser("viewer", "viewerpw", "Readers");
    await using tm1 = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, "admin");
    await tm1.start();

    await using admin = new Tm1Rest(tm1, cognos.camAuthorization("admin"));
    await using analyst = new Tm1Rest(tm1, cognos.camAuthorization("analyst"));
    await using viewer = new Tm1Rest(tm1, cognos.camAuthorization("viewer"));
    // TM1 knows users by CAMID, and puts them in their Cognos groups at logon
    expect(await admin.get("ActiveUser?$select=Type")).toContain('"Type":"Admin"');
    const groups = await analyst.get("ActiveUser/Groups?$select=Name");
    expect(groups).toContain(analysts);
    expect(groups).toContain(readers);

    // The administrator builds a cube and its security: Analysts write, Readers read
    expect((await admin.post("ExecuteProcessWithReturn", setUpSecurity())).status).toBe(201);

    expect((await analyst.post("Cubes('Sales')/tm1.Update", write("150"))).status).toBe(204);
    const read = await viewer.post(
      "ExecuteMDX?$expand=Cells($select=Value)",
      '{"MDX":"SELECT {[Measure].[Amount]} ON 0, {[Region].[North]} ON 1 FROM [Sales]"}',
    );
    expect(read.body).toContain('"Value":150');
    const refused = await viewer.post("Cubes('Sales')/tm1.Update", write("999"));
    expect(refused.status).toBe(400);
    expect(refused.body).toContain("CubeCellWriteStatusCubeNoWriteAccess");
  });
});

/** Java's OidcExampleTest: OpenID Connect logons. TM1 in CAM security through Cognos's OIDC
 * namespace, with Keycloak as the provider. Two ways in, without a browser: a password, which
 * Cognos checks with Keycloak (`CAMNamespace ...:OIDC`), and an ID token an application already
 * holds, exchanged at Cognos for a CAM passport (`CAMPassport`). */
describe("OpenID Connect logons", () => {
  requiresCognos("passwords and tokens log on to TM1", cognosTimeout, async ({ cognosSource }) => {
    const tm1Source = Tm1Source.discover();
    if (!tm1Source) throw new Error("needs a TM1 source");
    await using keycloak = new KeycloakContainer();
    await using cognos = new CognosContainer(cognosSource)
      .withUser("admin", "adminpw", "Admins")
      .withUser("viewer", "viewerpw", "Readers")
      .withKeycloak(keycloak);
    await using tm1 = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, CognosContainer.Namespace.OIDC, "admin");
    await tm1.start();

    // A password: TM1 hands it to Cognos, which checks it with Keycloak
    {
      await using admin = new Tm1Rest(tm1, cognos.camAuthorization("admin", CognosContainer.Namespace.OIDC));
      const user = await admin.get("ActiveUser?$select=Name,Type");
      expect(user).toContain('CAMID(\\"OIDC:u:admin\\")');
      expect(user).toContain('"Type":"Admin"');
    }

    // A token: the app holds viewer's ID token, and Cognos turns it into a passport
    const passport = await cognos.passport(await keycloak.idToken("viewer", "viewerpw"));
    await using viewer = new Tm1Rest(tm1, "CAMPassport " + passport);
    expect(await viewer.get("ActiveUser/Groups?$select=Name")).toContain('CAMID(\\"OIDC:g:readers\\")');
  });
});

/** Java's EnvironmentExampleTest: a whole environment in one object. PlanningAnalyticsEnvironment
 * starts and wires any mix of TM1 servers, an Admin Server, TM1 Web, PAW and Cognos security. Each
 * part's customizer gets the ordinary container class, with all its settings. */
describe("A whole environment in one object", () => {
  requiresTm1Web("two servers and TM1 Web", severalContainers, async () => {
    await using env = PlanningAnalyticsEnvironment.builder()
      .tm1("planning", (tm1) => tm1.withSeed(Tm1Seed.SDATA))
      .tm1("reporting")
      .tm1Web()
      .build();
    await env.start();

    // Two servers share one Admin Server, and TM1 Web lists both
    const listed = await (await Tm1WebSession.open(env.tm1Web())).servers(env.tm1Web().getAdminHost());
    expect(listed).toEqual(expect.arrayContaining(["planning", "reporting"]));
    await using planning = new Tm1Rest(env.tm1("planning"));
    await using reporting = new Tm1Rest(env.tm1("reporting"));
    expect(await planning.modelCubeCount()).toBeGreaterThan(0);
    expect(await reporting.modelCubeCount()).toBe(0);
  });

  requiresCognos("two servers in CAM security with LDAP and OIDC", cognosTimeout, async () => {
    await using env = PlanningAnalyticsEnvironment.builder()
      .tm1("planning")
      .tm1("reporting")
      .cognos((cognos) => cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
      .keycloak()
      .camSecurity(CognosContainer.Namespace.OIDC, "admin")
      .build();
    await env.start();

    const cognos = env.cognos();
    const viewerPassport = "CAMPassport " + (await cognos.passport(await env.keycloak().idToken("viewer", "viewerpw")));
    for (const server of env.tm1Servers().values()) {
      await using admin = new Tm1Rest(server, cognos.camAuthorization("admin", CognosContainer.Namespace.OIDC));
      await using viewer = new Tm1Rest(server, viewerPassport);
      expect(await admin.get("ActiveUser?$select=Type")).toContain('"Type":"Admin"');
      expect(await viewer.get("ActiveUser/Groups?$select=Name")).toContain('CAMID(\\"OIDC:g:readers\\")');
    }
  });

  requiresTm1("a reused environment keeps its containers for the next run", async ({ skip }) => {
    if (!DockerHost.reuseEnabled()) skip("Testcontainers reuse is off: set testcontainers.reuse.enable=true in ~/.testcontainers.properties");
    // A configuration of its own, so it can't pick up a developer's reused environment
    const builder = PlanningAnalyticsEnvironment.builder()
      .tm1("reuse-test", (tm1) => tm1.withEnv("TM1_CFG_MaxThreads", "3"))
      .reuse(true);
    const first = builder.build();
    await first.start();
    const container = first.tm1("reuse-test").getContainerId();
    await first.stop();
    const second = builder.build();
    try {
      await second.start();
      expect(second.tm1("reuse-test").getContainerId()).toBe(container);
    } finally {
      const docker = (await DockerHost.client()).container.dockerode;
      await docker.getContainer(container).remove({ force: true, v: true });
      await docker.getNetwork(await second.network().getId()).remove();
    }
  });
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
