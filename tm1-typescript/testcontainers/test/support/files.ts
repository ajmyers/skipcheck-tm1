import { existsSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach } from "vitest";
import type { Lookup } from "../../src/settings.js";

/** A folder for each test, removed afterwards, as JUnit's `@TempDir`. */
export function useTempDir(): { readonly path: string; touch(name: string): string; mkdir(name: string): string } {
  let dir = "";
  beforeEach(() => {
    dir = realpathSync(mkdtempSync(join(tmpdir(), "tm1-tc-")));
  });
  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });
  return {
    get path() {
      return dir;
    },
    touch(name: string): string {
      const path = join(dir, name);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, "");
      return path;
    },
    mkdir(name: string): string {
      const path = join(dir, name);
      mkdirSync(path, { recursive: true });
      return path;
    },
  };
}

/** Settings from a map, as the Java tests' `Map::get`. */
export function settings(values: Record<string, string>): Lookup {
  return (name) => values[name];
}

/** `values` without `names`. */
export function without(values: Record<string, string>, ...names: string[]): Record<string, string> {
  return Object.fromEntries(Object.entries(values).filter(([k]) => !names.includes(k)));
}

function findRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  while (!existsSync(join(dir, "settings.gradle.kts"))) {
    const parent = dirname(dir);
    if (parent === dir) throw new Error("Can't find the repository root (settings.gradle.kts)");
    dir = parent;
  }
  return dir;
}

/** Where the repository's files are. */
export const Repo = {
  root: findRoot(),
  get javaSources(): string {
    return join(this.root, "java", "tm1-testcontainers", "src", "main", "java", "io", "skipcheck", "tm1", "testcontainers");
  },
  get typescriptSources(): string {
    return join(this.root, "tm1-typescript", "testcontainers", "src");
  },
  get pawScript(): string {
    return join(this.root, "docker", "paw", "paw-local.sh");
  },
};
