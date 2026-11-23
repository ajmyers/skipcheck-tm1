import { randomUUID } from "node:crypto";
import { it } from "vitest";
import { DockerHost, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { RequiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { Tm1Authenticator, Tm1Client, Tm1ClientBuilder } from "../../src/index.js";

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v && v.trim() ? v.trim() : undefined;
};

/** Where the TM1 under test is, and how to log in to it. */
export interface Target {
  url: string;
  user: string;
  password: string;
  /** The CAM namespace users log in through, or undefined for TM1's own security. */
  namespace: string | undefined;
}

const NOT_CONFIGURED =
  "No TM1 configured: set TM1_URL (with TM1_USER, TM1_PASSWORD) for a server that's already running, or one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or TM1_BUNDLE_URL to start one in Docker";

/** Why a TM1 in Docker can't be had, or undefined if it can. Docker is only asked when a source is
 * set; a source that's set but wrong runs the tests, which fail on it, as Java's `@RequiresTm1`. */
async function containerSkipReason(): Promise<string | undefined> {
  let source: Tm1Source | undefined;
  try {
    source = Tm1Source.discover();
  } catch {
    return undefined;
  }
  if (!source) return NOT_CONFIGURED;
  return (await DockerHost.available()) ? undefined : RequiresTm1.NO_DOCKER_MESSAGE;
}

const containerReason = env("TM1_URL") !== undefined ? undefined : await containerSkipReason();

/** The shared TM1 server in Docker (`@skipcheck/tm1-testcontainers`), started once per run: an
 * empty database, `admin` with no password, or CAM security if the container has it. */
async function sharedContainer(): Promise<Target> {
  const tm1 = await Tm1ServerContainer.shared();
  return { url: tm1.getRestUrl(), user: tm1.getUsername(), password: tm1.getPassword(), namespace: tm1.getCamNamespace()?.id() };
}

/** A test against a real TM1, as the Java client's `*Tm1Test` classes and the Python and .NET
 * clients' live tests. The server is:
 *
 * - one already running, when `TM1_URL` is set: with `TM1_USER` and `TM1_PASSWORD` (default
 *   `admin` and `apple`), `TM1_NAMESPACE` for CAM and `TM1_VERIFY=false` for a self-signed
 *   certificate;
 * - otherwise the shared TM1 server in Docker ({@link Live.containerTarget}), started from the
 *   Java tests' sources (`TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`,
 *   `TM1_BUNDLE_DIR`, `TM1_BUNDLE_URL`) with `@skipcheck/tm1-testcontainers`.
 *
 * Without either (or without Docker) the tests are skipped. Tests make objects named
 * `tm1ts_test_…` and delete them afterwards. */
export const Live = {
  /** Where the tests get a TM1 when `TM1_URL` isn't set: the shared container. Set it to
   * undefined to only test against a running server. */
  containerTarget: sharedContainer as (() => Promise<Target>) | undefined,

  /** Whether there's a TM1 to test against. */
  get configured(): boolean {
    return env("TM1_URL") !== undefined || (Live.containerTarget !== undefined && containerReason === undefined);
  },

  /** Why the tests are skipped. */
  get reason(): string {
    if (!Live.containerTarget) return "No TM1 configured: set TM1_URL (with TM1_USER, TM1_PASSWORD) for a server that's already running";
    return containerReason ?? NOT_CONFIGURED;
  },

  /** The REST root and login of the TM1 to test against. */
  async target(): Promise<Target> {
    const url = env("TM1_URL");
    if (url) return { url, user: env("TM1_USER") ?? "admin", password: env("TM1_PASSWORD") ?? "apple", namespace: env("TM1_NAMESPACE") };
    if (Live.containerTarget && containerReason === undefined) return Live.containerTarget();
    throw new Error(Live.reason);
  },

  /** Settings for a client of the TM1 under test: as the target's user, or as `user` (and
   * `password`), who logs in the same way: through the target's CAM namespace (the container's,
   * or `TM1_NAMESPACE`) if it has one, with TM1's own security otherwise. */
  async builder(user?: string, password?: string): Promise<Tm1ClientBuilder> {
    const t = await Live.target();
    // Node's fetch takes no per-request certificate options: TM1_VERIFY=false turns checking off for the run
    if (env("TM1_VERIFY") === "false") process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = "0";
    const [u, p] = user !== undefined ? [user, password ?? ""] : [t.user, t.password];
    const authenticator = t.namespace ? Tm1Authenticator.camNamespace(u, p, t.namespace) : Tm1Authenticator.basic(u, p);
    return Tm1Client.builder().baseUrl(t.url).authenticator(authenticator);
  },

  /** A new client of the TM1 under test. */
  async client(): Promise<Tm1Client> {
    return (await Live.builder()).build();
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
