// SPDX-License-Identifier: Apache-2.0

import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

function find(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  while (!existsSync(join(dir, "spec", "tm1-api.json"))) {
    const parent = dirname(dir);
    if (parent === dir) throw new Error("Can't find the repository root (spec/tm1-api.json)");
    dir = parent;
  }
  return dir;
}

/** Where the repository's shared files are. */
export const Repo = {
  root: find(),
  get spec(): string {
    return join(this.root, "spec", "tm1-api.json");
  },
  get conformance(): string {
    return join(this.root, "spec", "conformance");
  },
  get payloads(): string {
    return join(this.root, "java", "tm1-client", "src", "test", "resources", "payloads", "11.8.35");
  },
  get snapshot(): string {
    return this.snapshotFor("11.8.35");
  },
  get snapshots(): readonly string[] {
    return [this.snapshotFor("11.8.35"), this.snapshotFor("12.6.0")];
  },
  snapshotFor(schema: string): string {
    return join(this.root, "java", "tm1-client", "src", "model", "metadata", `tm1-schema-${schema}.xml`);
  },
  get javaClient(): string {
    return join(this.root, "java", "tm1-client", "src", "main", "java", "io", "skipcheck", "tm1", "client");
  },
  get javaTests(): string {
    return join(this.root, "java", "tm1-client", "src", "test", "java");
  },
  get javaServices(): string {
    return join(this.javaClient, "service");
  },
  get typescript(): string {
    return resolve(this.root, "typescript", "tm1-client");
  },
};
