// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import * as library from "../src/index.js";
import * as vitestHelpers from "../src/vitest.js";
import { declarations, type TsDeclaration } from "./support/declarations.js";
import { Repo } from "./support/files.js";

/** The TypeScript library offers what the Java tm1-testcontainers does, read from the Java
 * sources as the .NET port's JavaParityTests does: each public Java type is an exported class of
 * the same name (its nested records and enums `Outer.Inner`, in a namespace merged with the
 * class; its annotations const objects in `./vitest.js`); each constant a static of the same
 * name; each enum constant a static instance; each record component a property; each
 * constructor and public method one of the same name, taking Java's parameter names in order.
 * Getters keep their names (`getRestUrl()`), as Testcontainers for Node has them
 * (`getMappedPort()`), and what does I/O returns a Promise. */

const JAVA = Repo.javaSources;
const TS = declarations(Repo.typescriptSources);

/** Java methods that do I/O: they return a Promise in TypeScript. */
const IO = new Set(["shared", "start", "stop", "idToken", "passport"]);

interface JavaMember {
  name: string;
  params: string[];
  constructor: boolean;
}

const javaFile = (name: string): string => readFileSync(join(JAVA, name + ".java"), "utf8");

const publicTypes = readdirSync(JAVA)
  .filter((f) => f.endsWith(".java"))
  .map((f) => f.slice(0, -5))
  .filter((n) => new RegExp(`public\\s+(?:final\\s+|sealed\\s+)*(?:class|enum|record|interface)\\s+${n}\\b`).test(javaFile(n)))
  .sort();

const annotations = readdirSync(JAVA)
  .filter((f) => /^Requires.*\.java$/.test(f))
  .map((f) => f.slice(0, -5))
  .sort();

function split(parameters: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let current = "";
  for (const c of parameters) {
    depth += c === "<" ? 1 : c === ">" ? -1 : 0;
    if (c === "," && depth === 0) {
      out.push(current);
      current = "";
    } else current += c;
  }
  if (current.trim() !== "") out.push(current);
  return out.map((p) => p.replace(/\s+/g, " ").trim());
}

/** The public constructors and methods declared at `depth` tabs. */
function javaMembers(source: string, depth: number, isInterface: boolean): JavaMember[] {
  const cls = /(?:class|record|interface|enum)\s+(\w+)/.exec(source)![1];
  const pattern =
    /^(?<indent>\t+)(?<mods>(?:(?:public|static|synchronized|final|default)\s+)*)(?:<[^>]+>\s+)?(?<ret>[\w.<>[\]?, @]+?)\s+(?<name>\w+)\s*\((?<params>[^)]*)\)\s*(?:throws\s+[\w., ]+)?[{;]/gm;
  const out: JavaMember[] = [];
  for (const m of source.matchAll(pattern)) {
    const g = m.groups!;
    if (g["indent"] !== "\t".repeat(depth)) continue;
    const mods = g["mods"]!;
    const ret = g["ret"]!.trim();
    const name = g["name"]!;
    const constructor = ret === "public" && name === cls;
    const isPublic = mods.includes("public") || (isInterface && !ret.includes("private"));
    if (ret.endsWith("record") || ret.endsWith("class")) continue; // a nested type, checked as one
    if (!(isPublic || constructor) || ret.includes("private") || ret.includes("protected") || ["toString", "equals", "hashCode"].includes(name)) continue;
    const params = split(g["params"]!).map((p) => p.replace("@Nullable", "").trim().split(" ").pop()!.replace(/^\.+/, ""));
    out.push({ name, params, constructor });
  }
  return out;
}

/** A record's components: `record PrivateImage(String image)`. */
function components(header: string): string[] {
  return split(header).map((p) => p.replace("@Nullable", "").trim().split(" ").pop()!);
}

function subsequence(expected: string[], got: string[]): boolean {
  let i = 0;
  for (const g of got) if (i < expected.length && expected[i] === g) i++;
  return i === expected.length;
}

function checkMembers(where: string, members: JavaMember[], d: TsDeclaration, problems: string[]): void {
  for (const m of members) {
    if (m.constructor) {
      if (!d.constructors.some((c) => subsequence(m.params, c)))
        problems.push(`${where}: no constructor takes (${m.params.join(", ")}); has ${d.constructors.map((c) => "(" + c.join(", ") + ")").join(" | ")}`);
      continue;
    }
    const found = d.members.get(m.name);
    if (!found) {
      problems.push(`${where}: missing ${m.name}`);
      continue;
    }
    const signatures = found.flatMap((f) => f.signatures);
    if (signatures.length === 0) {
      if (m.params.length > 0) problems.push(`${where}.${m.name} is a property, but Java's takes (${m.params.join(", ")})`);
      continue;
    }
    if (!signatures.some((s) => subsequence(m.params, s)))
      problems.push(`${where}.${m.name}: no signature takes (${m.params.join(", ")}); has ${signatures.map((s) => "(" + s.join(", ") + ")").join(" | ")}`);
  }
}

function checkConstants(where: string, source: string, d: TsDeclaration, isInterface: boolean, problems: string[]): void {
  const pattern = isInterface ? /^\t(?:String|int)\s+([A-Z][A-Z_0-9]*)\s*=/gm : /^\tpublic\s+static\s+final\s+[\w.<>]+\s+([A-Z][A-Z_0-9]*)\s*=/gm;
  for (const c of source.matchAll(pattern)) {
    if (!d.members.get(c[1]!)?.some((m) => m.isStatic)) problems.push(`${where}: missing constant ${c[1]}`);
  }
}

/** A Java string constant's value: its literals, and the constants it joins, evaluated. */
function javaMessage(source: string, constant: string): string {
  const expression = new RegExp(`String\\s+${constant}\\s*=\\s*([\\s\\S]*?);\\s*$`, "m").exec(source)![1]!;
  let text = "";
  for (const part of expression.matchAll(/"((?:[^"\\]|\\.)*)"|([A-Za-z]\w*)\.([A-Z][A-Z_0-9]*)/g)) {
    if (part[1] !== undefined) {
      text += JSON.parse('"' + part[1] + '"') as string;
      continue;
    }
    text += new RegExp(`\\b${part[3]}\\s*=\\s*"([^"]*)"`).exec(javaFile(part[2]!))![1];
  }
  return text;
}

describe("Java parity", () => {
  it("reads the Java sources", () => {
    expect(publicTypes).toContain("Tm1ServerContainer");
    expect(publicTypes).not.toContain("PawSession"); // package-private
    expect(annotations).toEqual(["RequiresCognos", "RequiresPaw", "RequiresTm1", "RequiresTm1Web"]);
    const members = javaMembers(javaFile("Tm1ServerContainer"), 1, false);
    expect(members).toContainEqual({ name: "withCamSecurity", params: ["cognos", "namespace", "admins"], constructor: false });
    expect(members).toContainEqual({ name: "getRestUrl", params: [], constructor: false });
    expect(members).toContainEqual({ name: "Tm1ServerContainer", params: ["image", "source"], constructor: true });
    expect(members.map((m) => m.name)).not.toContain("configure");
    expect(members.map((m) => m.name)).not.toContain("mountSource");
    expect(javaMembers(javaFile("Tm1Source"), 1, true)).toContainEqual({ name: "discover", params: ["lookup"], constructor: false });
  });

  it("catches what's missing", () => {
    const real = TS.get("Tm1ServerContainer")!;
    const members = javaMembers(javaFile("Tm1ServerContainer"), 1, false);
    expect(members.length).toBeGreaterThan(15);
    const problems: string[] = [];
    checkMembers("Tm1ServerContainer", members, real, problems);
    expect(problems).toEqual([]);
    const broken: TsDeclaration = { ...real, members: new Map([...real.members].filter(([k]) => k !== "getRestUrl")), constructors: [["source"]] };
    checkMembers("Tm1ServerContainer", members, broken, problems);
    expect(problems).toEqual(["Tm1ServerContainer: no constructor takes (image, source); has (source)", "Tm1ServerContainer: missing getRestUrl"]);
  });

  it.each(publicTypes)("%s has its TypeScript counterpart", (name) => {
    const source = javaFile(name);
    const d = TS.get(name);
    expect(d, "no exported class " + name).toBeDefined();
    expect(d!.kind).toBe("class");
    expect((library as Record<string, unknown>)[name], name + " isn't exported from the package").toBeDefined();
    const problems: string[] = [];
    if (new RegExp(`public\\s+enum\\s+${name}\\b`).test(source)) {
      for (const c of source.matchAll(/^\t([A-Z][A-Z_0-9]*)\(/gm))
        if (!d!.members.get(c[1]!)?.some((m) => m.isStatic)) problems.push(`no enum constant ${c[1]}`);
      expect(problems).toEqual([]);
      return;
    }
    const isInterface = new RegExp(`interface\\s+${name}\\b`).test(source);
    checkConstants(name, source, d!, isInterface, problems);
    const record = new RegExp(`public\\s+record\\s+${name}\\(([^)]*)\\)`).exec(source);
    if (record) {
      for (const c of components(record[1]!)) if (!d!.members.has(c)) problems.push(`${name}: no property ${c}`);
      if (!d!.constructors.some((c) => subsequence(components(record[1]!), c))) problems.push(`${name}: no constructor takes the record's components`);
    }
    for (const n of source.matchAll(/^\t(public\s+)?(?:static\s+)?(?:final\s+)?(record|enum|class)\s+(\w+)(?:\(([^)]*)\))?/gm)) {
      // A class's nested type without "public" is package-private (an interface's are public)
      if (n[1] === undefined && !isInterface) continue;
      const inner = TS.get(name + "." + n[3]);
      if (!inner) {
        problems.push(`no nested ${n[2]} ${name}.${n[3]}`);
        continue;
      }
      if (n[2] === "record") {
        for (const c of components(n[4]!)) if (!inner.members.has(c)) problems.push(`${name}.${n[3]}: no property ${c}`);
        if (!inner.constructors.some((c) => subsequence(components(n[4]!), c))) problems.push(`${name}.${n[3]}: no constructor takes the record's components`);
      }
      if (n[2] === "enum") {
        const body = source.slice(n.index);
        for (const c of body.matchAll(/^\t\t([A-Z][A-Z_0-9]*)\(/gm))
          if (!inner.members.get(c[1]!)?.some((m) => m.isStatic)) problems.push(`${name}.${n[3]}: no enum constant ${c[1]}`);
        checkMembers(name + "." + n[3], javaMembers(body.slice(0, body.indexOf("\n\t}")), 2, false), inner, problems);
      }
    }
    checkMembers(name, javaMembers(source, 1, isInterface), d!, problems);
    expect(problems).toEqual([]);
  });

  it("the environment's builder has every builder method", () => {
    const source = javaFile("PlanningAnalyticsEnvironment");
    const from = source.slice(source.indexOf("public static final class Builder"));
    // Up to the Builder's closing brace: the package-private types after it aren't the builder's
    const builder = from.slice(0, from.indexOf("\n\t}\n"));
    const problems: string[] = [];
    checkMembers("PlanningAnalyticsEnvironment.Builder", javaMembers(builder, 2, false), TS.get("PlanningAnalyticsEnvironment.Builder")!, problems);
    expect(problems).toEqual([]);
  });

  it("what does I/O returns a Promise", () => {
    const io: string[] = [];
    for (const d of TS.values()) {
      for (const m of [...IO].flatMap((n) => d.members.get(n) ?? [])) {
        io.push(d.name + "." + m.name);
        expect(m.returns.length, `${d.name}.${m.name} is a method`).toBeGreaterThan(0);
        for (const r of m.returns) expect(r, `${d.name}.${m.name}`).toMatch(/^Promise</);
      }
    }
    expect(io).toEqual(
      expect.arrayContaining([
        "Tm1ServerContainer.shared",
        "GenericContainer.start",
        "GenericContainer.stop",
        "PawContainer.start",
        "KeycloakContainer.idToken",
        "CognosContainer.passport",
        "PlanningAnalyticsEnvironment.start",
      ]),
    );
  });

  it.each(annotations)("@%s is a vitest helper with Java's messages", (name) => {
    const source = javaFile(name);
    const helper = (vitestHelpers as Record<string, unknown>)[name] as Record<string, unknown> | undefined;
    expect(helper, "no " + name + " in ./vitest.js").toBeDefined();
    expect(helper!["skipReason"]).toBeTypeOf("function");
    const test = "r" + name.slice(1);
    expect((vitestHelpers as Record<string, unknown>)[test], "no " + test + " test in ./vitest.js").toBeTypeOf("function");
    const constants = [...source.matchAll(/^\tString\s+([A-Z][A-Z_0-9]*)\s*=/gm)].map((c) => c[1]!);
    expect(constants.length).toBeGreaterThan(0);
    for (const c of constants) expect(helper![c], `${name}.${c}`).toBe(javaMessage(source, c));
  });
});
