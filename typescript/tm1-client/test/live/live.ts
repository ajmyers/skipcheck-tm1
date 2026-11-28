// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { inject, it } from "vitest";
import { CognosSource, Tm1EngineSource, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { RequiresCognos, RequiresTm1, RequiresTm1Engine } from "@skipcheck/tm1-testcontainers/vitest";
import { Tm1Authenticator, Tm1Client, Tm1ClientBuilder, Tm1Dialect } from "../../src/index.js";
import type { LiveTm1, Target } from "./global-setup.js";

export type { Target };

const NOT_CONFIGURED =
  "No TM1 configured: set TM1_URL (with TM1_USER, TM1_PASSWORD) for a server that's already running, or one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or TM1_BUNDLE_URL (or TM1_ENGINE_* for a TM1 v12 engine) to start one in Docker";

// What global-setup.ts found for the run. Nothing is provided to a file outside the "live" project
// (the CAM files, which start their own servers).
const provided: LiveTm1 = inject("liveTm1") ?? { skip: "no-source" };

/** A test against a real TM1, as the Java client's `*Tm1Test` classes and the Python and .NET
 * clients' live tests. Every file tests against the same server, found once per run by
 * `global-setup.ts`, and the files run one after the other, as Java's test classes do. The server is:
 *
 * - one already running, when `TM1_URL` is set: with `TM1_USER` and `TM1_PASSWORD` (default
 *   `admin` and `apple`), `TM1_NAMESPACE` for CAM and `TM1_VERIFY=false` for a self-signed
 *   certificate;
 * - otherwise one TM1 server in Docker for the run, started from the Java tests' sources
 *   (`TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR`,
 *   `TM1_BUNDLE_URL`) with `@skipcheck/tm1-testcontainers`.
 *
 * Without either (or without Docker) the tests are skipped. The server is shared, so tests make
 * objects named `tm1ts_test_…`, delete them afterwards, and don't assume it's otherwise empty. */
export const Live = {
  /** Whether there's a TM1 to test against. */
  get configured(): boolean {
    return provided.target !== undefined;
  },

  /** Whether the live tests are running against a TM1 v12 engine container. */
  get isEngine(): boolean {
    return provided.target?.engine !== undefined;
  },

  /** Why the tests are skipped. */
  get reason(): string {
    return provided.skip === "no-docker" ? RequiresTm1.NO_DOCKER_MESSAGE : NOT_CONFIGURED;
  },

  /** The REST root and login of the TM1 to test against. */
  async target(): Promise<Target> {
    if (provided.target) return provided.target;
    throw new Error(Live.reason);
  },

  /** Settings for a client of the TM1 under test: as the target's user, or as `user` (and
   * `password`), who logs in the same way: through the target's CAM namespace (the container's,
   * or `TM1_NAMESPACE`) if it has one, with TM1's own security otherwise. */
  async builder(user?: string, password?: string): Promise<Tm1ClientBuilder> {
    const t = await Live.target();
    if (t.engine) {
      return Tm1Client.builder()
        .baseUrl(t.url)
        .dialect(Tm1Dialect.v12(t.engine.instance, t.engine.database))
        .authenticator(Tm1Authenticator.application(t.engine.clientId, t.engine.clientSecret, user ?? t.user));
    }
    // Node's fetch takes no per-request certificate options: TM1_VERIFY=false turns checking off for the run
    if (process.env["TM1_VERIFY"]?.trim() === "false") process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = "0";
    const [u, p] = user !== undefined ? [user, password ?? ""] : [t.user, t.password];
    const authenticator = t.namespace ? Tm1Authenticator.camNamespace(u, p, t.namespace) : Tm1Authenticator.basic(u, p);
    return Tm1Client.builder().baseUrl(t.url).authenticator(authenticator);
  },

  /** A new client of the TM1 under test. */
  async client(): Promise<Tm1Client> {
    return (await Live.builder()).build();
  },

  /** An authenticator for `user` against the TM1 under test: application credentials on a v12
   * engine, or Basic credentials on TM1 11. */
  async authenticator(user: string, password: string): Promise<Tm1Authenticator> {
    const t = await Live.target();
    if (t.engine) {
      return Tm1Authenticator.application(t.engine.clientId, t.engine.clientSecret, user);
    }
    return Tm1Authenticator.basic(user, password);
  },

  /** A name for a test's objects. */
  name(): string {
    return "tm1ts_test_" + randomUUID().replace(/-/g, "").slice(0, 8);
  },
};

/** `it`, skipped without a TM1 to test against. */
export const tm1Test: typeof it = (Live.configured ? it : it.skip) as typeof it;

/** The reason the live tests are skipped, for a describe's title. */
export const liveReason = Live.configured ? "" : ` (skipped: ${Live.reason})`;

/** Why the tests that start their own Cognos can't run, or undefined if they can, as Java's
 * `@RequiresCognos`. Docker is only asked when a Cognos source is set; a source that's set but wrong
 * runs the tests, which fail on it. */
async function cognosSkipReason(): Promise<string | undefined> {
  try {
    if (!CognosSource.discover()) return RequiresCognos.NO_SOURCE_MESSAGE;
  } catch {
    // Set but wrong
  }
  return RequiresCognos.skipReason();
}

const cognosSkip = await cognosSkipReason();

/** `it`, skipped without a Cognos (and a TM1) to start in Docker: set a Cognos source
 * (`COGNOS_PRIVATE_IMAGE`, `COGNOS_INSTALL_VOLUME`, `COGNOS_ZIP_DIR` or `COGNOS_ZIP_URL`) and a TM1
 * source to run them. */
export const cognosTest: typeof it = (cognosSkip === undefined ? it : it.skip) as typeof it;

/** Whether the Cognos tests run. */
export const cognosConfigured = cognosSkip === undefined;

/** The reason the Cognos tests are skipped, for a describe's title. */
export const cognosReason = cognosSkip === undefined ? "" : ` (skipped: ${cognosSkip})`;

/** Why the tests that start their own TM1 v12 engine can't run, or undefined if they can, as Java's
 * `@RequiresTm1Engine`. */
async function engineSkipReason(): Promise<string | undefined> {
  try {
    if (!Tm1EngineSource.discover()) return RequiresTm1Engine.NO_SOURCE_MESSAGE;
  } catch {
    // Set but wrong
  }
  return RequiresTm1Engine.skipReason();
}

const engineSkip = await engineSkipReason();

/** `it`, skipped without a TM1 v12 engine source and Docker. */
export const engineTest: typeof it = (engineSkip === undefined ? it : it.skip) as typeof it;

/** Whether the TM1 v12 engine auth tests run. */
export const engineConfigured = engineSkip === undefined;

/** The reason the TM1 v12 engine auth tests are skipped, for a describe's title. */
export const engineReason = engineSkip === undefined ? "" : ` (skipped: ${engineSkip})`;

/** Why the tests that start both a TM1 11 server and a TM1 v12 engine can't run, or undefined. */
async function tm11SkipReason(): Promise<string | undefined> {
  try {
    if (!Tm1Source.discover()) return RequiresTm1.NO_SOURCE_MESSAGE;
  } catch {
    // Set but wrong
  }
  return RequiresTm1.skipReason();
}

const passThroughSkip = engineSkip ?? (await tm11SkipReason());

/** `it`, skipped without both TM1 11 and TM1 v12 engine sources and Docker. */
export const enginePassThroughTest: typeof it = (passThroughSkip === undefined ? it : it.skip) as typeof it;

/** Whether the TM1 v12 pass-through Basic auth tests run. */
export const enginePassThroughConfigured = passThroughSkip === undefined;

/** The reason the TM1 v12 pass-through Basic auth tests are skipped, for a describe's title. */
export const enginePassThroughReason = passThroughSkip === undefined ? "" : ` (skipped: ${passThroughSkip})`;

const passThroughCamSkip = cognosSkip ?? engineSkip;

/** `it`, skipped without Cognos, TM1 11, and TM1 v12 engine sources and Docker. */
export const enginePassThroughCamTest: typeof it = (passThroughCamSkip === undefined ? it : it.skip) as typeof it;

/** Whether the TM1 v12 pass-through CAM auth tests run. */
export const enginePassThroughCamConfigured = passThroughCamSkip === undefined;

/** The reason the TM1 v12 pass-through CAM auth tests are skipped, for a describe's title. */
export const enginePassThroughCamReason = passThroughCamSkip === undefined ? "" : ` (skipped: ${passThroughCamSkip})`;
