import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const source = (path: string): string => fileURLToPath(new URL(path, import.meta.url));

export default defineConfig({
  resolve: {
    // The workspace's packages from their sources, as tsconfig.json's paths: test/live starts TM1
    // with @skipcheck/tm1-testcontainers, which uses this package
    alias: [
      { find: /^@skipcheck\/tm1-client$/, replacement: source("./src/index.ts") },
      { find: /^@skipcheck\/tm1-testcontainers$/, replacement: source("./testcontainers/src/index.ts") },
      { find: /^@skipcheck\/tm1-testcontainers\/vitest$/, replacement: source("./testcontainers/src/vitest.ts") },
    ],
  },
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 20_000,
    // The live tests' setup may start TM1 in Docker: a bundle's first install takes minutes
    hookTimeout: 10 * 60_000,
  },
});
