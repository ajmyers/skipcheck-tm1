// SPDX-License-Identifier: Apache-2.0

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { describe, expect } from "vitest";
import { type GenericContainer, PlanningAnalyticsEnvironment } from "../src/index.js";
import { RequiresTm1, RequiresTm1Engine, requiresCognos, requiresTm1, requiresTm1Engine, requiresTm1Web } from "../src/vitest.js";
import { Repo } from "./support/files.js";

/** Java's ContainerConformanceTest: starts each scenario of testcontainers-conformance/scenarios, a
 * pa-environment.yaml, and writes what Docker says it got (docker inspect of each container and of
 * the network) to build/container-conformance/typescript/<scenario>.json.
 * scripts/compare-containers.py then requires every language to have got the same containers
 * (./gradlew containerConformance). */
describe("The containers Docker is asked for", () => {
  requiresTm1("one server", { timeout: 30 * 60_000 }, async () => {
    await record("one-server");
  });

  requiresTm1Web("servers and TM1 Web", { timeout: 30 * 60_000 }, async () => {
    await record("servers-and-web");
  });

  requiresCognos("servers in CAM security", { timeout: 45 * 60_000 }, async () => {
    await record("cam-security");
  });

  // No database of the file's own is asked for: the environment starts its own engine
  requiresTm1Engine("engine", { timeout: 30 * 60_000 }, async () => {
    await record("engine");
  });

  requiresTm1Engine("engine with OAuth2", { timeout: 30 * 60_000 }, async () => {
    await record("engine-oauth2");
  });

  // As Java's @RequiresTm1 with @RequiresTm1Engine
  requiresTm1Engine("engine with pass-through", { timeout: 30 * 60_000 }, async ({ skip }) => {
    const why = await RequiresTm1.skipReason();
    if (why !== undefined) skip(why);
    await record("engine-passthrough");
  });

  // As Java's @RequiresCognos with @RequiresTm1Engine
  requiresCognos("engine with pass-through to CAM security", { timeout: 45 * 60_000 }, async ({ skip }) => {
    const why = await RequiresTm1Engine.skipReason();
    if (why !== undefined) skip(why);
    await record("engine-passthrough-cam");
  });
});

async function record(scenario: string): Promise<void> {
  const home = join(Repo.root, "testcontainers-conformance", "scenarios");
  await using env = PlanningAnalyticsEnvironment.fromConfig(join(home, scenario + ".yaml")).build();
  await env.start();

  const containers = new Map<string, GenericContainer>();
  for (const [name, server] of env.tm1Servers()) containers.set("tm1/" + name, server);
  const admin = env.adminServer();
  if (admin) containers.set("adminServer", admin);
  if (scenario === "servers-and-web") containers.set("tm1Web", env.tm1Web());
  if (scenario === "engine-oauth2") {
    containers.set("tm1Engine", env.tm1Engine());
    containers.set("tm1EngineDatastore", env.tm1Engine().getDatastore());
    containers.set("keycloak", env.keycloak());
  }
  if (scenario === "engine-passthrough" || scenario === "engine-passthrough-cam") {
    containers.set("tm1Engine", env.tm1Engine());
    containers.set("tm1EngineDatastore", env.tm1Engine().getDatastore());
    if (scenario === "engine-passthrough-cam") containers.set("cognos", env.cognos());
  }
  if (scenario === "engine") {
    containers.set("tm1Engine", env.tm1Engine());
    containers.set("tm1EngineDatastore", env.tm1Engine().getDatastore());
  }
  if (scenario === "cam-security") {
    containers.set("cognos", env.cognos());
    containers.set("keycloak", env.keycloak());
  }
  // Each inspection as Docker's CLI prints it, so every language writes the same text and none
  // reads it through its own Docker client's types
  const parts = [...containers].map(([role, container]) => `"${role}": ${docker("inspect", container.getContainerId())}`);
  const network = docker("network", "inspect", await env.network().getId());
  const log = `{"scenario": "${scenario}", "language": "typescript",\n"network": ${network},\n"containers": {\n${parts.join(",\n")}}}\n`;
  const file = join(Repo.root, "build", "container-conformance", "typescript", scenario + ".json");
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, log);
  expect(containers.size).toBeGreaterThan(0);
}

function docker(...args: string[]): string {
  return execFileSync("docker", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).trim();
}
