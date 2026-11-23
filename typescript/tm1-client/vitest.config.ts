// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const source = (path: string): string => fileURLToPath(new URL(path, import.meta.url));

/** The live tests that start their own Cognos. */
const CAM = "test/live/Cam*.live.test.ts";

export default defineConfig({
  resolve: {
    // The workspace's packages from their sources, as tsconfig.json's paths: test/live starts TM1
    // with @skipcheck/tm1-testcontainers, which uses this package
    alias: [
      { find: /^@skipcheck\/tm1-client$/, replacement: source("./src/index.ts") },
      { find: /^@skipcheck\/tm1-testcontainers$/, replacement: source("../tm1-testcontainers/src/index.ts") },
      { find: /^@skipcheck\/tm1-testcontainers\/vitest$/, replacement: source("../tm1-testcontainers/src/vitest.ts") },
    ],
  },
  test: {
    // With TEST_RESULTS set (./gradlew checkTypescript sets it), a JUnit report too, for scripts/test-summary.py
    reporters: process.env.TEST_RESULTS ? ["default", ["junit", { outputFile: `${process.env.TEST_RESULTS}/tm1-client.xml` }]] : ["default"],
    testTimeout: 20_000,
    // The live tests' setup may start TM1 in Docker: a bundle's first install takes minutes
    hookTimeout: 10 * 60_000,
    projects: [
      {
        // The offline tests, in parallel
        extends: true,
        test: { name: "tests", include: ["test/**/*.test.ts"], exclude: ["test/live/**"] },
      },
      {
        // The live tests share one TM1, found (and, in Docker, started) once per run by the global
        // setup, and run one file after the other, as Java's test classes do: a TM1 for each file,
        // all at once, is more than a machine takes
        extends: true,
        test: {
          name: "live",
          include: ["test/live/*.live.test.ts"],
          exclude: [CAM],
          globalSetup: ["test/live/global-setup.ts"],
          fileParallelism: false,
        },
      },
      {
        // Each CAM test file starts a Cognos of its own, as Java's classes do, and Cognos is too
        // heavy to run two at once: these files run one after the other too
        extends: true,
        test: { name: "cam", include: [CAM], fileParallelism: false },
      },
    ],
  },
});
