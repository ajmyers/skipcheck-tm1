// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join, relative, sep } from "node:path";
import { describe, expect, it } from "vitest";
import { lines } from "./coverage-gate.test.js";
import { type Exclusions, exclusions, javaTests, key, portTests } from "./java-live-parity.test.js";
import { Repo } from "./support/repo.js";

const TESTS = join(Repo.typescript, "test");
const EXCLUDED = "unit-excluded.txt";

/** Java's live tests: test/java-live-parity.test.ts holds this client to those. */
const LIVE = /(Tm1Test|BehaviorTest)$/;

/** Java's service tests that record their calls: every client replays the recordings in spec/conformance. */
const RECORDS = "new Conformance(";

function files(dir: string, suffix: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...files(path, suffix));
    else if (name.endsWith(suffix)) out.push(path);
  }
  return out;
}

/** Each Java unit test class that is neither live nor recorded, with its test methods: a class with
 * none (a base class, a helper) isn't one. */
function javaUnitTests(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const file of files(Repo.javaTests, "Test.java")) {
    const cls = basename(file, ".java");
    const source = readFileSync(file, "utf8");
    if (LIVE.test(cls) || source.includes(RECORDS)) continue;
    if (out.has(cls)) throw new Error(`two Java classes named ${cls}`);
    const tests = javaTests(source);
    if (tests.length > 0) out.set(cls, tests);
  }
  return out;
}

/** The title of every test in test/, outside test/live, by its key. */
function unitTestsHere(): Set<string> {
  const out = new Set<string>();
  for (const file of files(TESTS, ".test.ts")) {
    if (relative(TESTS, file).split(sep)[0] === "live") continue;
    for (const title of portTests(readFileSync(file, "utf8")).titles) out.add(key(title));
  }
  return out;
}

/** What keeps the unit tests here from being Java's: `java` is Java's unit test classes and their
 * methods, `here` the key of every unit test of this client. */
export function problems(java: Map<string, string[]>, here: Set<string>, excluded: Exclusions): string[] {
  const out = excluded.malformed.map((l) => `${EXCLUDED}: '${l}' isn't 'Class#method: reason' or 'Class: reason', or is there twice`);
  for (const name of excluded.classes.keys()) if (!java.has(name)) out.push(`${EXCLUDED}: Java has no unit test class ${name}`);
  for (const name of excluded.methods.keys()) {
    const [cls, method] = name.split("#") as [string, string];
    if (!java.has(cls)) out.push(`${EXCLUDED}: Java has no unit test class ${cls}`);
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

/** The unit tests are the Java client's, test for test, read from the Java sources
 * (java/tm1-client/src/test/java): it's what keeps a fix made in one client from being left out of
 * another. A behaviour change starts with a Java test, and this fails until that test is here too.
 *
 * Each test method of a Java unit test class has a test of the same name in some file under test/,
 * outside test/live (`followsANextLinkInAResult` is "follows a next link in a result": the same
 * letters and digits), or a line in coverage/unit-excluded.txt says why not: `ClassName#methodName:
 * reason`, or `ClassName: reason` for the whole class. A line for a class or a test Java doesn't
 * have, for a test this client has after all, or for a method of a class excluded whole, fails too,
 * so the list can't rot.
 *
 * Two kinds of Java test class are someone else's to check, and aren't read here: the live tests
 * (`*Tm1Test`, `*BehaviorTest`: test/java-live-parity.test.ts), and the service tests that record
 * their calls (`new Conformance(`: every client replays the recordings in spec/conformance).
 *
 * Unlike the live gate, a test may be in any file: the unit tests here are grouped by what they test,
 * not by Java's classes. This reads sources only. */
describe("Java unit test parity", () => {
  const java = javaUnitTests();

  it("reads Java's unit tests", () => {
    expect(java.size).toBeGreaterThan(20);
    expect([...java.values()].reduce((n, methods) => n + methods.length, 0)).toBeGreaterThan(150);
  });

  it("has every Java unit test, by its name, or says why not", () => {
    expect(problems(java, unitTestsHere(), exclusions(lines(EXCLUDED)))).toEqual([]);
  });

  describe("the check itself", () => {
    const java = new Map([
      ["Tm1SessionBatchTest", ["splitsABigQueue", "refusesCallsAfterItRan"]],
      ["MetadataSnapshotTest", ["readsTheSnapshot"]],
    ]);
    const complete = new Set(["splitsabigqueue", "refusescallsafteritran", "readsthesnapshot"]);
    const check = (here: Set<string>, ...excluded: string[]): string[] => problems(java, here, exclusions(excluded));
    const without = (k: string): Set<string> => new Set([...complete].filter((c) => c !== k));

    it("passes when every test is here, whatever its file", () => {
      expect(check(complete)).toEqual([]);
      expect(check(new Set([...complete, "onlytypescripthasthis"]))).toEqual([]);
    });

    it("reads a test's title as Java's name", () => {
      const titles = portTests('it("splits a big queue", f);\ntest.each([1])("refuses calls after it ran: %s", f);\n').titles;
      expect(check(new Set([...titles.map(key), "readsthesnapshot"]))).toEqual([]);
    });

    it("leaves out a parameter's placeholder, but not a $ of the title's own", () => {
      const source = 'it.each(xs)("reads $name: %j", f);\nit(`reads ${name}`, f);\nit("reads $metadata", f);\n';
      expect(portTests(source).titles.map(key)).toEqual(["reads", "reads", "readsmetadata"]);
    });

    it("fails on a Java test that isn't here", () => {
      expect(check(without("splitsabigqueue"))).toEqual([
        `Tm1SessionBatchTest#splitsABigQueue has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
    });

    it("accepts an exclusion with a reason, for a test or a class", () => {
      expect(
        check(
          new Set(["refusescallsafteritran"]),
          "Tm1SessionBatchTest#splitsABigQueue: this client sends a batch whole",
          "MetadataSnapshotTest: tests Java's snapshot",
        ),
      ).toEqual([]);
    });

    it("fails on an exclusion that has rotted", () => {
      expect(check(complete, "Tm1SessionBatchTest#splitsABigQueue"), "no reason").toHaveLength(1);
      expect(check(complete, "Tm1SessionBatchTest#splitsABigQueue:  "), "an empty reason").toHaveLength(1);
      expect(check(complete, "GoneTest: was removed")).toEqual([`${EXCLUDED}: Java has no unit test class GoneTest`]);
      expect(check(complete, "GoneTest#gone: was removed")).toEqual([`${EXCLUDED}: Java has no unit test class GoneTest`]);
      expect(check(complete, "Tm1SessionBatchTest#renamedSince: no counterpart")).toEqual([`${EXCLUDED}: Tm1SessionBatchTest has no test renamedSince`]);
      expect(check(complete, "Tm1SessionBatchTest#splitsABigQueue: no counterpart")).toEqual([
        `${EXCLUDED} excludes Tm1SessionBatchTest#splitsABigQueue, but it is here: take the line off`,
      ]);
      expect(check(without("readsthesnapshot"), "MetadataSnapshotTest: tests Java's snapshot", "MetadataSnapshotTest#readsTheSnapshot: and again")).toEqual([
        `${EXCLUDED} excludes MetadataSnapshotTest#readsTheSnapshot and the whole of MetadataSnapshotTest`,
      ]);
      expect(check(complete, "MetadataSnapshotTest: a", "MetadataSnapshotTest: b"), "twice").toHaveLength(1);
    });
  });
});
