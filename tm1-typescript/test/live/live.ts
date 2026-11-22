import { randomUUID } from "node:crypto";
import { it } from "vitest";
import { Tm1Authenticator, Tm1Client, Tm1ClientBuilder } from "../../src/index.js";

/** The settings that start TM1 in Docker for the Java tests (tm1-testcontainers). */
const SOURCES = ["TM1_PRIVATE_IMAGE", "TM1_HOME_DIR", "TM1_INSTALL_VOLUME", "TM1_BUNDLE_DIR", "TM1_BUNDLE_URL"];

const env = (name: string): string | undefined => {
  const v = process.env[name];
  return v && v.trim() ? v.trim() : undefined;
};

/** Where the TM1 under test comes from, or why there's none. */
export interface Target {
  url: string;
  user: string;
  password: string;
  namespace: string | undefined;
}

/** A test against a real TM1, as the Java client's `*Tm1Test` classes and the Python and .NET
 * clients' live tests. The server is one already running, `TM1_URL`, with `TM1_USER` and
 * `TM1_PASSWORD` (default `admin` and `apple`), `TM1_NAMESPACE` for CAM and `TM1_VERIFY=false` for a
 * self-signed certificate. Starting TM1 in Docker from the Java tests' sources (`TM1_PRIVATE_IMAGE`,
 * `TM1_HOME_DIR`, …) comes with the testcontainers port: {@link Live.containerTarget} is the hook.
 * Without a server the tests are skipped. Tests make objects named `tm1ts_test_…` and delete them
 * afterwards. */
export const Live = {
  /** A container started for the run, once ported; undefined until then. */
  containerTarget: undefined as (() => Promise<Target>) | undefined,

  get configured(): boolean {
    return env("TM1_URL") !== undefined || (Live.containerTarget !== undefined && SOURCES.some((s) => env(s) !== undefined));
  },

  get reason(): string {
    if (SOURCES.some((s) => env(s) !== undefined) && !Live.containerTarget) return "TM1 in Docker (TM1_PRIVATE_IMAGE and the like) isn't ported to the TypeScript tests yet: set TM1_URL for a server that's already running";
    return "No TM1 configured: set TM1_URL (with TM1_USER, TM1_PASSWORD) for a server that's already running";
  },

  /** The REST root and login of the TM1 to test against. */
  async target(): Promise<Target> {
    const url = env("TM1_URL");
    if (url) return { url, user: env("TM1_USER") ?? "admin", password: env("TM1_PASSWORD") ?? "apple", namespace: env("TM1_NAMESPACE") };
    if (Live.containerTarget) return Live.containerTarget();
    throw new Error(Live.reason);
  },

  /** Settings for a client of the TM1 under test: as the target's user, or as `user` with TM1's own security. */
  async builder(user?: string, password?: string): Promise<Tm1ClientBuilder> {
    const t = await Live.target();
    // Node's fetch takes no per-request certificate options: TM1_VERIFY=false turns checking off for the run
    if (env("TM1_VERIFY") === "false") process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = "0";
    const authenticator = user !== undefined ? Tm1Authenticator.basic(user, password) : t.namespace ? Tm1Authenticator.camNamespace(t.user, t.password, t.namespace) : Tm1Authenticator.basic(t.user, t.password);
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
