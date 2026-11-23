// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // The client from its sources, as tsconfig.json's paths
    alias: { "@skipcheck/tm1-client": fileURLToPath(new URL("../tm1-client/src/index.ts", import.meta.url)) },
  },
  test: {
    // With TEST_RESULTS set (./gradlew checkTypescript sets it), a JUnit report too, for scripts/test-summary.py
    reporters: process.env.TEST_RESULTS ? ["default", ["junit", { outputFile: `${process.env.TEST_RESULTS}/tm1-testcontainers.xml` }]] : ["default"],
    include: ["test/**/*.test.ts"],
    // Container tests wait for TM1 (a bundle's first install takes minutes); the rest are quick
    testTimeout: 10 * 60_000,
    hookTimeout: 10 * 60_000,
  },
});
