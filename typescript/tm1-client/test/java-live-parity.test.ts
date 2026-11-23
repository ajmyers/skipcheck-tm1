// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { lines } from "./coverage-gate.test.js";
import { Repo } from "./support/repo.js";

const LIVE = join(Repo.typescript, "test", "live");
const SUFFIX = ".live.test.ts";

/** Live test files that port no Java class: this client's own tests. */
const PORT_ONLY = new Set(["PortOnly" + SUFFIX]);

/** A name with only its letters and digits, lower-cased: a Java method `stopsAtABreakpoint` and the
 * title "stops at a breakpoint" are the same test. */
export function key(name: string): string {
  return name.replace(/[^A-Za-z0-9]/g, "").toLowerCase();
}

function javaFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...javaFiles(path));
    else if (/(Tm1Test|BehaviorTest)\.java$/.test(name)) out.push(path);
  }
  return out;
}

/** The test methods of a Java test class: those under `@Test` or `@ParameterizedTest`. */
export function javaTests(source: string): string[] {
  return [...source.matchAll(/@(?:Test|ParameterizedTest)\b[\s\S]*?\bvoid\s+(\w+)\s*\(/g)].map((m) => m[1]!);
}

/** The file a Java live test class is ported to: `XTm1Test` is `X.live.test.ts`, `XBehaviorTest` is
 * `XBehavior.live.test.ts`. */
export function portFile(javaClass: string): string {
  return javaClass.replace(/Tm1Test$/, "").replace(/Test$/, "") + SUFFIX;
}

/** A live test file's top-level describes and its tests' titles, read from the source: the calls of
 * `it`, `test` and the files' own `…Test` (`tm1Test`, `cognosTest`). A title's
 * placeholders for a parameter (`%s`, `$name`) don't count: Java's parameterized test is one method. */
export function portTests(source: string): { describes: number; titles: string[] } {
  const title = /\b(?:it|test|[a-z]\w*Test)(?:\.(?!each\b)\w+)*(?:\.each\([\s\S]*?\))?\(\s*(["'`])((?:\\.|(?!\1)[^\\])*)\1/g;
  return {
    describes: [...source.matchAll(/^describe(?:\.\w+)*\(/gm)].length,
    titles: [...source.matchAll(title)].map((m) => m[2]!.replace(/%[a-zA-Z%]|\$\w+/g, "")),
  };
}

export interface Exclusions {
  classes: Map<string, string>;
  methods: Map<string, string>;
  malformed: string[];
}

/** Reads `ClassName#methodName: reason` and `ClassName: reason` lines. */
export function exclusions(all: string[]): Exclusions {
  const out: Exclusions = { classes: new Map(), methods: new Map(), malformed: [] };
  for (const line of all) {
    const m = /^(\w+)(?:#(\w+))?\s*:\s*(\S.*)$/.exec(line);
    const target = m ? (m[2] ? out.methods : out.classes) : undefined;
    const name = m ? (m[2] ? `${m[1]}#${m[2]}` : m[1]!) : "";
    if (!m || !target || out.classes.has(name) || out.methods.has(name)) out.malformed.push(line);
    else target.set(name, m[3]!);
  }
  return out;
}

/** What's wrong between Java's live tests (class name to test methods) and the live test files here
 * (file name to source), given the exclusions. */
export function problems(java: Map<string, string[]>, files: Map<string, string>, excluded: Exclusions): string[] {
  const out = excluded.malformed.map((l) => `live-excluded.txt: '${l}' isn't 'Class#method: reason' or 'Class: reason', or is there twice`);
  for (const name of excluded.classes.keys()) if (!java.has(name)) out.push(`live-excluded.txt: Java has no live test class ${name}`);
  for (const name of excluded.methods.keys()) {
    const [cls, method] = name.split("#") as [string, string];
    if (!java.get(cls)?.includes(method)) out.push(`live-excluded.txt: Java has no live test ${name}`);
    else if (excluded.classes.has(cls)) out.push(`live-excluded.txt: ${name} is excluded with its whole class`);
  }
  const ports = new Set<string>();
  for (const [cls, methods] of java) {
    const file = portFile(cls);
    ports.add(file);
    const source = files.get(file);
    if (excluded.classes.has(cls)) {
      if (source !== undefined) out.push(`${cls} is excluded but ${file} is there: take it off live-excluded.txt`);
      continue;
    }
    if (source === undefined) {
      out.push(`${cls} has no ${file}`);
      continue;
    }
    const { describes, titles } = portTests(source);
    if (describes !== 1) out.push(`${file} has ${describes} top-level describes, not one`);
    const have = new Set(titles.map(key));
    for (const method of methods) {
      const isExcluded = excluded.methods.has(`${cls}#${method}`);
      if (isExcluded && have.has(key(method))) out.push(`${cls}#${method} is excluded but ${file} has it: take it off live-excluded.txt`);
      if (!isExcluded && !have.has(key(method))) out.push(`${file} has no test for ${cls}#${method}`);
    }
    const wanted = new Set(methods.map(key));
    for (const t of titles) if (!wanted.has(key(t))) out.push(`${file} has "${t}", which ${cls} doesn't: rename it, or move it to a file that isn't a port`);
  }
  for (const file of files.keys()) {
    if (!ports.has(file) && !PORT_ONLY.has(file)) out.push(`${file} ports no Java live test class: name it after one, or list it as this client's own`);
  }
  return out;
}

function javaLiveTests(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const file of javaFiles(Repo.javaTests).sort()) out.set(basename(file, ".java"), javaTests(readFileSync(file, "utf8")));
  return out;
}

function liveFiles(): Map<string, string> {
  const out = new Map<string, string>();
  for (const name of readdirSync(LIVE).sort()) if (name.endsWith(SUFFIX)) out.set(name, readFileSync(join(LIVE, name), "utf8"));
  return out;
}

/** The live tests are the Java client's, test for test, read from the Java sources
 * (java/tm1-client/src/test/java/.../*Tm1Test.java and *BehaviorTest.java): each class has its file
 * in test/live (`CubeServiceTm1Test` is `CubeService.live.test.ts`) with one top-level describe, and
 * each test method a test of the same name there (`createsReadsListsAndDeletesACube` is "creates,
 * reads, lists and deletes a cube": the same letters and digits). What isn't ported has a line in
 * coverage/live-excluded.txt saying why; a line for something Java doesn't have, or that is ported,
 * fails. A test Java doesn't have goes in a file that isn't a port (PortOnly.live.test.ts). */
describe("Java live test parity", () => {
  const java = javaLiveTests();

  it("reads Java's live tests", () => {
    expect(java.size).toBeGreaterThan(40);
    for (const [cls, methods] of java) expect(methods.length, cls).toBeGreaterThan(0);
  });

  it("has every Java live test, by its name, in its class's file", () => {
    expect(problems(java, liveFiles(), exclusions(lines("live-excluded.txt")))).toEqual([]);
  });

  describe("the check itself", () => {
    const one = new Map([["CubeServiceTm1Test", ["createsACube", "listsCubes"]]]);
    const file = (...titles: string[]): Map<string, string> =>
      new Map([
        ["CubeService.live.test.ts", `describe("x", () => {\n${titles.map((t) => `  tm1Test(${JSON.stringify(t)}, async () => {});`).join("\n")}\n});\n`],
      ]);
    const none = exclusions([]);

    it("passes when each method has its test", () => {
      expect(problems(one, file("creates a cube", "lists cubes"), none)).toEqual([]);
    });

    it("reads parameterized and multi-line tests", () => {
      const source =
        'describe("x", () => {\n  tm1Test.each(scenarios())("creates a cube: $name", f);\n  tm1Test(\n    "lists cubes",\n    f,\n  );\n  it.each(["a"])("lists cubes: %s", f);\n});\n';
      expect(portTests(source)).toEqual({ describes: 1, titles: ["creates a cube: ", "lists cubes", "lists cubes: "] });
    });

    it("fails on a missing file, a missing test, a test Java doesn't have, and a second describe", () => {
      expect(problems(one, new Map(), none)).toEqual(["CubeServiceTm1Test has no CubeService.live.test.ts"]);
      expect(problems(one, file("creates a cube"), none)).toEqual(["CubeService.live.test.ts has no test for CubeServiceTm1Test#listsCubes"]);
      expect(problems(one, file("creates a cube", "lists cubes", "drops cubes"), none)).toHaveLength(1);
      const twice = new Map([
        ["CubeService.live.test.ts", file("creates a cube", "lists cubes").get("CubeService.live.test.ts")! + 'describe("y", () => {});\n'],
      ]);
      expect(problems(one, twice, none)).toEqual(["CubeService.live.test.ts has 2 top-level describes, not one"]);
    });

    it("fails on a file that ports no Java class", () => {
      const files = file("creates a cube", "lists cubes").set("Cubes.live.test.ts", "").set("PortOnly.live.test.ts", "");
      expect(problems(one, files, none)).toEqual(["Cubes.live.test.ts ports no Java live test class: name it after one, or list it as this client's own"]);
    });

    it("accepts an exclusion with a reason, for a method or a class", () => {
      expect(problems(one, file("creates a cube"), exclusions(["CubeServiceTm1Test#listsCubes: tests Java's records"]))).toEqual([]);
      expect(problems(one, new Map(), exclusions(["CubeServiceTm1Test: tests Java's records"]))).toEqual([]);
    });

    it("fails on an exclusion that has rotted", () => {
      const rotten = (line: string, files = file("creates a cube", "lists cubes")): number => problems(one, files, exclusions([line])).length;
      expect(rotten("CubeServiceTm1Test#listsCubes"), "no reason").toBeGreaterThan(0);
      expect(rotten("CubeServiceTm1Test#listsCubes:  "), "an empty reason").toBeGreaterThan(0);
      expect(rotten("CubeServiceTm1Test#dropsCubes: gone"), "no such method").toBeGreaterThan(0);
      expect(rotten("ViewServiceTm1Test: gone"), "no such class").toBeGreaterThan(0);
      expect(rotten("CubeServiceTm1Test#listsCubes: ported since"), "ported after all").toBeGreaterThan(0);
      expect(rotten("CubeServiceTm1Test: ported since"), "the class is ported after all").toBeGreaterThan(0);
      expect(
        problems(one, file("creates a cube"), exclusions(["CubeServiceTm1Test#listsCubes: a", "CubeServiceTm1Test#listsCubes: b"])).length,
        "twice",
      ).toBeGreaterThan(0);
    });
  });
});
