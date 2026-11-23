// SPDX-License-Identifier: Apache-2.0

import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { format } from "node:util";
import { vi } from "vitest";
import { Repo } from "./repo.js";

const START = /@start\s+region\s*=\s*"?([\w-]+)"?/;
const END = /@end\b/;

/** Any line ending, as Java's `\R`. */
const LINE_END = /\r\n|[\n\v\f\r\u0085\u2028\u2029]/;

interface Region {
  source: string;
  output: string[];
}

/** What an example printed, recorded for the docs: the walkthroughs show each step's output under
 * its code (api-site/README.md, "Walkthroughs, and what the examples print"). A live test runs an example's steps through
 * {@link record}, and {@link write} (or disposing it) writes
 * `api-site/example-output/<project>/typescript/<Example>.json`, with each region's output and a
 * hash of its code. The site's build fails when a region that prints has no recording, when its code
 * has changed since (the hash differs), or when the languages printed different things. The Java
 * client's `ExampleOutput`, which hashes a region the same way: {@link sourceHash}. */
export class ExampleOutput {
  private readonly source: string;
  private readonly regions = new Map<string, Region>();
  private failed = false;

  /** @param project the project whose examples these are, such as `tm1-client`
   * @param example the examples file, such as `ConnectWalkthrough` */
  constructor(
    private readonly project: string,
    private readonly example: string,
  ) {
    this.source = readFileSync(join(Repo.root, "typescript", project, "examples", example + ".ts"), "utf8");
  }

  /** Runs a region's code, keeping what it prints with `console.log`, which still reaches the test log. */
  async record(region: string, step: () => unknown): Promise<void> {
    const hash = sourceHash(this.source, region);
    const original = console.log.bind(console);
    let printed = "";
    const spy = vi.spyOn(console, "log").mockImplementation((...args: unknown[]) => {
      printed += format(...args) + "\n";
      original(...args);
    });
    try {
      await step();
    } catch (error) {
      this.failed = true;
      throw error;
    } finally {
      spy.mockRestore();
    }
    this.regions.set(region, { source: hash, output: lines(printed) });
  }

  /** What a region printed, as {@link record} keeps it. */
  output(region: string): string[] {
    const kept = this.regions.get(region);
    if (!kept) throw new Error(`no output recorded for ${region}`);
    return kept.output;
  }

  /** Writes the recording, replacing the one before, unless a step failed: then the one before stays. */
  write(): void {
    if (this.failed) return;
    const regions = Object.fromEntries([...this.regions].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
    const path = join(Repo.root, "api-site", "example-output", this.project, "typescript", this.example + ".json");
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify({ example: this.example, language: "typescript", regions }, null, 2) + "\n");
  }

  [Symbol.dispose](): void {
    this.write();
  }
}

/** The hash of a region's code, as every language and the site's build work it out: the lines
 * between its `@start region = <name>` and `@end`, each without the whitespace around it, blank
 * ones left out, joined with newlines, as the SHA-256 of their UTF-8, in hexadecimal. Indentation
 * doesn't count, so reformatting a file only changes the hash where a line's own text changes. */
export function sourceHash(source: string, region: string): string {
  const kept: string[] = [];
  let inside = false;
  let found = false;
  for (const line of source.split(LINE_END)) {
    const start = START.exec(line);
    if (start) {
      inside = start[1] === region;
      found ||= inside;
    } else if (inside && END.test(line)) {
      inside = false;
    } else if (inside && line.trim() !== "") {
      kept.push(line.trim());
    }
  }
  if (!found) throw new Error(`no region ${region}`);
  return createHash("sha256").update(kept.join("\n"), "utf8").digest("hex");
}

/** Printed text as lines, without their trailing whitespace and without the empty lines at the end. */
export function lines(text: string): string[] {
  const out = text.split(LINE_END).map((line) => line.trimEnd());
  while (out.length > 0 && out[out.length - 1] === "") out.pop();
  return out;
}
