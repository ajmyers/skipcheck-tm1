import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { Repo } from "./support/repo.js";

/** The libraries with examples in every language: the client, and tm1-testcontainers. */
const MODULES = ["tm1-client", "tm1-testcontainers"];

/** The names of the regions an examples file marks: `@start region = name` … `@end`, in a comment,
 * as Javadoc's snippets are (and as api-site/scripts/guide.mjs reads them). */
export function regions(source: string): string[] {
  const found: string[] = [];
  let open: string | undefined;
  for (const line of source.split("\n")) {
    const start = /@start\s+region\s*=\s*"?([\w-]+)"?/.exec(line);
    if (start) open = start[1];
    else if (/@end\b/.test(line) && open !== undefined) {
      found.push(open);
      open = undefined;
    }
  }
  return found.sort();
}

/** What's wrong between a module's Java examples (file name to source) and its TypeScript ones. */
export function problems(java: Map<string, string>, typescript: Map<string, string>): string[] {
  const out: string[] = [];
  for (const [name, source] of java) {
    const ours = typescript.get(name);
    if (ours === undefined) {
      out.push(`${name}.java has no ${name}.ts`);
      continue;
    }
    const want = regions(source);
    const have = regions(ours);
    for (const region of want) if (!have.includes(region)) out.push(`${name}.ts has no region ${region}`);
    for (const region of have) if (!want.includes(region)) out.push(`${name}.ts has a region Java doesn't: ${region}`);
    if (new Set(have).size !== have.length) out.push(`${name}.ts marks a region twice`);
  }
  for (const name of typescript.keys()) if (!java.has(name)) out.push(`${name}.ts has no ${name}.java`);
  return out;
}

function sources(dir: string, extension: string): Map<string, string> {
  const out = new Map<string, string>();
  if (!existsSync(dir)) return out;
  for (const file of readdirSync(dir).sort()) if (file.endsWith(extension)) out.set(basename(file, extension), readFileSync(join(dir, file), "utf8"));
  return out;
}

/** The guide shows each example in every language (api-site/scripts/guide.mjs), so each Java
 * examples class (java/<module>/src/examples/java/examples/<Name>.java) has its TypeScript file
 * (typescript/<module>/examples/<Name>.ts) with the same regions: none missing, none extra.
 * `npm run typecheck` checks that the examples compile; nothing runs them. */
describe("Java examples parity", () => {
  it.each(MODULES)("%s has every Java example, region for region", (module) => {
    const java = sources(join(Repo.root, "java", module, "src", "examples", "java", "examples"), ".java");
    expect(java.size, "the Java examples weren't found").toBeGreaterThan(0);
    expect(problems(java, sources(join(Repo.root, "typescript", module, "examples"), ".ts"))).toEqual([]);
  });

  it("reads regions as the guide does", () => {
    expect(regions('// @start region = b\nx\n// @end\n\t// @start region = "a-1"\ny\n\t// @end\n// @end\n')).toEqual(["a-1", "b"]);
  });

  it("fails on a missing file, a missing region, an extra region and an extra file", () => {
    const java = new Map([["CubeExamples", "// @start region = list\n// @end\n// @start region = create\n// @end\n"]]);
    expect(problems(java, new Map())).toEqual(["CubeExamples.java has no CubeExamples.ts"]);
    expect(problems(java, new Map([["CubeExamples", "// @start region = list\n// @end\n"]]))).toEqual(["CubeExamples.ts has no region create"]);
    const extra = "// @start region = list\n// @end\n// @start region = create\n// @end\n// @start region = drop\n// @end\n";
    expect(problems(java, new Map([["CubeExamples", extra]]))).toEqual(["CubeExamples.ts has a region Java doesn't: drop"]);
    expect(problems(new Map(), new Map([["Other", ""]]))).toEqual(["Other.ts has no Other.java"]);
  });
});
