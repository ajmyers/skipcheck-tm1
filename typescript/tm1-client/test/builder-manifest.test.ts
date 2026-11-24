// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { join } from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import * as client from "../src/index.js";
import { ODataPath, Tm1PatchBuilder } from "../src/index.js";
import { Repo } from "./support/repo.js";

/** The Java client's `BuilderManifestTest`: what every generated create and patch builder offers,
 * which every client's builders must match (`spec/conformance/builders.json`). Each builder is driven
 * to find what it offers: a setter on a builder of its own gives the name it writes, and a property is
 * required when a builder with every other setter called refuses to build. The write type each
 * structured setter takes is read from the generated code's declarations, as Java's is from its
 * setters' parameter types. */

const BIND = "@odata.bind";
const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as { types: { name: string }[] };
const manifest = JSON.parse(readFileSync(join(Repo.conformance, "builders.json"), "utf8")) as {
  builders: Record<string, Described>;
};

interface Described {
  odataType: string | null;
  open: boolean;
  required: string[];
  setters: string[];
  values: Record<string, string>;
}

type Builder = Record<string, (...args: unknown[]) => unknown>;
type Patch = { builder(): Builder };

const exported = client as unknown as Record<string, unknown>;
const bare = (name: string): string => (name.endsWith(BIND) ? name.slice(0, -BIND.length) : name);
const ordinal = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

/** Each generated builder's setters' parameter types, as declared: `CubeCreateBuilder` → `views` → `readonly ViewCreate[] | null`. */
const parameterTypes = new Map<string, Map<string, string>>();
{
  const file = join(Repo.typescript, "src", "generated", "patches.ts");
  const tree = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true);
  for (const node of tree.statements) {
    if (!ts.isClassDeclaration(node) || !node.name) continue;
    const methods = new Map<string, string>();
    for (const m of node.members)
      if (ts.isMethodDeclaration(m) && m.parameters.length === 1 && m.parameters[0]!.type)
        methods.set(m.name.getText(tree), m.parameters[0]!.type.getText(tree));
    parameterTypes.set(node.name.text, methods);
  }
}

/** The write type a setter takes, as Java's manifest writes it (`ViewCreate[]`), or undefined for a
 * primitive, an enum or a binding. */
function valueType(builder: string, setter: string): string | undefined {
  const declared = parameterTypes.get(builder)?.get(setter);
  const m = declared && /^(readonly )?([A-Za-z0-9_]+)(\[\])? \| null$/.exec(declared);
  if (!m || !/(Create|Patch)$/.test(m[2]!) || typeof (exported[m[2]!] as Partial<Patch> | undefined)?.builder !== "function") return undefined;
  return m[2]! + (m[3] ?? "");
}

function setters(builder: Builder): string[] {
  const names = new Set<string>();
  for (let p = Object.getPrototypeOf(builder) as object; p !== Tm1PatchBuilder.prototype; p = Object.getPrototypeOf(p) as object) {
    for (const name of Object.getOwnPropertyNames(p)) {
      if (!["constructor", "build", "property"].includes(name) && typeof (p as Record<string, unknown>)[name] === "function") names.add(name);
    }
  }
  return [...names].sort(ordinal);
}

function call(builder: Builder, setter: string): void {
  builder[setter]!(/^bind[A-Z]/.test(setter) ? ODataPath.of("x") : null);
}

/** The name a setter writes: the builder's own set, bind and bindAll, watched. */
function written(builder: Builder, setter: string): string[] {
  const names: string[] = [];
  const watched = builder as unknown as Record<string, (name: string, value: unknown) => unknown>;
  watched.set = (name) => (names.push(name), builder);
  watched.bind = (name) => (names.push(name + BIND), builder);
  watched.bindAll = (name) => (names.push(name + BIND), builder);
  call(builder, setter);
  return names;
}

function describeBuilder(name: string, patch: Patch): Described {
  const wrote = new Map<string, string>();
  const takes: [string, string][] = [];
  for (const setter of setters(patch.builder())) {
    const names = written(patch.builder(), setter);
    expect(names, setter + " writes one property").toHaveLength(1);
    wrote.set(setter, names[0]!);
    const value = valueType(name + "Builder", setter);
    if (value !== undefined && !names[0]!.endsWith(BIND)) takes.push([names[0]!, value]);
  }
  const required: string[] = [];
  for (const group of [...new Set([...wrote.values()].map(bare))].sort(ordinal)) {
    const b = patch.builder();
    for (const [setter, key] of wrote) if (bare(key) !== group) call(b, setter);
    try {
      b.build!();
    } catch {
      required.push(group);
    }
  }
  const full = patch.builder();
  for (const setter of wrote.keys()) call(full, setter);
  const values = new Map((full.build!() as unknown as { values(): [string, unknown][] }).values());
  return {
    odataType: (values.get("@odata.type") as string | undefined) ?? null,
    open: typeof patch.builder().property === "function",
    required,
    setters: [...wrote.values()].sort(ordinal),
    values: Object.fromEntries(takes.sort(([a], [b]) => ordinal(a, b))),
  };
}

describe("builder manifest", () => {
  it("every builder offers what the manifest lists", () => {
    const names = spec.types
      .flatMap((t) => [t.name + "Patch", t.name + "Create"])
      .filter((n) => typeof (exported[n] as Partial<Patch> | undefined)?.builder === "function")
      .sort(ordinal);
    expect(names).toEqual(Object.keys(manifest.builders).sort(ordinal));
    const wrong = names.flatMap((n) => {
      const got = describeBuilder(n, exported[n] as Patch);
      return JSON.stringify(got) === JSON.stringify(manifest.builders[n]) ? [] : [`${n}: ${JSON.stringify(got)} != ${JSON.stringify(manifest.builders[n])}`];
    });
    expect(wrong).toEqual([]);
  });
});
