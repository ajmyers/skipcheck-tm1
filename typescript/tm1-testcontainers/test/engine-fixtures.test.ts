// SPDX-License-Identifier: Apache-2.0

import { Tm1Version } from "@skipcheck/tm1-client";
import { afterAll, describe, expect, it, vi } from "vitest";
import { DockerHost, IllegalArgumentException, Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "../src/index.js";
import { RequiresTm1, RequiresTm1Engine, requiresTm1Engine } from "../src/vitest.js";

const SOURCES = [
  Tm1EngineSource.PRIVATE_IMAGE,
  Tm1EngineSource.HOME_DIR,
  Tm1EngineSource.INSTALL_VOLUME,
  Tm1EngineSource.BUNDLE_DIR,
  Tm1EngineSource.BUNDLE,
  Tm1EngineSource.BUNDLE_URL,
  "TM1_BUNDLE_DIR",
];

/** requiresTm1Engine's fixtures, without Docker: an engine that only records what it's asked for
 * stands in for the shared one, and a source is set, so the tests run wherever this does. What the
 * real engine does with the same calls is engine-containers.test.ts's and engine-examples.test.ts's. */
describe("requiresTm1Engine", () => {
  const saved = Object.fromEntries(SOURCES.map((s) => [s, process.env[s]]));
  for (const s of SOURCES) delete process.env[s];
  process.env[Tm1EngineSource.INSTALL_VOLUME] = "made-up";
  const docker = vi.spyOn(DockerHost, "available").mockResolvedValue(true);

  const created: string[] = [];
  const dropped: string[] = [];
  const engine = new (class extends Tm1EngineContainer {
    override isRunning(): boolean {
      return true;
    }
    override getTm1Version(): Tm1Version {
      return Tm1Version.parse("12.6.7");
    }
    override async createDatabase(name: string): Promise<Tm1Database> {
      created.push(name);
      return new Tm1Database(this, Tm1EngineContainer.DEFAULT_INSTANCE, name);
    }
    override async dropDatabase(database: Tm1Database): Promise<void> {
      dropped.push(database.getName());
    }
    override database(name: string): Tm1Database {
      return new Tm1Database(this, Tm1EngineContainer.DEFAULT_INSTANCE, name);
    }
  })(Tm1EngineSource.discover()!);
  const shared = vi.spyOn(Tm1EngineContainer, "shared").mockResolvedValue(engine);

  afterAll(() => {
    shared.mockRestore();
    docker.mockRestore();
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  });

  requiresTm1Engine("makes no database for a test that doesn't ask for one", ({ tm1Engine }) => {
    expect(tm1Engine).toBe(engine);
    expect(created).toEqual([]);
  });

  let first: Tm1Database;

  requiresTm1Engine("names the file's database after the file", ({ tm1Database }) => {
    first = tm1Database;
    expect(created).toHaveLength(1);
    expect(tm1Database.getName()).toBe(created[0]);
    expect(tm1Database.getName()).toMatch(/^enginefixtures_[0-9a-z]{4}$/);
    expect(tm1Database.getInstance()).toBe("tm1");
  });

  requiresTm1Engine("gives every test of the file the one database", ({ tm1Database }) => {
    expect(tm1Database).toBe(first);
    expect(created).toHaveLength(1);
    // Still there: it's dropped when the file is done
    expect(dropped).toEqual([]);
  });

  describe("sharing", () => {
    const requiresSharedDatabase = requiresTm1Engine.extend({ shareDatabase: true });

    requiresSharedDatabase("gives the engine's own database when sharing", ({ tm1Database }) => {
      expect(tm1Database.getName()).toBe(Tm1EngineContainer.DEFAULT_DATABASE);
      expect(tm1Database.equals(first)).toBe(false);
      expect(created).toHaveLength(1);
    });
  });

  describe("with a setting of its own", () => {
    const requiresTm1V12 = requiresTm1Engine.extend({ minVersion: "12" });

    requiresTm1V12("still gets the file's one database", ({ tm1Database }) => {
      expect(tm1Database).toBe(first);
      expect(created).toHaveLength(1);
    });
  });

  requiresTm1Engine("keeps the database until the file's last test", ({ tm1Database }) => {
    expect(tm1Database).toBe(first);
    expect(created).toHaveLength(1);
    expect(dropped).toEqual([]);
  });

  describe("on an older engine", () => {
    const requiresNewer = requiresTm1Engine.extend({ minVersion: "99" });
    let ran = false;

    requiresNewer("skips a test that needs a newer TM1", () => {
      ran = true;
    });

    it("says which TM1 it needs and which it found", async () => {
      expect(ran).toBe(false);
      expect(await RequiresTm1Engine.skipReason("99")).toBe("Needs TM1 99 or later; the engine from install volume made-up is 12.6.7");
      expect(await RequiresTm1Engine.skipReason("12.6")).toBeUndefined();
      expect(await RequiresTm1Engine.skipReason()).toBeUndefined();
    });
  });
});

/** The skip helper, as vitest-helpers.test.ts has TM1 11's: skipped without Docker or a source, and
 * a source that's set but wrong fails the test rather than skipping it. */
describe("RequiresTm1Engine", () => {
  it("skips with Java's message without a source, and runs a test whose source is wrong", async () => {
    const saved = Object.fromEntries(SOURCES.map((s) => [s, process.env[s]]));
    try {
      for (const s of SOURCES) delete process.env[s];
      const available = await DockerHost.available();
      expect(await RequiresTm1Engine.skipReason()).toBe(available ? RequiresTm1Engine.NO_SOURCE_MESSAGE : RequiresTm1.NO_DOCKER_MESSAGE);
      await expect(Tm1EngineContainer.shared()).rejects.toThrow(RequiresTm1Engine.NO_SOURCE_MESSAGE);

      process.env[Tm1EngineSource.HOME_DIR] = "/not/a/tm1/engine/install";
      const reason = await RequiresTm1Engine.skipReason();
      // Never skipped for want of a source: run (and fail on it), or skipped for want of Docker
      expect(reason === undefined || reason === RequiresTm1.NO_DOCKER_MESSAGE).toBe(true);
      // What the fixtures call throws the source's own error
      await expect(Tm1EngineContainer.shared()).rejects.toThrow(IllegalArgumentException);
      await expect(Tm1EngineContainer.shared()).rejects.toThrow("Not a TM1 v12 install");
    } finally {
      for (const [k, v] of Object.entries(saved)) {
        if (v === undefined) delete process.env[k];
        else process.env[k] = v;
      }
    }
  });
});
