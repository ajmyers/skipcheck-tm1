// SPDX-License-Identifier: Apache-2.0

import { DockerHost, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import type { TestProject } from "vitest/node";

/** Where the TM1 under test is, and how to log in to it. */
export interface Target {
  url: string;
  user: string;
  password: string;
  /** The CAM namespace users log in through, or undefined for TM1's own security. */
  namespace: string | undefined;
  /** The shared container's Docker ID, or undefined for a server at `TM1_URL`. */
  containerId: string | undefined;
  /** The version the shared container read as it started, or undefined for a server at `TM1_URL`. */
  version: string | undefined;
}

/** What the live test files are given: the TM1 to test against, or why there isn't one. */
export type LiveTm1 = { target: Target; skip?: undefined } | { target?: undefined; skip: "no-source" | "no-docker" };

declare module "vitest" {
  export interface ProvidedContext {
    liveTm1: LiveTm1;
  }
}

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v && v.trim() ? v.trim() : undefined;
};

/** Once per run of the live tests, before any of their files: finds the TM1 they all test against,
 * as Java's `@RequiresTm1` gives every test class the one shared `Tm1ServerContainer`.
 *
 * - `TM1_URL` set: that server; no container starts.
 * - Otherwise a TM1 source set (and Docker there): one TM1 server in Docker, started here and
 *   stopped when the run ends. A source that's set but wrong fails the run, as in Java.
 * - Neither: no container starts, and the files skip their tests with the reason.
 *
 * It runs in vitest's own process, so it can't use what needs a test (the vitest helpers). */
export default async function setup(project: TestProject): Promise<(() => Promise<void>) | void> {
  const url = env("TM1_URL");
  if (url) {
    const target = { url, user: env("TM1_USER") ?? "admin", password: process.env["TM1_PASSWORD"] ?? "apple", namespace: env("TM1_NAMESPACE") };
    project.provide("liveTm1", { target: { ...target, containerId: undefined, version: undefined } });
    return;
  }
  // Docker is only asked when a source is set
  if (!Tm1Source.discover()) return project.provide("liveTm1", { skip: "no-source" });
  if (!(await DockerHost.available())) return project.provide("liveTm1", { skip: "no-docker" });
  const tm1 = await Tm1ServerContainer.shared();
  project.provide("liveTm1", {
    target: {
      url: tm1.getRestUrl(),
      user: tm1.getUsername(),
      password: tm1.getPassword(),
      namespace: tm1.getCamNamespace()?.id(),
      containerId: tm1.getContainerId(),
      version: tm1.getTm1Version().toString(),
    },
  });
  return async () => {
    // With Testcontainers reuse switched on, it stays for the next run
    if (!DockerHost.reuseEnabled()) await tm1.stop();
  };
}
