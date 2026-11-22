import { test, type TestAPI } from "vitest";
import { CognosSource } from "./CognosSource.js";
import { DockerHost } from "./docker.js";
import { Messages } from "./messages.js";
import { PawContainer } from "./PawContainer.js";
import { PawSource } from "./PawSource.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";
import { Tm1Source } from "./Tm1Source.js";
import { Tm1WebContainer } from "./Tm1WebContainer.js";
import { Tm1WebSource } from "./Tm1WebSource.js";

/** Whether a source is set: `true` for one set wrong, so the test runs and fails on it (the
 * fixture's `discover` throws the same error), as a wrong setting fails Java's tests rather than
 * skipping them. */
function discovers(discover: () => unknown): boolean {
  try {
    return discover() !== undefined;
  } catch {
    return true;
  }
}

/** Java's `@RequiresTm1`: tests against TM1 in Docker, skipped when Docker isn't available or no
 * {@link Tm1Source} is configured, with Java's messages. */
export const RequiresTm1 = {
  /** Why a test is skipped without a source. */
  NO_SOURCE_MESSAGE: Messages.TM1_NO_SOURCE,
  /** Why a test is skipped without Docker. */
  NO_DOCKER_MESSAGE: Messages.TM1_NO_DOCKER,

  /** Why a TM1 test would be skipped, or `undefined` to run it (a wrong source runs it, to fail). */
  async skipReason(): Promise<string | undefined> {
    if (!(await DockerHost.available())) return Messages.TM1_NO_DOCKER;
    return discovers(() => Tm1Source.discover()) ? undefined : Messages.TM1_NO_SOURCE;
  },
};

/** Java's `@RequiresCognos`: tests against Cognos (and TM1). */
export const RequiresCognos = {
  NO_SOURCE_MESSAGE: Messages.COGNOS_NO_SOURCE,

  async skipReason(): Promise<string | undefined> {
    return (await RequiresTm1.skipReason()) ?? (discovers(() => CognosSource.discover()) ? undefined : Messages.COGNOS_NO_SOURCE);
  },
};

/** Java's `@RequiresTm1Web`: tests against TM1 Web (and TM1). */
export const RequiresTm1Web = {
  NO_SOURCE_MESSAGE: Messages.TM1_WEB_NO_SOURCE,

  async skipReason(): Promise<string | undefined> {
    return (await RequiresTm1.skipReason()) ?? (discovers(() => Tm1WebSource.discover()) ? undefined : Messages.TM1_WEB_NO_SOURCE);
  },
};

/** Java's `@RequiresPaw`: tests against PAW (and TM1), also skipped until IBM's license is
 * accepted. */
export const RequiresPaw = {
  NO_SOURCE_MESSAGE: Messages.PAW_NO_SOURCE,
  NO_LICENSE_MESSAGE: Messages.PAW_NO_LICENSE,

  async skipReason(): Promise<string | undefined> {
    const tm1 = await RequiresTm1.skipReason();
    if (tm1 !== undefined) return tm1;
    let source: PawSource | undefined;
    try {
      source = PawSource.discover();
    } catch {
      return undefined; // set but wrong: run, to fail on it, before the license counts
    }
    if (!source) return Messages.PAW_NO_SOURCE;
    return PawContainer.licenseAcceptedBySetting() ? undefined : Messages.PAW_NO_LICENSE;
  },
};

/** What a `requiresTm1` test gets: the shared TM1 server, started on first use. */
export interface Tm1Fixtures {
  tm1: Tm1ServerContainer;
}

/** What a `requiresCognos` test gets: the discovered Cognos source (Cognos tests start their own
 * Cognos, with their own users), and the shared TM1 server. */
export interface CognosFixtures extends Tm1Fixtures {
  cognosSource: CognosSource;
}

/** What a `requiresTm1Web` test gets: the shared TM1 Web, and the shared TM1 server it lists. */
export interface Tm1WebFixtures extends Tm1Fixtures {
  tm1Web: Tm1WebContainer;
}

/** What a `requiresPaw` test gets: the shared PAW, and the shared TM1 server it logs on to. */
export interface PawFixtures extends Tm1Fixtures {
  paw: PawContainer;
}

type Skip = (note?: string) => never;

/** A check run before every test: skips it with Java's message, else lets it run. */
function check(reason: () => Promise<string | undefined>): [(context: { skip: Skip }, use: (value: void) => Promise<void>) => Promise<void>, { auto: boolean }] {
  return [
    async ({ skip }: { skip: Skip }, use: (value: void) => Promise<void>) => {
      const why = await reason();
      if (why !== undefined) skip(why);
      await use();
    },
    { auto: true },
  ];
}

/** vitest's `test` for tests against TM1, as Java's `@RequiresTm1`: skipped (with Java's message)
 * when Docker isn't available or no TM1 source is configured; run, and failing, when a source is
 * set but wrong. A test that names `tm1` gets the shared server, as Java injects a
 * `Tm1ServerContainer` parameter: one per run, started on first use and shared by every test
 * file in a worker.
 *
 * ```ts
 * import { requiresTm1 } from "@ajmyers/tm1-testcontainers/vitest";
 *
 * requiresTm1("lists cubes", async ({ tm1 }) => {
 *   const url = tm1.getRestUrl(); // http://localhost:54321/api/v1/
 *   ...
 * });
 * ```
 */
export const requiresTm1: TestAPI<Tm1Fixtures> = test.extend<Tm1Fixtures & { requiresTm1: void }>({
  requiresTm1: check(RequiresTm1.skipReason),
  // Named, so the check runs first
  tm1: async ({ requiresTm1 }, use) => use((void requiresTm1, await Tm1ServerContainer.shared())),
}) as unknown as TestAPI<Tm1Fixtures>;

/** vitest's `test` for tests against Cognos, as Java's `@RequiresCognos`. */
export const requiresCognos: TestAPI<CognosFixtures> = requiresTm1.extend<{ cognosSource: CognosSource; requiresCognos: void; tm1: Tm1ServerContainer }>({
  // The shared TM1 server only once this check passes
  tm1: async ({ requiresCognos }, use) => use((void requiresCognos, await Tm1ServerContainer.shared())),
  requiresCognos: check(RequiresCognos.skipReason),
  cognosSource: async ({ requiresCognos }, use) => {
    void requiresCognos;
    const source = CognosSource.discover();
    if (!source) throw new Error(Messages.COGNOS_NO_SOURCE);
    await use(source);
  },
}) as unknown as TestAPI<CognosFixtures>;

/** vitest's `test` for tests against TM1 Web, as Java's `@RequiresTm1Web`. */
export const requiresTm1Web: TestAPI<Tm1WebFixtures> = requiresTm1.extend<{ tm1Web: Tm1WebContainer; requiresTm1Web: void; tm1: Tm1ServerContainer }>({
  // The shared TM1 server only once this check passes
  tm1: async ({ requiresTm1Web }, use) => use((void requiresTm1Web, await Tm1ServerContainer.shared())),
  requiresTm1Web: check(RequiresTm1Web.skipReason),
  tm1Web: async ({ requiresTm1Web }, use) => use((void requiresTm1Web, await Tm1WebContainer.shared())),
}) as unknown as TestAPI<Tm1WebFixtures>;

/** vitest's `test` for tests against PAW, as Java's `@RequiresPaw`. */
export const requiresPaw: TestAPI<PawFixtures> = requiresTm1.extend<{ paw: PawContainer; requiresPaw: void; tm1: Tm1ServerContainer }>({
  // The shared TM1 server only once this check passes
  tm1: async ({ requiresPaw }, use) => use((void requiresPaw, await Tm1ServerContainer.shared())),
  requiresPaw: check(RequiresPaw.skipReason),
  paw: async ({ requiresPaw }, use) => use((void requiresPaw, await PawContainer.shared())),
}) as unknown as TestAPI<PawFixtures>;
