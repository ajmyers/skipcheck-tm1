import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // The client from its sources, as tsconfig.json's paths
    alias: { "@skipcheck/tm1-client": fileURLToPath(new URL("../tm1-client/src/index.ts", import.meta.url)) },
  },
  test: {
    include: ["test/**/*.test.ts"],
    // Container tests wait for TM1 (a bundle's first install takes minutes); the rest are quick
    testTimeout: 10 * 60_000,
    hookTimeout: 10 * 60_000,
  },
});
