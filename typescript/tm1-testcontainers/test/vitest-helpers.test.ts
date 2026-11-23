// SPDX-License-Identifier: Apache-2.0

import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { DockerHost, IllegalArgumentException, Tm1ServerContainer } from "../src/index.js";
import { RequiresCognos, RequiresPaw, RequiresTm1, RequiresTm1Web } from "../src/vitest.js";

const SOURCES = [
  "TM1_PRIVATE_IMAGE",
  "TM1_HOME_DIR",
  "TM1_INSTALL_VOLUME",
  "TM1_BUNDLE_DIR",
  "TM1_BUNDLE_URL",
  "PAW_ZIP",
  "PAW_BUNDLE_DIR",
  "PAW_ACCEPT_LICENSE",
];

/** The skip helpers, as Java's annotations: skipped without Docker or a source, and a source
 * that's set but wrong fails the test rather than skipping it. Each test sets the environment
 * itself, and puts it back. */
describe("The vitest helpers", () => {
  const saved = Object.fromEntries(SOURCES.map((s) => [s, process.env[s]]));
  afterEach(() => {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  });
  const clear = (): void => SOURCES.forEach((s) => delete process.env[s]);

  it("skip with Java's messages without a source", async () => {
    clear();
    const docker = await DockerHost.available();
    expect(await RequiresTm1.skipReason()).toBe(docker ? RequiresTm1.NO_SOURCE_MESSAGE : RequiresTm1.NO_DOCKER_MESSAGE);
    // The others need TM1 first, as Java's are meta-annotated with @RequiresTm1
    expect(await RequiresCognos.skipReason()).toBe(await RequiresTm1.skipReason());
    expect(await RequiresTm1Web.skipReason()).toBe(await RequiresTm1.skipReason());
    expect(await RequiresPaw.skipReason()).toBe(await RequiresTm1.skipReason());
    await expect(Tm1ServerContainer.shared()).rejects.toThrow(RequiresTm1.NO_SOURCE_MESSAGE);
  });

  it("run a test whose source is set but wrong, so it fails", async () => {
    clear();
    const missing = mkdtempSync(join(tmpdir(), "tm1-tc-"));
    rmSync(missing, { recursive: true });
    process.env["TM1_HOME_DIR"] = missing;
    const reason = await RequiresTm1.skipReason();
    // Never skipped for want of a source: run (and fail on it), or skipped for want of Docker
    expect(reason === undefined || reason === RequiresTm1.NO_DOCKER_MESSAGE).toBe(true);
    // What the fixture calls throws the source's own error
    await expect(Tm1ServerContainer.shared()).rejects.toThrow(IllegalArgumentException);
    await expect(Tm1ServerContainer.shared()).rejects.toThrow("Not a TM1 install");
  });

  it("skip PAW until its license is accepted", async () => {
    clear();
    if (!(await DockerHost.available())) return;
    const dir = mkdtempSync(join(tmpdir(), "tm1-tc-"));
    try {
      process.env["TM1_INSTALL_VOLUME"] = "tm1_tm1-install";
      process.env["PAW_BUNDLE_DIR"] = dir; // set but wrong: no zip in it
      expect(await RequiresPaw.skipReason()).toBeUndefined();
      delete process.env["PAW_BUNDLE_DIR"];
      expect(await RequiresPaw.skipReason()).toBe(RequiresPaw.NO_SOURCE_MESSAGE);
      const zip = join(dir, "ipa_workspace_local_2.1.19.1260.zip");
      process.env["PAW_ZIP"] = zip;
      // A zip that isn't there is a wrong setting: run, to fail
      expect(await RequiresPaw.skipReason()).toBeUndefined();
      writeFileSync(zip, "");
      expect(await RequiresPaw.skipReason()).toBe(RequiresPaw.NO_LICENSE_MESSAGE);
      process.env["PAW_ACCEPT_LICENSE"] = "true";
      expect(await RequiresPaw.skipReason()).toBeUndefined();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
