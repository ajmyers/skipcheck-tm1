// SPDX-License-Identifier: Apache-2.0

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";

/** Reads a setting by name: `undefined` (or `null`) when it's unset. */
export type Lookup = (name: string) => string | undefined | null;

/** Where the settings come from: environment variables, which stand in for both of the Java
 * module's places (a system property, then an environment variable of the same name). */
export const Settings = {
  get(name: string): string | undefined {
    return process.env[name];
  },

  /** A setting's value, trimmed; `undefined` when unset or blank. */
  text(value: string | undefined | null): string | undefined {
    return value === undefined || value === null || value.trim() === "" ? undefined : value.trim();
  },

  trimmed(lookup: Lookup): (name: string) => string | undefined {
    return (name) => Settings.text(lookup(name));
  },

  requireText(value: string | undefined | null, name: string): string {
    if (value === undefined || value === null || value.trim() === "") throw new IllegalArgumentException(name + " must not be blank");
    return value;
  },

  /** An absolute, normalized path, as Java's `toAbsolutePath().normalize()`. */
  absolute(path: string): string {
    return resolve(path);
  },

  isFile(path: string): boolean {
    return existsSync(path) && statSync(path).isFile();
  },

  isDirectory(path: string): boolean {
    return existsSync(path) && statSync(path).isDirectory();
  },

  /** A URL for logs: scheme, host, port and path, never the user info or the query (a signed
   * URL's secret). */
  safe(url: URL): string {
    return url.protocol.replace(/:$/, "") + "://" + url.hostname + (url.port === "" ? "" : ":" + url.port) + url.pathname;
  },

  /** Whether `dir` is a folder holding a file whose name passes `match`. */
  hasFile(dir: string, match: (name: string) => boolean): boolean {
    return Settings.isDirectory(dir) && readdirSync(dir).some(match);
  },

  /** This library's version, from its package.json, as Java reads tm1-testcontainers.properties. */
  libraryVersion(): string {
    const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")) as { version?: string };
    return pkg.version ?? "0.0.0-dev";
  },
};

/** A wrong argument: Java's `IllegalArgumentException`. A source that's set but wrong throws it,
 * so the test fails rather than skips. */
export class IllegalArgumentException extends Error {
  /** The exception's name, as Java's class. */
  override readonly name = "IllegalArgumentException";
}

/** A call at the wrong time, or a part that's missing: Java's `IllegalStateException`. */
export class IllegalStateException extends Error {
  /** The exception's name, as Java's class. */
  override readonly name = "IllegalStateException";
}

/** A URL from a string or a `URL`, checked; the text is kept as given, so a signed URL's query is
 * passed on byte for byte. */
export function parseUrl(url: URL | string): { url: URL; text: string } {
  if (url instanceof URL) return { url, text: url.href };
  try {
    return { url: new URL(url), text: url };
  } catch {
    // Never the URL itself: it may be signed
    throw new IllegalArgumentException("Not a URL: " + url.replace(/[?#].*$/, "").replace(/\/\/[^/@]*@/, "//"));
  }
}
