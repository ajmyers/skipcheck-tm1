// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CognosContainer,
  CognosSource,
  IllegalArgumentException,
  IllegalStateException,
  KeycloakContainer,
  Network,
  PawSource,
  PlanningAnalyticsEnvironment,
  Tm1AdminServerContainer,
  Tm1Seed,
  Tm1ServerContainer,
  Tm1Source,
  Tm1WebContainer,
  Tm1WebSource,
} from "../src/index.js";
import { Repo } from "./support/files.js";

/** These need no Docker: they read the wiring before anything starts, as the Java tests do. */

const source = new Tm1Source.PrivateImage("tm1-server:test");
const webSource = new Tm1WebSource.PrivateImage("tm1-web:test");
const decode = (auth: string): string => Buffer.from(auth.replace(/^\S+ /, ""), "base64").toString("utf8");

/** Java's AdminServerWiringTest. */
describe("Admin Server wiring", () => {
  it("servers register with the Admin Server on its network", () => {
    const admin = new Tm1AdminServerContainer(source);
    const planning = new Tm1ServerContainer(source).withAdminServer(admin);
    const reporting = new Tm1ServerContainer(source).withAdminServer(admin);
    expect(admin.getCommandParts()).toEqual(["admin"]);
    expect(admin.getNetworkAliases()).toContain(Tm1AdminServerContainer.NETWORK_ALIAS);
    expect(admin.getExposedPorts()).toEqual([5895]);
    expect(planning.getEnvMap().get("TM1_ADMIN_HOST")).toBe(Tm1AdminServerContainer.NETWORK_ALIAS);
    expect(planning.getNetwork()).toBeInstanceOf(Network);
    expect(admin.getNetwork()).toBe(planning.getNetwork());
    expect(reporting.getNetwork()).toBe(planning.getNetwork());
    expect(planning.getDependencies()).toContain(admin);
    expect(planning.getAdminServer()).toBe(admin);
  });

  it("TM1 Web lists servers from the Admin Server", () => {
    const admin = new Tm1AdminServerContainer(source);
    const web = new Tm1WebContainer(webSource).withAdminServer(admin);
    web.configure();
    expect(web.getEnvMap().get("TM1_ADMIN_HOST")).toBe(Tm1AdminServerContainer.NETWORK_ALIAS);
    expect(web.getAdminHost()).toBe(Tm1AdminServerContainer.NETWORK_ALIAS);
    expect(web.getNetwork()).toBe(admin.getNetwork());
    expect(web.getAdminServer()).toBe(admin);
  });

  it("joins a network either side already has", () => {
    const network = Network.newNetwork();
    const admin = new Tm1AdminServerContainer(source);
    const tm1 = new Tm1ServerContainer(source).withNetwork(network).withAdminServer(admin);
    expect(admin.getNetwork()).toBe(network);
    expect(tm1.getNetwork()).toBe(network);
    expect(network.getName()).toMatch(/^tm1-testcontainers-[0-9a-f]{12}$/);
    expect(network.isCreated()).toBe(false);
  });

  it("refuses containers on different networks", () => {
    const admin = new Tm1AdminServerContainer(source).withNetwork(Network.newNetwork());
    const tm1 = new Tm1ServerContainer(source).withNetwork(Network.newNetwork());
    expect(() => tm1.withAdminServer(admin)).toThrow(IllegalStateException);
    expect(() => tm1.withAdminServer(admin)).toThrow("share one Network");
  });

  it("TM1 Web needs its TM1 server running", () => {
    const tm1 = new Tm1ServerContainer(source);
    const web = new Tm1WebContainer(webSource).withTm1Server(tm1);
    expect(tm1.getNetwork()).toBeDefined();
    expect(web.getDependencies()).toContain(tm1);
    expect(web.getTm1Server()).toBe(tm1);
    expect(() => web.configure()).toThrow("isn't running");
    expect(() => web.getAdminHost()).toThrow("withTm1Server");
  });
});

/** Java's CognosContainerTest. */
describe("CognosContainer", () => {
  const cognos = (): CognosContainer =>
    new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
      .withUser("admin", "adminpw", "Admins")
      .withUser("analyst", "analyst#pw", "Analysts", "Readers")
      .withUser("viewer", "viewerpw", "Readers");

  it("passes the users to the image", () => {
    const c = cognos();
    expect(c.usersSpec()).toBe("admin:adminpw:Admins analyst:analyst#pw:Analysts,Readers viewer:viewerpw:Readers");
    expect([...c.getGroups()]).toEqual(["Admins", "Analysts", "Readers"]);
    c.configure();
    expect(c.getEnvMap().get("COGNOS_USERS")).toBe(c.usersSpec());
    expect(c.getEnvMap().get("COGNOS_HOST")).toBe("cognos");
    expect(c.getNetworkAliases()).toContain(CognosContainer.NETWORK_ALIAS);
    expect(c.getNetworkUrl()).toBe("http://cognos:9300");
    expect(c.getUsers().map((u) => u.name)).toEqual(["admin", "analyst", "viewer"]);
    expect(c.getUser("analyst").groups).toEqual(["Analysts", "Readers"]);
  });

  it("builds CAM logons", () => {
    const auth = cognos().camAuthorization("analyst");
    expect(auth.startsWith("CAMNamespace ")).toBe(true);
    expect(decode(auth)).toBe("analyst:analyst#pw:LDAP");
    expect(decode(cognos().camAuthorization("analyst", CognosContainer.Namespace.OIDC))).toBe("analyst:analyst#pw:OIDC");
    expect(CognosContainer.camAuthorization("u", "p", "NS")).toBe("CAMNamespace " + Buffer.from("u:p:NS").toString("base64"));
    expect(() => cognos().camAuthorization("nobody")).toThrow("nobody");
  });

  it("refuses users the image can't take apart", () => {
    const c = cognos();
    expect(() => c.withUser("two words", "pw")).toThrow(IllegalArgumentException);
    expect(() => c.withUser("x", "pa:ss")).toThrow(IllegalArgumentException);
    expect(() => c.withUser("x", "pa ss")).toThrow(IllegalArgumentException);
    expect(() => c.withUser("x", "")).toThrow(IllegalArgumentException);
    expect(() => c.withUser("x", "pw", "Bad Group")).toThrow(IllegalArgumentException);
    expect(() => c.withUser("viewer", "again")).toThrow("already");
  });

  it("TM1 in CAM security logs on as the first admin", () => {
    const c = cognos();
    const tm1 = new Tm1ServerContainer(source).withCamSecurity(c, "admin");
    tm1.configure();
    expect(tm1.getUsername()).toBe("admin");
    expect(tm1.getPassword()).toBe("adminpw");
    expect(tm1.getAuthorization()).toBe(c.camAuthorization("admin"));
    expect(tm1.getEnvMap().get("TM1_CAM_URI")).toBe("http://cognos:9300");
    expect(tm1.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("LDAP");
    expect(tm1.getEnvMap().get("TM1_CAM_NAMESPACE_TYPE")).toBe("ldap");
    expect(tm1.getEnvMap().get("TM1_CAM_ADMINS")).toBe("admin");
    expect(tm1.getEnvMap().get("TM1_CAM_GROUPS")).toBe("Admins Analysts Readers");
    expect(tm1.getNetwork()).toBe(c.getNetwork());
    expect(tm1.getDependencies()).toContain(c);
    expect(tm1.getCognos()).toBe(c);
    expect(tm1.getCamNamespace()).toBe(CognosContainer.Namespace.LDAP);
  });

  it("TM1 through OIDC uses the OIDC namespace", () => {
    const keycloak = new KeycloakContainer();
    const withOidc = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test")).withUser("admin", "adminpw", "Admins").withKeycloak(keycloak);
    expect(withOidc.getEnvMap().get("COGNOS_KEYCLOAK_URL")).toBe("http://keycloak:8080");
    expect(withOidc.getNetwork()).toBe(keycloak.getNetwork());
    expect(withOidc.getKeycloak()).toBe(keycloak);
    const tm1 = new Tm1ServerContainer(source).withCamSecurity(withOidc, CognosContainer.Namespace.OIDC, "admin");
    tm1.configure();
    expect(tm1.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("OIDC");
    expect(tm1.getEnvMap().get("TM1_CAM_NAMESPACE_TYPE")).toBe("oidc");
    expect(tm1.getAuthorization()).toBe(withOidc.camAuthorization("admin", CognosContainer.Namespace.OIDC));
  });

  it("OIDC needs Keycloak", () => {
    const tm1 = new Tm1ServerContainer(source).withCamSecurity(cognos(), CognosContainer.Namespace.OIDC, "admin");
    expect(() => tm1.configure()).toThrow("withKeycloak");
  });

  it("needs an admin who is a test user", () => {
    const tm1 = new Tm1ServerContainer(source);
    expect(() => tm1.withCamSecurity(cognos())).toThrow(IllegalArgumentException);
    tm1.withCamSecurity(cognos(), "nobody");
    expect(() => tm1.configure()).toThrow("nobody");
  });

  it("native security keeps Basic logons", () => {
    const tm1 = new Tm1ServerContainer(source);
    expect(tm1.getAuthorization()).toBe("Basic " + Buffer.from("admin:").toString("base64"));
    expect(tm1.getCamNamespace()).toBeUndefined();
    expect(tm1.getCognos()).toBeUndefined();
  });

  it("Keycloak serves the Cognos realm", () => {
    const keycloak = new KeycloakContainer();
    expect(keycloak.getCommandParts()).toEqual(["start-dev"]);
    expect(keycloak.getEnvMap().get("KC_HOSTNAME")).toBe("http://keycloak:8080");
    expect(keycloak.getNetworkAliases()).toContain(KeycloakContainer.NETWORK_ALIAS);
    expect(keycloak.getDockerImageName()).toBe(process.env["KEYCLOAK_IMAGE"]?.trim() || KeycloakContainer.DEFAULT_IMAGE);
  });
});

const fixture = (name: string): string => join(Repo.paEnvironmentFixtures, name);

function thrown(action: () => unknown): Error {
  try {
    action();
  } catch (e) {
    return e as Error;
  }
  throw new Error("Nothing was thrown");
}

const builder = (): PlanningAnalyticsEnvironment.Builder =>
  PlanningAnalyticsEnvironment.builder().tm1Source(source).tm1WebSource(webSource).cognosSource(new CognosSource.PrivateImage("cognos-server:test"));

/** Java's PlanningAnalyticsEnvironmentTest. */
describe("PlanningAnalyticsEnvironment", () => {
  it("one server uses its own Admin Server", () => {
    const env = builder().tm1("planning").build();
    const planning = env.tm1("planning");
    expect(env.adminServer()).toBeUndefined();
    expect(planning.getEnvMap().get("TM1_SERVER_NAME")).toBe("planning");
    expect(planning.getEnvMap().has("TM1_ADMIN_HOST")).toBe(false);
    expect(planning.getNetworkAliases()).toContain("planning");
    expect(planning.getNetwork()).toBe(env.network());
  });

  it("several servers share an Admin Server that TM1 Web lists from", () => {
    const env = builder().tm1("planning").tm1("reporting").tm1Web().build();
    const admin = env.adminServer();
    expect(admin).toBeInstanceOf(Tm1AdminServerContainer);
    expect([...env.tm1Servers().keys()]).toEqual(["planning", "reporting"]);
    for (const server of env.tm1Servers().values()) {
      expect(server.getAdminServer()).toBe(admin);
      expect(server.getNetwork()).toBe(env.network());
    }
    expect(env.tm1Web().getAdminServer()).toBe(admin);
    expect(env.tm1Web().getDependencies()).toContain(env.tm1("planning"));
    expect(env.tm1Web().getDependencies()).toContain(env.tm1("reporting"));
    expect(env.containers()[0]).toBe(admin);
    expect(env.containers().at(-1)).toBe(env.tm1Web());
  });

  it("Cognos puts every server in CAM security", () => {
    const env = builder()
      .tm1("planning")
      .tm1("reporting")
      .cognos((cognos) => cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
      .build();
    for (const server of env.tm1Servers().values()) {
      server.configure();
      expect(server.getCognos()).toBe(env.cognos());
      expect(server.getEnvMap().get("TM1_CAM_ADMINS")).toBe("admin");
      expect(server.getEnvMap().get("TM1_CAM_GROUPS")).toBe("Admins Readers");
      expect(server.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("LDAP");
    }
  });

  it("Keycloak adds OIDC", () => {
    const env = builder()
      .tm1("planning")
      .cognos((cognos) => cognos.withUser("admin", "adminpw").withUser("boss", "bosspw"))
      .keycloak()
      .camSecurity(CognosContainer.Namespace.OIDC, "boss")
      .build();
    const planning = env.tm1("planning");
    planning.configure();
    expect(env.cognos().getKeycloak()).toBe(env.keycloak());
    expect(planning.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("OIDC");
    expect(planning.getEnvMap().get("TM1_CAM_ADMINS")).toBe("boss");
    expect(env.containers().slice(0, 2)).toEqual([env.keycloak(), env.cognos()]);
  });

  it("customizers see the wired container and can change it", () => {
    const env = builder()
      .tm1("planning", (tm1) => tm1.withSeed(Tm1Seed.SDATA).withEnv("TM1_CFG_MaxThreads", "4"))
      .build();
    const planning = env.tm1("planning");
    expect(planning.getEnvMap().get("TM1_SEED")).toBe("sdata");
    expect(planning.getEnvMap().get("TM1_CFG_MaxThreads")).toBe("4");
    expect(planning.getEnvMap().get("TM1_SERVER_NAME")).toBe("planning");
    expect(planning.getUsername()).toBe("admin");
    expect(planning.getPassword()).toBe("apple");
  });

  it("refuses missing or unsupported parts", () => {
    expect(() => builder().build()).toThrow("at least one");
    expect(() => builder().tm1("a").keycloak().build()).toThrow("cognos(...)");
    expect(() =>
      builder()
        .tm1("a")
        .cognos((c) => c.withUser("u", "p"))
        .camSecurity(CognosContainer.Namespace.OIDC)
        .build(),
    ).toThrow("keycloak()");
    expect(() =>
      builder()
        .tm1("a")
        .cognos((c) => c.withUser("u", "p"))
        .paw(() => undefined)
        .build(),
    ).toThrow("PAW");
    expect(() =>
      builder()
        .tm1("a")
        .cognos(() => undefined)
        .build(),
    ).toThrow("test users");
    expect(() => builder().tm1("a").tm1("a")).toThrow("already");
    expect(() => builder().tm1("two words")).toThrow(IllegalArgumentException);
    const env = builder().tm1("a").build();
    expect(() => env.tm1Web()).toThrow("tm1Web()");
    expect(() => env.tm1("b")).toThrow("[a]");
  });

  it("uses the network it's given, and keeps it", () => {
    const network = Network.newNetwork();
    const env = builder().tm1("a").network(network).build();
    expect(env.network()).toBe(network);
    expect(env.tm1("a").getNetwork()).toBe(network);
    expect(env.isRunning()).toBe(false);
  });

  it("from config builds the example with everything", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(join(Repo.paEnvironmentExamples, "everything.yaml"))
      .tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
      .tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
      .cognosSource(new CognosSource.PrivateImage("cognos-server:test"))
      .build();
    expect([...env.tm1Servers().keys()]).toEqual(["planning", "reporting"]);
    expect(env.tm1("planning").getEnvMap().get("TM1_SEED")).toBe("sdata");
    expect(env.tm1("reporting").getEnvMap().get("TM1_SEED")).toBe("empty");
    expect(env.adminServer()).toBeDefined();
    expect(env.tm1Web().getEnvMap().get("TM1_WEB_HEAP")).toBe("1g");
    expect(
      env
        .cognos()
        .getUsers()
        .map((u) => u.name),
    ).toEqual(["admin", "analyst", "viewer"]);
    expect(env.cognos().getUser("analyst").groups).toEqual(["Analysts", "Readers"]);
    expect(env.cognos().getEnvMap().get("COGNOS_MAX_MEMORY")).toBe("6144");
    expect(env.cognos().getKeycloak()).toBe(env.keycloak());
    // The sources set on the builder win over the file's ibm folder
    expect(env.tm1("planning").getSource()).toStrictEqual(new Tm1Source.PrivateImage("tm1-server:test"));
    const planning = env.tm1("planning");
    planning.configure();
    expect(planning.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("LDAP");
    expect(planning.getEnvMap().get("TM1_CAM_ADMINS")).toBe("admin");
    expect(planning.getEnvMap().get("TM1_CAM_GROUPS")).toBe("Admins Analysts Readers");
  });

  it("from config maps every setting", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(fixture("settings.yaml")).build();
    const ibm = resolve(fixture("ibm"));
    const planning = env.tm1("planning");
    expect(planning.getEnvMap().get("TM1_SEED")).toBe("sdata");
    expect(planning.getEnvMap().get("TM1_CFG_MaxThreads")).toBe("4");
    expect(planning.getEnvMap().get("TM1_CFG_EnableNewHierarchyCreation")).toBe("True");
    expect(planning.getEnvMap().get("TM1_CFG_DefaultMeasuresDimension")).toBe("Measures");
    // adminServer: true, with one server
    expect(env.adminServer()).toBeDefined();
    expect(planning.getAdminServer()).toBe(env.adminServer());
    expect(env.tm1Web().getEnvMap().get("TM1_WEB_HEAP")).toBe("2g");
    expect(env.cognos().getUser("boss").groups).toEqual(["Admins", "Planners"]);
    expect(env.cognos().getEnvMap().get("COGNOS_MAX_MEMORY")).toBe("8192");
    planning.configure();
    expect(planning.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("OIDC");
    expect(planning.getEnvMap().get("TM1_CAM_ADMINS")).toBe("boss");
    // ibm is every part's source; images are what they run
    expect(planning.getSource()).toStrictEqual(new Tm1Source.Bundle(ibm));
    expect(env.tm1Web().getSource()).toStrictEqual(new Tm1WebSource.Bundle(ibm));
    expect(env.cognos().getSource()).toStrictEqual(new CognosSource.Zip(ibm));
    expect(planning.getDockerImageName()).toBe("registry.example.com/tm1-server:local");
    expect(env.adminServer()!.getDockerImageName()).toBe("registry.example.com/tm1-server:local");
    expect(env.tm1Web().getDockerImageName()).toBe("registry.example.com/tm1-web:local");
    expect(env.cognos().getDockerImageName()).toBe("registry.example.com/cognos-server:local");
    expect(env.keycloak().getDockerImageName()).toBe("registry.example.com/keycloak:26");
  });

  it("from config runs PAW from the ibm folder", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(fixture("paw.yaml")).build();
    const ibm = resolve(fixture("ibm"));
    expect(env.paw().getSource()).toStrictEqual(new PawSource(join(ibm, "ipa_workspace_local_0.0.0.zip")));
    expect(env.paw().licenseAccepted()).toBe(true);
    expect(env.paw().getTm1Server()).toBe(env.tm1("tm1"));
    expect(env.adminServer()).toBeUndefined();
  });

  it("from config refuses what it cannot run", () => {
    const invalid = readdirSync(fixture("invalid"))
      .filter((f) => f.endsWith(".yaml"))
      .sort()
      .map((f) => join(fixture("invalid"), f));
    expect(invalid.length).toBeGreaterThan(0);
    for (const file of invalid) {
      const first = readFileSync(file, "utf8").split(/\r?\n/)[0]!;
      expect(first, file).toMatch(/^# error: /);
      const error = thrown(() => PlanningAnalyticsEnvironment.fromConfig(file));
      expect(error, file).toBeInstanceOf(IllegalArgumentException);
      expect(error.message.startsWith(basename(file) + ": "), file + ": " + error.message).toBe(true);
      expect(error.message, file).toContain(first.slice("# error: ".length));
    }
    const missing = thrown(() => PlanningAnalyticsEnvironment.fromConfig(fixture("missing.yaml")));
    expect(missing).toBeInstanceOf(IllegalArgumentException);
    expect(missing.message).toContain("not found");
  });
});
