// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { Tm1Client } from "../src/index.js";
import * as services from "../src/services/index.js";
import { lines } from "./coverage-gate.test.js";
import { Repo } from "./support/repo.js";
import { allMembers, declarations } from "./support/sources.js";

export interface JavaMethod {
  name: string;
  overloads: { type: string; name: string }[][];
  endpoints: Set<string>;
}

function javaFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...javaFiles(path));
    else if (name.endsWith(".java") && name !== "package-info.java") out.push(path);
  }
  return out;
}

function split(parameters: string): string[] {
  const result: string[] = [];
  let depth = 0;
  let current = "";
  for (const c of parameters) {
    depth += c === "<" ? 1 : c === ">" ? -1 : 0;
    if (c === "," && depth === 0) {
      result.push(current);
      current = "";
    } else current += c;
  }
  if (current.trim()) result.push(current);
  return result.map((p) => p.replace(/\s+/g, " ").trim());
}

const METHOD =
  /(?<ann>(?:\s*@Tm1Endpoint\("[^"]+"\)\s*)*)(?<decl>\s+public\s+(?:static\s+)?(?:<[^>]+>\s+)?(?<ret>[\w.<>[\]?, ]+?)\s+(?<name>\w+)\s*\((?<params>[^)]*)\)\s*(?:throws [\w., ]+)?\{)/g;

/** The public methods of a Java service class, with their overloads' parameters and endpoints, as
 * the .NET client's JavaParityTests reads them. */
export function javaMethods(file: string): Map<string, JavaMethod> {
  const source = readFileSync(file, "utf8");
  const cls = basename(file, ".java");
  const result = new Map<string, JavaMethod>();
  for (const m of source.matchAll(METHOD)) {
    const g = m.groups!;
    const name = g["name"]!;
    if (name === cls || !g["decl"]!.replace(/^[\r\n]+/, "").startsWith("\tpublic") || ["toString", "equals", "hashCode"].includes(name)) continue;
    const parameters = split(g["params"]!).map((p) => {
      const parts = p.replace("@Nullable", "").trim().split(" ").filter(Boolean);
      return { type: parts.slice(0, -1).join(" "), name: parts[parts.length - 1]!.replace(/^\.+/, "") };
    });
    let method = result.get(name);
    if (!method) result.set(name, (method = { name, overloads: [], endpoints: new Set() }));
    method.overloads.push(parameters);
    for (const e of g["ann"]!.matchAll(/@Tm1Endpoint\("([^"]+)"\)/g)) method.endpoints.add(e[1]!);
  }
  return result;
}

function subsequence(expected: string[], got: string[]): boolean {
  let i = 0;
  for (const g of got) if (i < expected.length && expected[i] === g) i++;
  return i === expected.length;
}

function pascal(constant: string): string {
  return constant
    .split("_")
    .filter(Boolean)
    .map((w) => w[0] + w.slice(1).toLowerCase())
    .join("");
}

const classes = javaFiles(Repo.javaServices)
  .filter((f) => new RegExp(`public\\s+(final\\s+)?(class|enum|record|interface)\\s+${basename(f, ".java")}\\b`).test(readFileSync(f, "utf8")))
  .sort();
const sources = declarations(join(Repo.typescript, "src"));
const pending = new Set(lines("pending-classes.txt"));

/** What's wrong with the TypeScript counterpart of a Java service class, or nothing. */
function problems(file: string): string[] {
  const name = basename(file, ".java");
  const ts = sources.get(name);
  if (!ts) return [`the TypeScript client has no ${name}`];
  const source = readFileSync(file, "utf8");
  const out: string[] = [];
  if (new RegExp(`public\\s+enum\\s+${name}\\b`).test(source)) {
    // A Java enum is a string union and a const of its members, in Pascal case: ViewScope.Public
    const members = allMembers(sources, name);
    for (const c of source.matchAll(/^\t([A-Z][A-Z_0-9]*)\b[,;(]/gm)) {
      if (!members.has(pascal(c[1]!))) out.push(`${name}.${c[1]} → ${pascal(c[1]!)}`);
    }
    return out;
  }
  const members = allMembers(sources, name);
  const table = (services as Record<string, unknown>)[name] as { endpoints?: Record<string, readonly string[]> } | undefined;
  for (const method of javaMethods(file).values()) {
    const member = members.get(method.name);
    if (!member) {
      out.push(`missing ${method.name}`);
      continue;
    }
    if (member.signatures.length === 0) continue; // a property, for an accessor
    const endpoints = new Set(table?.endpoints?.[method.name] ?? []);
    const want = [...method.endpoints].sort();
    const have = [...endpoints].sort();
    if (want.join("|") !== have.join("|")) out.push(`${method.name}: endpoints [${have.join(", ")}], Java [${want.join(", ")}]`);
    for (const overload of method.overloads) {
      const expected = overload.map((p) => (p.type === "ODataQuery" ? "query" : p.name));
      if (
        !member.signatures.some((s) =>
          subsequence(
            expected,
            s.filter((n) => n !== "options"),
          ),
        )
      ) {
        out.push(`${method.name}: no signature takes (${expected.join(", ")}); has ${member.signatures.map((s) => `(${s.join(", ")})`).join(" | ")}`);
      }
    }
  }
  return out;
}

/** The TypeScript client offers what the Java client does, method for method, read from the Java
 * sources (java/tm1-client/src/main/java/.../service), as the Python and .NET clients' parity tests:
 * each Java class is an export of the same name; each public method has the same name, implements
 * the same `@Tm1Endpoint` IDs (in the class's `endpoints` table), and takes Java's parameters, by
 * Java's names, in order (an `ODataQuery` is `query`; overloads become optional parameters or
 * overload signatures; `options`, with the abort signal, comes last). A class listed in
 * coverage/pending-classes.txt isn't ported yet: it must still fall short, so it comes off the list
 * once it's done. */
describe("Java parity", () => {
  it.each(classes.map((f) => [basename(f, ".java"), f] as const))("%s has its TypeScript counterpart", (name, file) => {
    const found = problems(file);
    if (pending.has(name)) {
      expect(found.length, `${name} is complete: take it off coverage/pending-classes.txt`).toBeGreaterThan(0);
      return;
    }
    expect(found).toEqual([]);
  });

  it("lists only Java classes as pending", () => {
    const names = new Set(classes.map((f) => basename(f, ".java")));
    expect([...pending].filter((p) => !names.has(p))).toEqual([]);
  });

  it("has every service on the client", () => {
    const methods = javaMethods(join(Repo.javaClient, "Tm1Client.java"));
    const client = Tm1Client.builder().baseUrl("http://tm1:8010/api/v1/").basicAuth("a", "b").build() as unknown as Record<string, unknown>;
    const skip = new Set(["builder", "session", "batch", "connect", "close", "withoutAsync", "toString"]);
    for (const m of methods.values()) {
      const service =
        (m.overloads.every((o) => o.length === 0) && m.name.endsWith("s")) ||
        ["server", "git", "encryption", "drillthrough", "processDebugging"].includes(m.name);
      if (!service || skip.has(m.name)) continue;
      expect(client[m.name], m.name).toBeDefined();
    }
  });
});
