// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { Tm1Version } from "@skipcheck/tm1-client";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import {
  CognosContainer,
  CognosSource,
  DockerHost,
  IllegalArgumentException,
  IllegalStateException,
  KeycloakContainer,
  Network,
  PawSource,
  PlanningAnalyticsEnvironment,
  Tm1AdminServerContainer,
  Tm1Database,
  Tm1EngineContainer,
  Tm1EngineSource,
  Tm1Seed,
  Tm1ServerContainer,
  Tm1Source,
  Tm1WebContainer,
  Tm1WebSource,
} from "../src/index.js";
import { RequiresTm1Engine, requiresTm1Engine } from "../src/vitest.js";
import { Repo, useTempDir } from "./support/files.js";

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

/** The pa-environment.yaml files for TM1 v12, shared with the other languages. */
const engineFixture = (name: string): string => join(Repo.paEnvironmentEngineFixtures, name);

const pawSource = (): PawSource => new PawSource(join(fixture("ibm"), "ipa_workspace_local_0.0.0.zip"));

const builder = (): PlanningAnalyticsEnvironment.Builder =>
  PlanningAnalyticsEnvironment.builder().tm1Source(source).tm1WebSource(webSource).cognosSource(new CognosSource.PrivateImage("cognos-server:test"));

const engineBuilder = (): PlanningAnalyticsEnvironment.Builder => builder().tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"));

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

  it("reuse needs the builder and the developer's switch", () => {
    expect(builder().tm1("a").build(true).reused()).toBe(false);
    const notSwitchedOn = builder().tm1("a").reuse(true).build(false);
    expect(notSwitchedOn.reused()).toBe(false);
    expect(notSwitchedOn.network().getName()).toMatch(/^tm1-testcontainers-/);
    const reused = builder().tm1("a").reuse(true).build(true);
    expect(reused.reused()).toBe(true);
    // Not started, so not yet marked: start() does that
    expect(reused.tm1("a").isShouldBeReused()).toBe(false);
  });

  it("a reused environment's network is named after its identity", () => {
    const env = builder().tm1("planning").tm1Web().reuse(true).build(true);
    expect(env.identity()).toMatch(/^[0-9a-f]{16}$/);
    expect(env.network().getName()).toBe("tm1-environment-" + env.identity());
    expect(env.network().isCreated()).toBe(false);
    for (const server of env.tm1Servers().values()) expect(server.getNetwork()).toBe(env.network());
    // The developer's own network is used as it is
    const own = Network.newNetwork();
    expect(builder().tm1("planning").network(own).reuse(true).build(true).network()).toBe(own);
  });

  it("the identity is the same for the same configuration and differs otherwise", () => {
    expect(builder().tm1("planning").tm1Web().reuse(true).build(true).identity()).toBe(builder().tm1("planning").tm1Web().reuse(true).build(true).identity());
    const configurations = [
      builder().tm1("planning"),
      builder().tm1("reporting"),
      builder().tm1("planning", (tm1) => tm1.withSeed(Tm1Seed.SDATA)),
      builder().tm1("planning", (tm1) => tm1.withEnv("TM1_CFG_MaxThreads", "4")),
      builder().tm1("planning").tm1("reporting"),
      builder().tm1("planning").adminServer(),
      builder().tm1("planning").tm1Web(),
      builder()
        .tm1("planning")
        .tm1Web((web) => web.withEnv("TM1_WEB_HEAP", "2g")),
      builder().tm1("planning").tm1Source(new Tm1Source.PrivateImage("tm1-server:other")),
      builder()
        .tm1("planning")
        .cognos((c) => c.withUser("admin", "adminpw")),
      builder()
        .tm1("planning")
        .cognos((c) => c.withUser("admin", "otherpw")),
      builder()
        .tm1("planning")
        .cognos((c) => c.withUser("admin", "adminpw").withUser("boss", "bosspw")),
      builder()
        .tm1("planning")
        .cognos((c) => c.withUser("admin", "adminpw").withUser("boss", "bosspw"))
        .camSecurity(CognosContainer.Namespace.LDAP, "boss"),
      builder()
        .tm1("planning")
        .cognos((c) => c.withUser("admin", "adminpw"))
        .keycloak(),
      // Testcontainers for Node's own reuse hash leaves out the network, so the identity has it
      builder().tm1("planning").network(Network.newNetwork()),
    ];
    const identities = configurations.map((b) => b.reuse(true).build(true).identity());
    expect(new Set(identities).size).toBe(identities.length);
  });

  it("every container names the language that started it", () => {
    const env = builder()
      .tm1("planning")
      .tm1("reporting")
      .tm1Web()
      .cognos((cognos) => cognos.withUser("admin", "adminpw"))
      .keycloak()
      .build();
    expect(env.containers()).toHaveLength(6);
    for (const part of env.containers()) {
      // The same label in every language, so docker ps finds what this library started
      expect((part as Tm1ServerContainer).getLabels().get("io.skipcheck.tm1.testcontainers"), String(part)).toBe("typescript");
    }
  });

  it("starting a reused environment marks every container with its identity", () => {
    const env = builder().tm1("planning").tm1("reporting").tm1Web().reuse(true).build(true);
    env.prepareForReuse();
    for (const part of env.containers()) {
      const container = part as Tm1ServerContainer;
      expect(container.isShouldBeReused(), String(container)).toBe(true);
      expect(container.getLabels().get("io.skipcheck.tm1.environment")).toBe(env.identity());
    }
    // No random alias to take away: neither Testcontainers for Node nor this library adds one
    expect(env.tm1("planning").getNetworkAliases()).toEqual(["planning"]);
    expect(env.adminServer()!.getNetworkAliases()).toEqual([Tm1AdminServerContainer.NETWORK_ALIAS]);
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

  it("from config runs PAW with Cognos", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(fixture("paw-cognos.yaml")).build();
    expect(env.paw().getTm1Server()).toBe(env.tm1("tm1"));
    expect(env.tm1("tm1").getCognos()).toBe(env.cognos());
    const settings = env.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/");
    expect(settings["PAW_AUTH_MODE"]).toBe("cam");
    expect(settings["PAW_CAM_NAMESPACE"]).toBe("LDAP");
  });

  it("PAW logs on to TM1 without Cognos", () => {
    const env = builder()
      .tm1("planning")
      .paw((paw) => paw.acceptLicense())
      .pawSource(pawSource())
      .build();
    const settings = env.paw().scriptSettings("10.0.0.2", "10.0.0.3", undefined);
    expect(settings).toMatchObject({ PAW_AUTH_MODE: "tm1", PAW_TM1_LOGIN_URL: "http://10.0.0.2:8010", PAW_TM1_ADMIN_URL: "http://10.0.0.3:5895" });
    expect(Object.keys(settings).filter((key) => key.startsWith("PAW_CAM_"))).toEqual([]);
  });

  it("PAW with Cognos logs on through CAM", () => {
    const env = builder()
      .tm1("planning")
      .cognos((cognos) => cognos.withUser("admin", "adminpw"))
      .paw((paw) => paw.acceptLicense())
      .pawSource(pawSource())
      .build();
    expect(env.paw().getTm1Server()).toBe(env.tm1("planning"));
    // The dispatcher on the network checks logons; browsers go to Cognos on the host
    expect(env.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/")).toMatchObject({
      PAW_AUTH_MODE: "cam",
      PAW_CAM_SERVLET: "http://cognos:9300/p2pd/servlet/dispatch",
      PAW_CAM_GATEWAY: "http://localhost:9300/bi/v1/disp",
      PAW_CAM_NAMESPACE: "LDAP",
    });
    // The TM1 server's namespace
    const oidc = builder()
      .tm1("planning")
      .cognos((cognos) => cognos.withUser("admin", "adminpw"))
      .keycloak()
      .camSecurity(CognosContainer.Namespace.OIDC)
      .paw((paw) => paw.acceptLicense())
      .pawSource(pawSource())
      .build();
    expect(oidc.paw().scriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/")["PAW_CAM_NAMESPACE"]).toBe("OIDC");
    expect(() => env.paw().scriptSettings("10.0.0.2", "10.0.0.2", undefined)).toThrow("Cognos");
  });

  it("an engine runs beside the TM1 servers", () => {
    const env = engineBuilder()
      .tm1("legacy")
      .tm1Engine((engine) => engine.withDatabase("planning").withUser("analyst", "Analysts"))
      .build();
    const engine = env.tm1Engine();
    expect(engine.getNetwork()).toBe(env.network());
    expect(env.tm1("legacy").getNetwork()).toBe(env.network());
    expect(engine.getSource()).toStrictEqual(new Tm1EngineSource.PrivateImage("tm1-engine:test"));
    // Its datastore is the engine's own, on the environment's network with it: before anything
    // is configured or started, and after
    expect(engine.getDatastore().getNetwork()).toBe(env.network());
    engine.configure();
    expect(engine.getDatastore().getNetwork()).toBe(env.network());
    expect(engine.getEnvMap().get("TM1_DATABASES")).toBe("tm1/planning");
    expect(engine.getEnvMap().get("TM1_USERS")).toBe("analyst:Analysts");
    expect(env.tm1Database("planning").getName()).toBe("planning");
    // Started with TM1 11's parts, and before PAW
    expect(env.containers()).toEqual([env.tm1("legacy"), engine]);
    const withPaw = engineBuilder()
      .tm1("legacy")
      .tm1Web()
      .tm1Engine()
      .paw((paw) => paw.acceptLicense())
      .pawSource(pawSource())
      .build();
    expect(withPaw.containers()).toEqual([withPaw.tm1("legacy"), withPaw.tm1Web(), withPaw.tm1Engine(), withPaw.paw()]);
    // A network the environment was given is the engine's and its datastore's too
    const network = Network.newNetwork();
    const given = engineBuilder().tm1Engine().network(network).build();
    expect(given.tm1Engine().getNetwork()).toBe(network);
    expect(given.tm1Engine().getDatastore().getNetwork()).toBe(network);
  });

  it("starts the engine's datastore on the environment's network", async () => {
    // Without Docker: the datastore, which starts first, says where it is and goes no further
    const env = engineBuilder().tm1Engine().build();
    const datastore = env.tm1Engine().getDatastore();
    const on: unknown[] = [];
    vi.spyOn(datastore, "start").mockImplementation(async () => {
      on.push(datastore.getNetwork());
      throw new Error("no Docker here");
    });
    const stop = vi.spyOn(datastore, "stop").mockResolvedValue();
    await expect(env.start()).rejects.toThrow("no Docker here");
    expect(on).toEqual([env.network()]);
    expect(stop).toHaveBeenCalled();
    expect(env.network().isCreated()).toBe(false);
  });

  it("an engine alone is an environment", () => {
    const env = engineBuilder().tm1Engine().build();
    expect(env.tm1Servers().size).toBe(0);
    expect(env.adminServer()).toBeUndefined();
    expect(env.containers()).toEqual([env.tm1Engine()]);
    expect(env.tm1Database("tm1").getInstance()).toBe("tm1");
    expect(env.isRunning()).toBe(false);
    expect(() => builder().tm1("planning").build().tm1Engine()).toThrow(IllegalStateException);
    expect(() => builder().tm1("planning").build().tm1Engine()).toThrow("no TM1 v12 engine; add it with tm1Engine()");
    expect(() => builder().tm1("planning").build().tm1Database("tm1")).toThrow("no TM1 v12 engine; add it with tm1Engine()");
    expect(() => PlanningAnalyticsEnvironment.builder().build()).toThrow("tm1(name), or a TM1 v12 engine with tm1Engine()");
  });

  it("the Admin Server, TM1 Web, PAW and Cognos need a TM1 server, not an engine", () => {
    for (const incomplete of [
      engineBuilder().tm1Engine().tm1Web(),
      engineBuilder().tm1Engine().adminServer(),
      engineBuilder()
        .tm1Engine()
        .paw((paw) => paw.acceptLicense())
        .pawSource(pawSource()),
      engineBuilder()
        .tm1Engine()
        .cognos((cognos) => cognos.withUser("admin", "adminpw")),
    ]) {
      expect(() => incomplete.build()).toThrow(IllegalStateException);
      expect(() => incomplete.build()).toThrow("are TM1 11's: add a TM1 server with tm1(name)");
    }
  });

  it("an environment with an engine can't be reused", () => {
    // Whatever the developer's switch says: the same code must do the same everywhere
    for (const enabled of [true, false]) {
      const reused = (): unknown => engineBuilder().tm1("legacy").tm1Engine().reuse(true).build(enabled);
      expect(reused).toThrow(IllegalStateException);
      expect(reused).toThrow("An environment with a TM1 v12 engine can't be reused");
    }
    // Without reuse it's an environment like any other, whatever the switch
    expect(engineBuilder().tm1("legacy").tm1Engine().build(true).reused()).toBe(false);
  });

  it("from config adds the engine with its databases and users", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(engineFixture("tm1-engine.yaml")).build();
    const ibm = resolve(engineFixture("ibm"));
    const engine = env.tm1Engine();
    engine.configure();
    // Sales and sales are two databases to TM1 v12, and each is in its instance
    expect(engine.getEnvMap().get("TM1_DATABASES")).toBe("tm1/planning,tm1/Sales,other/sales");
    // A user's password isn't TM1 v12's to keep
    expect(engine.getEnvMap().get("TM1_USERS")).toBe("analyst:Analysts:Planners,viewer,boss:ADMIN,planner:Planners");
    expect(env.tm1Database("Sales").getInstance()).toBe("tm1");
    expect(engine.database("other", "sales").getName()).toBe("sales");
    // ibm is every part's source; images are what they run
    expect(engine.getSource()).toStrictEqual(new Tm1EngineSource.Bundle(ibm));
    expect(engine.getDockerImageName()).toBe("registry.example.com/tm1-engine:local");
    expect(env.tm1("legacy").getSource()).toStrictEqual(new Tm1Source.Bundle(ibm));
    // images.mongo is checked and not used: the engine's MongoDB is its own
    expect(engine.getDatastore().getDockerImageName()).toBe(Tm1EngineContainer.DATASTORE_IMAGE);
    expect(engine.getNetwork()).toBe(env.network());
    expect(engine.getDatastore().getNetwork()).toBe(env.network());
  });

  it("from config runs an engine alone", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(join(Repo.containerConformanceScenarios, "engine.yaml"))
      .tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
      .build();
    expect(env.tm1Servers().size).toBe(0);
    env.tm1Engine().configure();
    expect(env.tm1Engine().getEnvMap().get("TM1_DATABASES")).toBe("tm1/planning,tm1/sales");
    expect(env.tm1Engine().getEnvMap().get("TM1_USERS")).toBe("analyst:Analysts");
  });

  it("from config takes OIDC true as on", () => {
    const env = PlanningAnalyticsEnvironment.fromConfig(engineFixture("oidc-true.yaml")).build();
    expect(env.keycloak()).toBeInstanceOf(KeycloakContainer);
    const planning = env.tm1("planning");
    planning.configure();
    expect(planning.getEnvMap().get("TM1_CAM_NAMESPACE")).toBe("OIDC");
  });

  it("from config refuses what it cannot run", () => {
    const invalid: string[] = [];
    for (const folder of [fixture("invalid"), engineFixture("invalid")]) {
      const found = readdirSync(folder)
        .filter((f) => f.endsWith(".yaml"))
        .sort()
        .map((f) => join(folder, f));
      expect(found.length, folder).toBeGreaterThan(0);
      invalid.push(...found);
    }
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

/** Java's Tm1EngineWiringTest. Wiring only: nothing here starts a container. */
describe("Tm1Engine wiring", () => {
  const SOURCE = new Tm1EngineSource.PrivateImage("tm1-engine:test");
  const temp = useTempDir();

  it("has one database unless some are named", () => {
    const engine = new Tm1EngineContainer(SOURCE);
    engine.configure();
    expect(engine.getEnvMap().get("TM1_DATABASES")).toBe("tm1/tm1");
    expect(engine.getEnvMap().has("TM1_USERS")).toBe(false);
    expect(engine.database("tm1").getInstance()).toBe("tm1");

    const named = new Tm1EngineContainer(SOURCE).withDatabase("planning").withDatabase("other", "sales");
    named.configure();
    expect(named.getEnvMap().get("TM1_DATABASES")).toBe("tm1/planning,other/sales");
    expect(named.database("other", "sales").getName()).toBe("sales");
    // An instance's name may be shorter than a database's
    const short = new Tm1EngineContainer(SOURCE).withDatabase("eu", "sales");
    short.configure();
    expect(short.getEnvMap().get("TM1_DATABASES")).toBe("eu/sales");
    expect(() => named.database("tm1")).toThrow(IllegalArgumentException);
    expect(() => named.database("tm1")).toThrow("No database tm1/tm1 was added (withDatabase). Added: [tm1/planning, other/sales]");
  });

  it("passes the users and their groups to the image", () => {
    const engine = new Tm1EngineContainer(SOURCE).withUser("analyst", "Analysts", "Planners").withUser("viewer");
    engine.configure();
    expect(engine.getEnvMap().get("TM1_USERS")).toBe("analyst:Analysts:Planners,viewer");
  });

  it("refuses names the image can't take", () => {
    const engine = new Tm1EngineContainer(SOURCE);
    // TM1 wants at least three characters
    expect(() => engine.withDatabase("ab")).toThrow(IllegalArgumentException);
    expect(() => engine.withDatabase("two words")).toThrow(IllegalArgumentException);
    expect(() => engine.withDatabase("a/b", "sales")).toThrow(IllegalArgumentException);
    expect(() => engine.withDatabase("a/b", "sales")).toThrow("A TM1 instance's name is letters, digits, '_' and '-', starting with a letter or digit: a/b");
    // An instance's name has no least length; a database's does, in any instance
    expect(() => engine.withDatabase("eu", "ab")).toThrow("A TM1 database's name is at least three letters");
    expect(() => engine.withUser("two words")).toThrow(IllegalArgumentException);
    expect(() => engine.withUser("analyst", "a:b")).toThrow(IllegalArgumentException);
    engine.withUser("analyst");
    expect(() => engine.withUser("analyst", "Again")).toThrow(IllegalArgumentException);
    expect(() => engine.withUser("analyst", "Again")).toThrow("already");
    // Nothing a refused call named was kept
    engine.configure();
    expect(engine.getEnvMap().get("TM1_DATABASES")).toBe("tm1/tm1");
    expect(engine.getEnvMap().get("TM1_USERS")).toBe("analyst");
  });

  it("runs its datastore on its own network", () => {
    const engine = new Tm1EngineContainer(SOURCE);
    engine.configure();
    const datastore = engine.getDatastore();
    expect(datastore.getDockerImageName()).toBe("mongo:8.0");
    expect(engine.getNetwork()).toBeInstanceOf(Network);
    expect(datastore.getNetwork()).toBe(engine.getNetwork());
    expect(datastore.getNetworkAliases()).toContain(Tm1EngineContainer.DATASTORE_ALIAS);
    expect(engine.getDependencies()).toContain(datastore);
    expect(engine.getEnvMap().get("TM1_DATASTORE_URL")).toBe("mongodb://root:tm1datastore@tm1-datastore:27017");
    expect(datastore.getEnvMap().get("MONGO_INITDB_ROOT_USERNAME")).toBe("root");
    expect(datastore.getEnvMap().get("MONGO_INITDB_ROOT_PASSWORD")).toBe("tm1datastore");
    expect(datastore.getCommandParts()).toEqual(["--wiredTigerCacheSizeGB", "0.5"]);
    expect(engine.getExposedPorts()).toEqual([Tm1EngineContainer.PORT]);
    // Both are this library's, like every container it starts
    expect(engine.getLabels().get("io.skipcheck.tm1.testcontainers")).toBe("typescript");
    expect(datastore.getLabels().get("io.skipcheck.tm1.testcontainers")).toBe("typescript");
    // The shared engine isn't kept between runs, and neither is one of your own
    expect(engine.isShouldBeReused()).toBe(false);
    expect(datastore.isShouldBeReused()).toBe(false);
  });

  it("its datastore is on its network before anything starts", () => {
    // Not only once the engine starts or is configured: an environment may start what the engine
    // depends on first, and TM1 couldn't reach a datastore left off the network
    const engine = new Tm1EngineContainer(SOURCE);
    expect(engine.getNetwork()).toBeInstanceOf(Network);
    expect(engine.getDatastore().getNetwork()).toBe(engine.getNetwork());
    const network = Network.newNetwork();
    engine.withNetwork(network);
    expect(engine.getDatastore().getNetwork()).toBe(network);
    // And the network it was given stays the one it's on when it's configured and started
    engine.configure();
    expect(engine.getNetwork()).toBe(network);
    expect(engine.getDatastore().getNetwork()).toBe(network);
  });

  it("joins a network it is given", () => {
    const network = Network.newNetwork();
    const engine = new Tm1EngineContainer(SOURCE).withNetwork(network);
    engine.configure();
    expect(engine.getNetwork()).toBe(network);
    expect(engine.getDatastore().getNetwork()).toBe(network);
  });

  it("mounts each source where the image looks", () => {
    temp.touch(join("install", "bin", "tm1sd.exe"));
    const install = join(temp.path, "install");
    expect(new Tm1EngineContainer(new Tm1EngineSource.InstallDir(install)).getBinds()).toEqual([`${install}:/opt/ibm/tm1-engine:ro`]);
    expect(new Tm1EngineContainer(new Tm1EngineSource.InstallVolume("tm1-engine")).getBinds()).toEqual(["tm1-engine:/opt/ibm/tm1-engine"]);

    temp.touch("tm1-v12.6.7-standalone-linux.tgz");
    const bundle = new Tm1EngineContainer(new Tm1EngineSource.Bundle(temp.path, "tm1-v12.6.7-standalone-linux.tgz"));
    expect(bundle.getBinds()).toEqual([`${temp.path}:/ibm:ro`, "tm1-engine-testcontainers-cache:/tm1/cache"]);
    expect(bundle.getEnvMap().get("TM1_ENGINE_BUNDLE")).toBe("tm1-v12.6.7-standalone-linux.tgz");

    const url = new Tm1EngineContainer(new Tm1EngineSource.BundleUrl("https://example.com/p.tgz", "abc"));
    expect(url.getBinds()).toEqual(["tm1-engine-testcontainers-cache:/tm1/cache"]);
    expect(url.getEnvMap().get("TM1_ENGINE_BUNDLE_URL")).toBe("https://example.com/p.tgz");
    expect(url.getEnvMap().get("TM1_ENGINE_BUNDLE_SHA256")).toBe("abc");

    expect(new Tm1EngineContainer(SOURCE).getBinds()).toEqual([]);
    expect(new Tm1EngineContainer(SOURCE).getDockerImageName()).toBe("tm1-engine:test");
  });

  // Java's options are the annotation's, kept by a subclass (@Inherited). Here they're fixtures of
  // the test, kept by a test extended from it. An engine that only answers stands in for the
  // shared one, and a source is set, so the test runs wherever this does
  describe("extended again", () => {
    const requiresInherited = requiresTm1Engine.extend({ shareDatabase: true, minVersion: "12.6" }).extend({});
    const saved = process.env[Tm1EngineSource.PRIVATE_IMAGE];
    const asked: string[] = [];
    let ran = false;

    beforeAll(() => {
      process.env[Tm1EngineSource.PRIVATE_IMAGE] = "tm1-engine:test";
      vi.spyOn(DockerHost, "available").mockResolvedValue(true);
      const engine = new (class extends Tm1EngineContainer {
        override getTm1Version(): Tm1Version {
          asked.push("version");
          return Tm1Version.parse("12.6.7");
        }
        override database(name: string): Tm1Database {
          return new Tm1Database(this, Tm1EngineContainer.DEFAULT_INSTANCE, name);
        }
      })(SOURCE);
      vi.spyOn(Tm1EngineContainer, "shared").mockResolvedValue(engine);
    });

    afterAll(() => {
      vi.restoreAllMocks();
      if (saved === undefined) delete process.env[Tm1EngineSource.PRIVATE_IMAGE];
      else process.env[Tm1EngineSource.PRIVATE_IMAGE] = saved;
    });

    requiresInherited("a subclass keeps the annotation's options", ({ shareDatabase, minVersion, tm1Database }) => {
      ran = true;
      expect(shareDatabase).toBe(true);
      expect(minVersion).toBe("12.6");
      // And they're used: the version was checked, and the database is the engine's own
      expect(asked).toEqual(["version"]);
      expect(tm1Database.getName()).toBe(Tm1EngineContainer.DEFAULT_DATABASE);
    });

    it("ran the test that keeps the options, rather than skipping it", () => {
      expect(ran).toBe(true);
    });
  });

  // A file here, where Java has a class: the unit vitest shares set-up over
  it("names a classes' database after the class", () => {
    expect(RequiresTm1Engine.databaseName("CubeTest", "k3f9")).toBe("cubetest_k3f9");
    // A short suffix is padded, so the name is always long enough for TM1
    expect(RequiresTm1Engine.databaseName("T", "7")).toBe("t_0007");
    expect(RequiresTm1Engine.databaseName("$$", "ab")).toBe("test_00ab");
    expect(RequiresTm1Engine.databaseName("AVeryLongTestClassNameThatGoesOnAndOn", "zzzz")).toBe("averylongtestclassnameth_zzzz");
  });

  it("asks nothing of an engine that isn't started", async () => {
    const engine = new Tm1EngineContainer(SOURCE);
    expect(() => engine.getTm1Version()).toThrow("The container isn't started yet");
    expect(() => engine.getRootClientId()).toThrow("The container isn't started yet");
    expect(() => engine.database("tm1").login()).toThrow("The container isn't started yet");
    await expect(engine.databases()).rejects.toThrow("The container isn't started yet");
    await expect(engine.createDatabase("scratch")).rejects.toThrow("The container isn't started yet");
    expect(engine.database("tm1").toString()).toBe("Tm1Database[tm1/tm1]");
    expect(engine.database("tm1").equals(engine.database("tm1"))).toBe(true);
    expect(engine.database("tm1").equals(new Tm1EngineContainer(SOURCE).database("tm1"))).toBe(false);
  });
});

/** What a running engine does with the image's `tm1-engine` command, without Docker: a started
 * container that answers each command as it's told to, and keeps what it was asked. */
describe("A running Tm1EngineContainer", () => {
  const credentials = {
    root: { clientId: "root-id", clientSecret: "root-secret" },
    instances: [
      {
        name: "tm1",
        applications: [
          { name: "elsewhere", clientId: "made-elsewhere", clientSecret: null },
          { name: "tm1", clientId: "the-id", clientSecret: "the-secret" },
          { name: "later", clientId: "later-id", clientSecret: "later-secret" },
        ],
        databases: [{ name: "tm1" }],
      },
      { name: "other", applications: [{ name: "elsewhere", clientId: "made-elsewhere", clientSecret: null }], databases: [{ name: "sales" }] },
    ],
  };

  function running(answer: (command: string[]) => { exitCode: number; stdout?: string; stderr?: string }): { engine: Tm1EngineContainer; asked: string[] } {
    const asked: string[] = [];
    const container = {
      getHost: () => "localhost",
      getMappedPort: () => 54321,
      exec: async (command: string[]) => {
        asked.push(command.join(" "));
        const result = command.includes("credentials") ? { exitCode: 0, stdout: JSON.stringify(credentials) } : answer(command);
        return { stdout: "", stderr: "", output: "", ...result };
      },
    };
    const engine = new (class extends Tm1EngineContainer {
      override isRunning(): boolean {
        return true;
      }
      override getContainer(): ReturnType<Tm1EngineContainer["getContainer"]> {
        return container as unknown as ReturnType<Tm1EngineContainer["getContainer"]>;
      }
    })(new Tm1EngineSource.PrivateImage("tm1-engine:test"));
    return { engine, asked };
  }

  it("logs in through the instance's first application whose secret the image holds", async () => {
    const { engine } = running(() => ({ exitCode: 0 }));
    const databases = await engine.databases();
    expect(databases.map(String)).toEqual(["Tm1Database[tm1/tm1]", "Tm1Database[other/sales]"]);
    const login = engine.database("tm1").login("analyst");
    expect([login.getClientId(), login.getClientSecret(), login.getUser(), login.getAuthUrl()]).toEqual([
      "the-id",
      "the-secret",
      "analyst",
      "http://localhost:54321/tm1/auth/v1/",
    ]);
    // An instance whose only application was made elsewhere has none to log in through
    expect(() => engine.database("other", "sales").login()).toThrow(IllegalStateException);
    expect(() => engine.database("other", "sales").login()).toThrow("The instance other has no application to log in through");
    expect(engine.getRootClientId()).toBe("root-id");
    expect(engine.getRootClientSecret()).toBe("root-secret");
  });

  it("gives creating a database the image's own wait, and the other commands three minutes", async () => {
    const { engine, asked } = running(() => ({ exitCode: 0 }));
    const made = await engine.createDatabase("scratch");
    await engine.dropDatabase(made);
    expect(asked).toEqual([
      "timeout 330 tm1-engine database create tm1/scratch",
      "timeout 180 tm1-engine credentials --json",
      "timeout 180 tm1-engine database drop tm1/scratch",
      "timeout 180 tm1-engine credentials --json",
    ]);
  });

  it("drops a database it couldn't finish creating, and says why the creation failed", async () => {
    const { engine, asked } = running((command) => (command.includes("create") ? { exitCode: 124, stderr: "still starting\n" } : { exitCode: 0 }));
    await expect(engine.createDatabase("other", "scratch")).rejects.toThrow("tm1-engine database create other/scratch failed (exit 124): still starting");
    expect(asked.slice(0, 2)).toEqual(["timeout 330 tm1-engine database create other/scratch", "timeout 180 tm1-engine database drop other/scratch"]);

    // The drop failing too changes nothing: the first failure is the one reported
    const neither = running((command) => ({
      exitCode: command.includes("create") ? 1 : 2,
      stderr: command.includes("create") ? "no such instance" : "no such database",
    }));
    await expect(neither.engine.createDatabase("scratch")).rejects.toThrow("tm1-engine database create tm1/scratch failed (exit 1): no such instance");
    expect(neither.asked.slice(0, 2)).toEqual(["timeout 330 tm1-engine database create tm1/scratch", "timeout 180 tm1-engine database drop tm1/scratch"]);
    // A name the image can't take is refused before anything is asked
    await expect(neither.engine.createDatabase("ab")).rejects.toThrow(IllegalArgumentException);
    expect(neither.asked.filter((a) => a.includes("database")).length).toBe(2);
  });

  it("stops the datastore when the engine can't start", async () => {
    const engine = new Tm1EngineContainer(new Tm1EngineSource.PrivateImage("tm1-engine:test"));
    const datastore = engine.getDatastore();
    // Fails before Docker is asked for anything: what the engine depends on is started first
    const start = vi.spyOn(datastore, "start").mockRejectedValue(new Error("no Docker here"));
    const stop = vi.spyOn(datastore, "stop").mockResolvedValue();
    await expect(engine.start()).rejects.toThrow("no Docker here");
    expect(start).toHaveBeenCalledTimes(1);
    expect(stop).toHaveBeenCalledTimes(1);
    expect(engine.isRunning()).toBe(false);
    // And a datastore that can't be stopped doesn't hide why the engine didn't start
    stop.mockRejectedValue(new Error("can't stop"));
    await expect(engine.start()).rejects.toThrow("no Docker here");
  });
});

/** The wait for the image to finish setting TM1 up, without Docker: {@link Tm1EngineContainer.awaitSetup}
 * with a container that answers as it's told to. */
describe("The wait for TM1 to be set up", () => {
  const exit = "The container exited while starting, with code 3. The end of its log:\nTM1 can't reach its datastore";
  const probe = (health: (asked: number) => number, exited: (asked: number) => string | undefined = () => undefined) => {
    const calls = { health: 0, exited: 0 };
    return {
      calls,
      exited: async () => exited(++calls.exited),
      health: async () => health(++calls.health),
      logs: async () => "the log",
    };
  };

  it("returns once the image's health command passes", async () => {
    const container = probe((asked) => (asked < 3 ? 1 : 0));
    await Tm1EngineContainer.awaitSetup(container, 60_000, 1);
    expect(container.calls.health).toBe(3);
  });

  it("fails at once, with the exit code and the end of the log, when the container has exited", async () => {
    const container = probe(
      () => 1,
      (asked) => (asked < 3 ? undefined : exit),
    );
    await expect(Tm1EngineContainer.awaitSetup(container, 60_000, 1)).rejects.toThrow(exit);
    // Not asked of a container that's gone
    expect(container.calls.health).toBe(2);
  });

  it("says the container exited when that is why it couldn't be asked", async () => {
    const container = probe(
      () => {
        throw new Error("container is not running");
      },
      (asked) => (asked < 2 ? undefined : exit),
    );
    await expect(Tm1EngineContainer.awaitSetup(container, 60_000, 1)).rejects.toThrow(exit);
    const unreachable = probe(() => {
      throw new Error("no such exec");
    });
    await expect(Tm1EngineContainer.awaitSetup(unreachable, 60_000, 1)).rejects.toThrow("Couldn't ask the container whether TM1 is set up");
  });

  it("times out, with the log, when TM1 is never set up", async () => {
    const container = probe(() => 1);
    await expect(Tm1EngineContainer.awaitSetup(container, 20, 1)).rejects.toThrow("Timed out after PT0.02S waiting for TM1 to be set up. the log");
    await expect(
      Tm1EngineContainer.awaitSetup(
        probe(() => 1),
        0,
        1,
      ),
    ).rejects.toThrow(/^Timed out after PT0S waiting for TM1 to be set up\. /);
  });
});
