// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import { describe, expect, it } from "vitest";
import { Repo } from "./support/files.js";

const EXCLUDED = "unit-excluded.txt";

/** A name with only its letters and digits, lower-cased: a Java method `startsWithAnEmptyDatabase`
 * and the title "starts with an empty database" are the same test. */
export function key(name: string): string {
  return name.replace(/[^A-Za-z0-9]/g, "").toLowerCase();
}

/** The test methods of a Java test class: those under `@Test` or `@ParameterizedTest`. */
export function javaTests(source: string): string[] {
  return [...source.matchAll(/@(?:Test|ParameterizedTest)\b[\s\S]*?\bvoid\s+(\w+)\s*\(/g)].map((m) => m[1]!);
}

/** The titles of a test file's tests, read from the source: the calls of `it`, `test` and this
 * package's own `requires…` (`requiresTm1`, `requiresPaw`). A title's placeholders for a
 * parameter (`%s` and `$name` in an `.each` title, `${name}` in a template) don't count: Java's
 * parameterized test is one method. A `$` in any other title is the title's own. */
export function portTests(source: string): string[] {
  const title = /\b(?:it|test|requires[A-Z]\w*)(?:\.(?!each\b)\w+)*(\.each\([\s\S]*?\))?\(\s*(["'`])((?:\\.|(?!\2)[^\\])*)\2/g;
  return [...source.matchAll(title)].map((m) => {
    const text = m[3]!.replace(/\$\{[^}]*\}/g, "");
    return m[1] ? text.replace(/%[a-zA-Z%]|\$\w+/g, "") : text;
  });
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

/** A coverage file's lines, without the blank ones and the comments. */
function lines(file: string): string[] {
  return readFileSync(join(Repo.coverage, file), "utf8")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith("#"));
}

function files(dir: string, suffix: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...files(path, suffix));
    else if (name.endsWith(suffix)) out.push(path);
  }
  return out;
}

/** Each Java test class with its test methods: a class with none (a helper) isn't one. */
function javaTestClasses(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const file of files(Repo.javaTests, "Test.java")) {
    const cls = basename(file, ".java");
    if (out.has(cls)) throw new Error(`two Java classes named ${cls}`);
    const tests = javaTests(readFileSync(file, "utf8"));
    if (tests.length > 0) out.set(cls, tests);
  }
  return out;
}

/** The title of every test in test/, by its key. */
function testsHere(): Set<string> {
  const out = new Set<string>();
  for (const file of files(Repo.typescriptTests, ".test.ts")) {
    for (const title of portTests(readFileSync(file, "utf8"))) out.add(key(title));
  }
  return out;
}

/** What keeps the tests here from being Java's: `java` is Java's test classes and their methods,
 * `here` the key of every test of this package. */
export function problems(java: Map<string, string[]>, here: Set<string>, excluded: Exclusions): string[] {
  const out = excluded.malformed.map((l) => `${EXCLUDED}: '${l}' isn't 'Class#method: reason' or 'Class: reason', or is there twice`);
  for (const name of excluded.classes.keys()) if (!java.has(name)) out.push(`${EXCLUDED}: Java has no test class ${name}`);
  for (const name of excluded.methods.keys()) {
    const [cls, method] = name.split("#") as [string, string];
    if (!java.has(cls)) out.push(`${EXCLUDED}: Java has no test class ${cls}`);
    else if (!java.get(cls)!.includes(method)) out.push(`${EXCLUDED}: ${cls} has no test ${method}`);
    else if (here.has(key(method))) out.push(`${EXCLUDED} excludes ${name}, but it is here: take the line off`);
    else if (excluded.classes.has(cls)) out.push(`${EXCLUDED} excludes ${name} and the whole of ${cls}`);
  }
  for (const [cls, methods] of java) {
    if (excluded.classes.has(cls)) continue;
    for (const method of methods) {
      if (!here.has(key(method)) && !excluded.methods.has(`${cls}#${method}`)) {
        out.push(`${cls}#${method} has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`);
      }
    }
  }
  return out;
}

/** The tests are the Java tm1-testcontainers', test for test, read from the Java sources
 * (java/tm1-testcontainers/src/test/java, its examples' tests included): it's what keeps a fix made
 * in Java from being left out here. A behaviour change starts with a Java test, and this fails until
 * that test is here too.
 *
 * Each test method of a Java test class has a test of the same name in some file under test/
 * (`startsWithAnEmptyDatabase` is "starts with an empty database": the same letters and digits), or
 * a line in coverage/unit-excluded.txt says why not: `ClassName#methodName: reason`, or
 * `ClassName: reason` for the whole class. A line for a class or a test Java doesn't have, for a test
 * this package has after all, or for a method of a class excluded whole, fails too, so the list
 * can't rot.
 *
 * A test may be in any file: the tests here are grouped by what they test, not by Java's classes.
 * One that needs TM1, TM1 Web, Cognos or PAW is here all the same, under `requiresTm1` and its
 * like, and skipped without one. This reads sources only. */
describe("Java test parity", () => {
  const java = javaTestClasses();

  it("reads Java's tests", () => {
    expect(java.size).toBeGreaterThan(15);
    expect([...java.values()].reduce((n, methods) => n + methods.length, 0)).toBeGreaterThan(60);
  });

  it("has every Java test, by its name, or says why not", () => {
    expect(problems(java, testsHere(), exclusions(lines(EXCLUDED)))).toEqual([]);
  });

  // Made-up classes and tests: this file is read with the others, so a real Java name in it would
  // count as that test
  describe("the check itself", () => {
    const java = new Map([
      ["QueueContainerTest", ["splitsABigQueue", "refusesCallsAfterItRan"]],
      ["SnapshotSourceTest", ["readsTheSnapshot"]],
    ]);
    const complete = new Set(["splitsabigqueue", "refusescallsafteritran", "readsthesnapshot"]);
    const check = (here: Set<string>, ...excluded: string[]): string[] => problems(java, here, exclusions(excluded));
    const without = (k: string): Set<string> => new Set([...complete].filter((c) => c !== k));

    it("passes when every test is here, whatever its file", () => {
      expect(check(complete)).toEqual([]);
      expect(check(new Set([...complete, "onlytypescripthasthis"]))).toEqual([]);
    });

    it("reads a Java class's test methods", () => {
      const source =
        "class QueueContainerTest {\n\t@Test\n\t@RequiresTm1\n\tvoid splitsABigQueue(Tm1ServerContainer tm1) {\n\t}\n\n" +
        '\t@ParameterizedTest\n\t@ValueSource(strings = { "a" })\n\tvoid refusesCallsAfterItRan(String name) {\n\t}\n\n' +
        "\tprivate static void helper() {\n\t}\n}\n";
      expect(javaTests(source)).toEqual(["splitsABigQueue", "refusesCallsAfterItRan"]);
    });

    it("reads a test's title as Java's name", () => {
      const titles = portTests('it("splits a big queue", f);\ntest.each([1])("refuses calls after it ran: %s", f);\n');
      expect(check(new Set([...titles.map(key), "readsthesnapshot"]))).toEqual([]);
    });

    it("reads the tests that need TM1, with or without options", () => {
      const source =
        'requiresTm1("splits a big queue", async ({ tm1 }) => {});\n' +
        'requiresPaw("refuses calls after it ran", timeout, async ({ paw }) => {});\n' +
        'requiresTm1Web(\n  "reads the snapshot",\n  f,\n);\n';
      expect(portTests(source)).toEqual(["splits a big queue", "refuses calls after it ran", "reads the snapshot"]);
      expect(check(new Set(portTests(source).map(key)))).toEqual([]);
    });

    it("leaves out a parameter's placeholder, but not a $ of the title's own", () => {
      const source = 'it.each(xs)("reads $name: %j", f);\nit(`reads ${name}`, f);\nit("reads $metadata", f);\n';
      expect(portTests(source).map(key)).toEqual(["reads", "reads", "readsmetadata"]);
    });

    it("fails on a Java test that isn't here", () => {
      expect(check(without("splitsabigqueue"))).toEqual([
        `QueueContainerTest#splitsABigQueue has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
    });

    it("accepts an exclusion with a reason, for a test or a class", () => {
      expect(
        check(
          new Set(["refusescallsafteritran"]),
          "QueueContainerTest#splitsABigQueue: this package sends a queue whole",
          "SnapshotSourceTest: tests Java's snapshot",
        ),
      ).toEqual([]);
    });

    it("fails on an exclusion that has rotted", () => {
      expect(check(complete, "QueueContainerTest#splitsABigQueue"), "no reason").toHaveLength(1);
      expect(check(complete, "QueueContainerTest#splitsABigQueue:  "), "an empty reason").toHaveLength(1);
      expect(check(complete, "GoneTest: was removed")).toEqual([`${EXCLUDED}: Java has no test class GoneTest`]);
      expect(check(complete, "GoneTest#gone: was removed")).toEqual([`${EXCLUDED}: Java has no test class GoneTest`]);
      expect(check(complete, "QueueContainerTest#renamedSince: no counterpart")).toEqual([`${EXCLUDED}: QueueContainerTest has no test renamedSince`]);
      expect(check(complete, "QueueContainerTest#splitsABigQueue: no counterpart")).toEqual([
        `${EXCLUDED} excludes QueueContainerTest#splitsABigQueue, but it is here: take the line off`,
      ]);
      expect(check(without("readsthesnapshot"), "SnapshotSourceTest: tests Java's snapshot", "SnapshotSourceTest#readsTheSnapshot: and again")).toEqual([
        `${EXCLUDED} excludes SnapshotSourceTest#readsTheSnapshot and the whole of SnapshotSourceTest`,
      ]);
      expect(check(complete, "SnapshotSourceTest: a", "SnapshotSourceTest: b"), "twice").toHaveLength(1);
    });
  });
});
