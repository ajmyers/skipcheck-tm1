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
    testTimeout: 20_000,
    // The live tests' setup may start TM1 in Docker: a bundle's first install takes minutes
    hookTimeout: 10 * 60_000,
    projects: [
      {
        extends: true,
        test: { name: "tests", include: ["test/**/*.test.ts"], exclude: [CAM] },
      },
      {
        // Each CAM test file starts a Cognos of its own, as Java's classes do, and Cognos is too
        // heavy to run two at once: these files run one after the other in one fork, beside the rest
        // (a project takes no fileParallelism of its own)
        extends: true,
        test: { name: "cam", include: [CAM], poolOptions: { forks: { singleFork: true } } },
      },
    ],
  },
});
