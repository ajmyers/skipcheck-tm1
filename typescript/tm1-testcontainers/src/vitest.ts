// SPDX-License-Identifier: Apache-2.0

import { randomInt } from "node:crypto";
import { basename } from "node:path";
import { Tm1Version } from "@skipcheck/tm1-client";
import { test, type TestAPI } from "vitest";
import { CognosSource } from "./CognosSource.js";
import { DockerHost } from "./docker.js";
import { Messages } from "./messages.js";
import { PawContainer } from "./PawContainer.js";
import { PawSource } from "./PawSource.js";
import type { Tm1Database } from "./Tm1Database.js";
import { Tm1EngineContainer } from "./Tm1EngineContainer.js";
import { Tm1EngineSource } from "./Tm1EngineSource.js";
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

/** Java's `@RequiresTm1Engine`: tests against a TM1 v12 engine in Docker, skipped when Docker isn't
 * available or no {@link Tm1EngineSource} is configured, with Java's messages. For TM1 11, see
 * {@link RequiresTm1}. */
export const RequiresTm1Engine = {
  /** Why tests skip when no source is configured, and what to set. */
  NO_SOURCE_MESSAGE: Messages.TM1_ENGINE_NO_SOURCE,

  /** Why a TM1 v12 test would be skipped, or `undefined` to run it (a wrong source runs it, to
   * fail). With a `minVersion`, such as `12.6`, it's also skipped on an older engine; checking
   * that starts the shared engine. */
  async skipReason(minVersion = ""): Promise<string | undefined> {
    if (!(await DockerHost.available())) return Messages.TM1_NO_DOCKER;
    let source: Tm1EngineSource | undefined;
    try {
      source = Tm1EngineSource.discover();
    } catch {
      return undefined; // set but wrong: run, to fail on it
    }
    if (!source) return Messages.TM1_ENGINE_NO_SOURCE;
    if (minVersion.trim() !== "") {
      const running = (await Tm1EngineContainer.shared()).getTm1Version();
      if (running.isBefore(Tm1Version.parse(minVersion))) {
        return `Needs TM1 ${minVersion} or later; the engine from ${source.describe()} is ${running.toString()}`;
      }
    }
    return undefined;
  },

  /** The name of a test file's database: the file's name, as far as a database's name allows, and
   * `suffix`, padded to four characters so the name is always long enough for TM1.
   * @internal */
  databaseName(type: string, suffix: string): string {
    const letters = type.toLowerCase().replace(/[^a-z0-9]/g, "");
    const start = letters === "" ? "test" : letters.slice(0, 24);
    return start + "_" + ("0000" + suffix).slice(suffix.length);
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
  /** The shared TM1 server, started on first use. */
  tm1: Tm1ServerContainer;
}

/** What a `requiresCognos` test gets: the discovered Cognos source (Cognos tests start their own
 * Cognos, with their own users), and the shared TM1 server. */
export interface CognosFixtures extends Tm1Fixtures {
  /** Where Cognos comes from, as discovered. */
  cognosSource: CognosSource;
}

/** What a `requiresTm1Web` test gets: the shared TM1 Web, and the shared TM1 server it lists. */
export interface Tm1WebFixtures extends Tm1Fixtures {
  /** The shared TM1 Web, started on first use. */
  tm1Web: Tm1WebContainer;
}

/** What a `requiresPaw` test gets: the shared PAW, and the shared TM1 server it logs on to. */
export interface PawFixtures extends Tm1Fixtures {
  /** The shared PAW, started on first use. */
  paw: PawContainer;
}

/** What a `requiresTm1Engine` test gets, and the two settings of Java's `@RequiresTm1Engine`. */
export interface Tm1EngineFixtures {
  /** The shared TM1 v12 engine ({@link Tm1EngineContainer.shared}), started on first use: one per
   * test file under vitest's default isolation. */
  tm1Engine: Tm1EngineContainer;
  /** The test file's own database, created when a test of the file first asks for it and dropped
   * when the file is done; the engine's shared one, `tm1`, with {@link shareDatabase}. */
  tm1Database: Tm1Database;
  /** Whether the tests use the engine's shared database, `tm1`, rather than one of the file's
   * own; `false` unless set: `requiresTm1Engine.extend({ shareDatabase: true })`. With an engine
   * per test file (vitest's default), that database is the file's alone anyway. */
  shareDatabase: boolean;
  /** The oldest TM1 the tests apply to, such as `12.6`; they're skipped on an older engine.
   * Checking it starts the engine. Empty, for any, unless set:
   * `requiresTm1Engine.extend({ minVersion: "12.6" })`. */
  minVersion: string;
}

/** A test file's own database: made when first asked for, and dropped when the file is done. */
interface FileDatabase {
  get(file: string): Promise<Tm1Database>;
}

type Skip = (note?: string) => never;

/** A check run before every test: skips it with Java's message, else lets it run. */
function check(
  reason: () => Promise<string | undefined>,
): [(context: { skip: Skip }, use: (value: void) => Promise<void>) => Promise<void>, { auto: boolean }] {
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
 * import { requiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
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

/** vitest's `test` for tests against a TM1 v12 engine, as Java's `@RequiresTm1Engine`: skipped
 * (with Java's message) when Docker isn't available or no TM1 v12 source is configured; run, and
 * failing, when a source is set but wrong. For TM1 11, use {@link requiresTm1}.
 *
 * The tests share an engine ({@link Tm1EngineContainer.shared}), and **each test file gets a
 * database of its own** in it, created when a test of the file first names `tm1Database` and
 * dropped when the file is done. So files don't see each other's objects. A test that names
 * `tm1Engine` gets the engine.
 *
 * **How many engines:** one per module registry. Under vitest's default isolation every test file
 * has its own modules, so that is one engine **per test file**, not one for the run, started by
 * the file's first test that needs it (about 40 seconds). One engine serves the whole run only
 * when the files share their modules: `isolate: false`, or a single worker without isolation.
 *
 * ```ts
 * import { requiresTm1Engine } from "@skipcheck/tm1-testcontainers/vitest";
 *
 * // A database of this file's own, dropped when the file is done
 * requiresTm1Engine("lists cubes", async ({ tm1Database: db }) => {
 *   const response = await fetch(db.getRestUrl() + "Cubes?$select=Name", await db.login().authorize());
 *   expect(response.status).toBe(200);
 * });
 * ```
 *
 * A database takes about 10 seconds to create and 7 to drop.
 * `requiresTm1Engine.extend({ shareDatabase: true })` is a `test` whose tests get the engine's one
 * shared database, `tm1`, instead, at no cost. With an engine per test file, that database is the
 * file's alone, so sharing loses no isolation and is the option to use there. Where one engine
 * serves several files, its tests need uniquely named objects, and to delete them afterwards.
 * `extend({ minVersion: "12.6" })` skips its tests on an older engine.
 *
 * "A test class", which Java gives a database, is the unit each language's test framework shares
 * set-up over: a JUnit class there, a module in pytest, a class in xUnit, and a file here. */
export const requiresTm1Engine: TestAPI<Tm1EngineFixtures> = test.extend<Tm1EngineFixtures & { requiresTm1Engine: void; tm1FileDatabase: FileDatabase }>({
  shareDatabase: false,
  minVersion: "",
  requiresTm1Engine: [
    async ({ skip, minVersion }, use) => {
      const why = await RequiresTm1Engine.skipReason(minVersion);
      if (why !== undefined) skip(why);
      await use();
    },
    { auto: true },
  ],
  // Named, so the check runs first
  tm1Engine: async ({ requiresTm1Engine }, use) => use((void requiresTm1Engine, await Tm1EngineContainer.shared())),
  // The file's, so the database is dropped after the file's last test. The database itself is kept
  // by the file's path, not here: a test extended from this one (with shareDatabase or minVersion)
  // has file fixtures of its own, and the file still has the one database
  tm1FileDatabase: [
    // eslint-disable-next-line no-empty-pattern -- vitest reads a fixture's needs from this pattern
    async ({}, use) => {
      const asked = new Set<string>();
      await use({
        get(file) {
          asked.add(file);
          let made = fileDatabases.get(file);
          if (!made) {
            made = Tm1EngineContainer.shared()
              .then((engine) => engine.createDatabase(RequiresTm1Engine.databaseName(unit(file), randomInt(36 ** 4).toString(36))))
              .catch((e: unknown) => {
                fileDatabases.delete(file);
                throw e;
              });
            fileDatabases.set(file, made);
          }
          return made;
        },
      });
      for (const file of asked) {
        const made = fileDatabases.get(file);
        fileDatabases.delete(file);
        const database = await made?.catch(() => undefined);
        if (database?.getEngine().isRunning()) await database.getEngine().dropDatabase(database);
      }
    },
    { scope: "file" },
  ],
  tm1Database: async ({ requiresTm1Engine, shareDatabase, tm1FileDatabase, task }, use) => {
    void requiresTm1Engine;
    if (shareDatabase) await use((await Tm1EngineContainer.shared()).database(Tm1EngineContainer.DEFAULT_DATABASE));
    else await use(await tm1FileDatabase.get(task.file.filepath));
  },
}) as unknown as TestAPI<Tm1EngineFixtures>;

/** Each test file's own database, by the file's path, from when a test of it first asks. */
const fileDatabases = new Map<string, Promise<Tm1Database>>();

/** A test file's name without its extensions: `cubes` for `test/cubes.test.ts`. */
function unit(file: string): string {
  return basename(file).replace(/(?:\.(?:test|spec))?\.[cm]?[jt]sx?$/, "");
}
