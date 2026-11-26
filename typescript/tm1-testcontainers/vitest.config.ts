// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // The client from its sources, as tsconfig.json's paths
    alias: { "@skipcheck/tm1-client": fileURLToPath(new URL("../tm1-client/src/index.ts", import.meta.url)) },
  },
  test: {
    // With TEST_RESULTS set (./gradlew checkTypescript sets it), a JUnit report too, for
    // scripts/test-summary.py. Naming reporters turns off the ones vitest picks itself, so its own
    // choice is spelled out: default, and in GitHub Actions the one that annotates a pull request
    reporters: process.env.TEST_RESULTS
      ? [
          "default",
          ...(process.env.GITHUB_ACTIONS === "true" ? ["github-actions"] : []),
          ["junit", { outputFile: `${process.env.TEST_RESULTS}/tm1-testcontainers.xml` }],
        ]
      : undefined,
    include: ["test/**/*.test.ts"],
    // One test file at a time: with their sources set, the container tests of different files would
    // start Cognos, PAW and several TM1 servers at once, and one of them wants nothing run beside it
    fileParallelism: false,
    // Container tests wait for TM1 (a bundle's first install takes minutes); the rest are quick
    testTimeout: 10 * 60_000,
    hookTimeout: 10 * 60_000,
  },
});
