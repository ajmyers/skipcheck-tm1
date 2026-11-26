// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import { Repo } from "./support/files.js";

const EXCLUDED = "unit-excluded.txt";

/** A name with only its letters and digits, lower-cased: a Java method `startsWithAnEmptyDatabase`
 * and the title "starts with an empty database" are the same test. */
export function key(name: string): string {
  return name.replace(/[^A-Za-z0-9]/g, "").toLowerCase();
}

/** Java source with its comments (block and `//` line comments) replaced by a space. A string, a
 * text block or a char literal is copied as it is: what looks like a comment's start inside one
 * (the slash and star of "samples/…", the two slashes of "http://x") is no comment. */
export function withoutJavaComments(source: string): string {
  let out = "";
  let i = 0;
  const literal = (quote: string, oneLine: boolean): void => {
    const start = i;
    i += quote.length;
    while (i < source.length && !source.startsWith(quote, i) && !(oneLine && source[i] === "\n")) i += source[i] === "\\" ? 2 : 1;
    i = Math.min(source.length, source.startsWith(quote, i) ? i + quote.length : i);
    out += source.slice(start, i);
  };
  while (i < source.length) {
    if (source.startsWith('"""', i)) literal('"""', false);
    else if (source[i] === '"' || source[i] === "'") literal(source[i]!, true);
    else if (source.startsWith("//", i)) {
      while (i < source.length && source[i] !== "\n") i++;
      out += " ";
    } else if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      out += " ";
    } else out += source[i++];
  }
  return out;
}

/** The test methods of a Java test class: those annotated `@Test`, `@ParameterizedTest`,
 * `@RepeatedTest` or `@TestTemplate`, comments apart. The name is the next `void name(` after the
 * annotation, whatever comes between: the annotation's arguments, other annotations, modifiers. */
export function javaTests(source: string): string[] {
  const test = /@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate)\b[\s\S]*?\bvoid\s+(\w+)\s*\(/g;
  return [...withoutJavaComments(source).matchAll(test)].map((m) => m[1]!);
}

/** What a test is declared with, from the callee of its call: `it.skipIf(c)` is `it` with
 * `skipIf`, `test.each(…)` is `test` with `each`. */
function declaredWith(callee: ts.Expression): { base: string; modifiers: string[] } | undefined {
  const modifiers: string[] = [];
  for (let e = callee; ;) {
    if (ts.isIdentifier(e)) return { base: e.text, modifiers };
    if (ts.isPropertyAccessExpression(e)) {
      modifiers.push(e.name.text);
      e = e.expression;
    } else if (ts.isCallExpression(e) || ts.isParenthesizedExpression(e)) e = e.expression;
    else if (ts.isTaggedTemplateExpression(e)) e = e.tag;
    else return undefined;
  }
}

/** The titles of a test file's tests that run, read from the source as TypeScript (so not from a
 * comment or a string): the calls of `it`, `test` and this package's own `requires…`
 * (`requiresTm1`, `requiresPaw`), the curried ones too (`it.each(…)(title, …)`, `it.for(…)`,
 * `it.skipIf(…)`, `it.runIf(…)`). One declared with `.skip` or `.todo` never runs, and isn't a test
 * here. A title's placeholders for a parameter (`%s` and `$name` in an `.each` or `.for` title,
 * `${name}` in a template) don't count: Java's parameterized test is one method. A `$` in any other
 * title is the title's own. */
export function portTests(source: string): string[] {
  const out: string[] = [];
  const visit = (node: ts.Node): void => {
    ts.forEachChild(node, visit);
    if (!ts.isCallExpression(node)) return;
    // it.skipIf(c) in it.skipIf(c)("title", f) is not the test: the call it's the callee of is
    if (ts.isCallExpression(node.parent) && node.parent.expression === node) return;
    const title = node.arguments[0];
    const how = declaredWith(node.expression);
    if (!title || !how || !/^(?:it|test|requires[A-Z]\w*)$/.test(how.base)) return;
    if (how.modifiers.includes("skip") || how.modifiers.includes("todo")) return;
    let text: string;
    if (ts.isStringLiteralLike(title)) text = title.text;
    else if (ts.isTemplateExpression(title)) text = title.head.text + title.templateSpans.map((s) => s.literal.text).join("");
    else return;
    const parameterized = how.modifiers.includes("each") || how.modifiers.includes("for");
    out.push(parameterized ? text.replace(/%[a-zA-Z%]|\$\w+/g, "") : text);
  };
  visit(ts.createSourceFile("tests.ts", source, ts.ScriptTarget.Latest, true));
  return out;
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

/** How many tests here have each name: the titles of every test in test/, by their key. */
function testsHere(): Map<string, number> {
  return counted(files(Repo.typescriptTests, ".test.ts").flatMap((file) => portTests(readFileSync(file, "utf8")).map(key)));
}

/** How many times each of `keys` is there. */
export function counted(keys: Iterable<string>): Map<string, number> {
  const out = new Map<string, number>();
  for (const k of keys) out.set(k, (out.get(k) ?? 0) + 1);
  return out;
}

/** What keeps the tests here from being Java's: `java` is Java's test classes and their methods,
 * `here` how many tests of this package have each name (by its key).
 *
 * A name needs as many tests here as Java has classes with a test of that name, not counting the
 * classes excluded for it. An exclusion is stale when the tests here are enough without it. */
export function problems(java: Map<string, string[]>, here: Map<string, number>, excluded: Exclusions): string[] {
  const out = excluded.malformed.map((l) => `${EXCLUDED}: '${l}' isn't 'Class#method: reason' or 'Class: reason', or is there twice`);
  const isExcluded = (cls: string, method: string): boolean => excluded.classes.has(cls) || excluded.methods.has(`${cls}#${method}`);
  /** The Java tests of each name that aren't excluded, as Class#method. */
  const wanted = new Map<string, string[]>();
  for (const [cls, methods] of java) {
    for (const method of new Set(methods)) {
      if (!wanted.has(key(method))) wanted.set(key(method), []);
      if (!isExcluded(cls, method)) wanted.get(key(method))!.push(`${cls}#${method}`);
    }
  }
  /** Whether there's a test of this name here beyond those the classes not excluded need. */
  const spare = (method: string): boolean => (here.get(key(method)) ?? 0) > wanted.get(key(method))!.length;
  for (const name of excluded.classes.keys()) {
    if (!java.has(name)) out.push(`${EXCLUDED}: Java has no test class ${name}`);
    else if (java.get(name)!.every(spare)) out.push(`${EXCLUDED} excludes the whole of ${name}, but every test of it is here: take the line off`);
  }
  for (const name of excluded.methods.keys()) {
    const [cls, method] = name.split("#") as [string, string];
    if (!java.has(cls)) out.push(`${EXCLUDED}: Java has no test class ${cls}`);
    else if (!java.get(cls)!.includes(method)) out.push(`${EXCLUDED}: ${cls} has no test ${method}`);
    else if (spare(method)) out.push(`${EXCLUDED} excludes ${name}, but it is here: take the line off`);
    else if (excluded.classes.has(cls)) out.push(`${EXCLUDED} excludes ${name} and the whole of ${cls}`);
  }
  for (const [k, tests] of wanted) {
    const count = here.get(k) ?? 0;
    if (count >= tests.length) continue;
    if (tests.length === 1) out.push(`${tests[0]} has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`);
    else {
      out.push(
        `${tests.join(", ")}: ${tests.length} Java classes have a test of this name, and ${count} ${count === 1 ? "test" : "tests"} here ` +
          `${count === 1 ? "has" : "have"} it: port each class's (and what it tests), or say why not in ${EXCLUDED}`,
      );
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
 * `ClassName: reason` for the whole class. A name several Java classes have a test of needs as many
 * tests here, one for each class. A line for a class or a test Java doesn't have, for a test this
 * package has after all, for a class whose every test it has, or for a method of a class excluded
 * whole, fails too, so the list can't rot.
 *
 * A test may be in any file: the tests here are grouped by what they test, not by Java's classes.
 * One that needs TM1, TM1 Web, Cognos or PAW is here all the same, under `requiresTm1` and its
 * like, and skipped without one. A test in a comment, or declared with `.skip` or `.todo`, never
 * runs, and isn't here. This reads sources only. */
describe("Java test parity", () => {
  const java = javaTestClasses();

  it("reads Java's tests", () => {
    expect(java.size).toBeGreaterThan(15);
    expect([...java.values()].reduce((n, methods) => n + methods.length, 0)).toBeGreaterThan(60);
  });

  it("has every Java test, by its name, or says why not", () => {
    expect(problems(java, testsHere(), exclusions(lines(EXCLUDED)))).toEqual([]);
  });

  // Made-up classes and tests, so that none of this can be taken for one of Java's
  describe("the check itself", () => {
    const java = new Map([
      ["QueueContainerTest", ["splitsABigQueue", "refusesCallsAfterItRan"]],
      ["SnapshotSourceTest", ["readsTheSnapshot"]],
    ]);
    const complete = ["splitsabigqueue", "refusescallsafteritran", "readsthesnapshot"];
    const check = (here: string[], ...excluded: string[]): string[] => problems(java, counted(here), exclusions(excluded));
    const without = (k: string): string[] => complete.filter((c) => c !== k);

    it("passes when every test is here, whatever its file", () => {
      expect(check(complete)).toEqual([]);
      expect(check([...complete, "onlytypescripthasthis"])).toEqual([]);
    });

    it("reads a Java class's test methods", () => {
      const source =
        "class QueueContainerTest {\n\t@Test\n\t@RequiresTm1\n\tvoid splitsABigQueue(Tm1ServerContainer tm1) {\n\t}\n\n" +
        '\t@ParameterizedTest\n\t@ValueSource(strings = { "a" })\n\tvoid refusesCallsAfterItRan(String name) {\n\t}\n\n' +
        "\tprivate static void helper() {\n\t}\n}\n";
      expect(javaTests(source)).toEqual(["splitsABigQueue", "refusesCallsAfterItRan"]);
    });

    it("reads a Java test whatever is between its annotation and its name", () => {
      expect(javaTests("@Test // drains it\nvoid splitsABigQueue() {}"), "a comment after @Test").toEqual(["splitsABigQueue"]);
      expect(javaTests("@Test /* drains it */ void splitsABigQueue() {}"), "a block comment after @Test").toEqual(["splitsABigQueue"]);
      expect(javaTests("@Test\n// void notThisOne() {}\nvoid splitsABigQueue() {}"), "a // line between").toEqual(["splitsABigQueue"]);
      expect(javaTests('@ParameterizedTest(name = "{0} (legacy)")\n@ValueSource(ints = { 1 })\nvoid splitsABigQueue(int n) {}'), "parentheses").toEqual([
        "splitsABigQueue",
      ]);
      expect(javaTests("@Test\nprotected void splitsABigQueue() {}"), "protected").toEqual(["splitsABigQueue"]);
      expect(javaTests("@Test\npublic final void splitsABigQueue() {}"), "modifiers").toEqual(["splitsABigQueue"]);
      expect(javaTests('@Test\n@Tags({ @Tag("slow"), @Tag("queue") })\nvoid splitsABigQueue() {}'), "nested annotations").toEqual(["splitsABigQueue"]);
      expect(javaTests("@RepeatedTest(3)\nvoid splitsABigQueue() {}"), "@RepeatedTest").toEqual(["splitsABigQueue"]);
      expect(javaTests("@TestTemplate\nvoid splitsABigQueue() {}"), "@TestTemplate").toEqual(["splitsABigQueue"]);
    });

    it("doesn't take a Java comment for a test, or a string for a comment", () => {
      expect(javaTests("/** Runs before each {@code @Test}. */\n@BeforeEach\nvoid fillsTheQueue() {}"), "a Javadoc naming @Test").toEqual([]);
      expect(javaTests("// @Test void splitsABigQueue() {}\n"), "a test in a comment").toEqual([]);
      expect(javaTests("/* @Test\nvoid splitsABigQueue() {} */\n@Test void readsTheSnapshot() {}"), "a test in a block comment").toEqual(["readsTheSnapshot"]);
      expect(javaTests("@TestInstance(Lifecycle.PER_CLASS)\nclass QueueContainerTest {\n\t@BeforeAll\n\tvoid fillsTheQueue() {}\n}"), "@TestInstance").toEqual(
        [],
      );
      const strings =
        'static final String ALL = "samples/*";\nstatic final String URL = "http://queue";\nstatic final char QUOTE = \'"\';\n' +
        'static final String ESCAPED = "a \\" /* b";\n@Test\nvoid splitsABigQueue() {}\n// the end */\n';
      expect(javaTests(strings), 'a "/*" in a string').toEqual(["splitsABigQueue"]);
      expect(withoutJavaComments(strings)).toContain('"samples/*"');
      expect(withoutJavaComments(strings)).toContain('"http://queue"');
      expect(javaTests('String block = """\n\t/* not a "comment"\n\t""";\n@Test\nvoid splitsABigQueue() {}'), "a text block").toEqual(["splitsABigQueue"]);
    });

    it("reads a test's title as Java's name", () => {
      const titles = portTests('it("splits a big queue", f);\ntest.each([1])("refuses calls after it ran: %s", f);\n');
      expect(check([...titles.map(key), "readsthesnapshot"])).toEqual([]);
    });

    it("reads the tests that need TM1, with or without options", () => {
      const source =
        'requiresTm1("splits a big queue", async ({ tm1 }) => {});\n' +
        'requiresPaw("refuses calls after it ran", timeout, async ({ paw }) => {});\n' +
        'requiresTm1Web(\n  "reads the snapshot",\n  f,\n);\n';
      expect(portTests(source)).toEqual(["splits a big queue", "refuses calls after it ran", "reads the snapshot"]);
      expect(check(portTests(source).map(key))).toEqual([]);
    });

    it("leaves out a parameter's placeholder, but not a $ of the title's own", () => {
      const source = 'it.each(xs)("reads $name: %j", f);\nit(`reads ${name}`, f);\nit("reads $metadata", f);\n';
      expect(portTests(source).map(key)).toEqual(["reads", "reads", "readsmetadata"]);
    });

    it("reads the curried forms", () => {
      const source =
        'it.skipIf(windows)("splits a big queue", f);\nit.runIf(linux)("refuses calls after it ran", f);\n' +
        'it.for([1, 2])("reads the snapshot: %i", f);\ntest.each`\n  a | b\n  ${1} | ${2}\n`("drains $a", f);\nit.concurrent.each(xs)("fills %s", f);\n';
      expect(portTests(source)).toEqual(["splits a big queue", "refuses calls after it ran", "reads the snapshot: ", "drains ", "fills "]);
    });

    it("doesn't count a test that never runs", () => {
      expect(portTests('// it("splits a big queue", f);\n'), "in a comment").toEqual([]);
      expect(portTests('/* it("splits a big queue", f);\n   test("reads the snapshot", f); */\n'), "in a block comment").toEqual([]);
      expect(portTests('it.skip("splits a big queue", f);\ntest.skip("reads the snapshot", f);\n'), ".skip").toEqual([]);
      expect(portTests('it.todo("splits a big queue");\ntest.todo("reads the snapshot");\n'), ".todo").toEqual([]);
      expect(portTests('requiresTm1.skip("splits a big queue", f);\nit.skip.each(xs)("reads %s", f);\n'), ".skip, further on").toEqual([]);
      expect(portTests("const source = 'it(\"splits a big queue\", f);';\n"), "in a string").toEqual([]);
      expect(portTests('describe("the queue", () => {});\nexpect("splits a big queue").toBe(x);\n'), "not a test").toEqual([]);
      // What a reader of text would trip on: a quote in a regular expression, a // in a string
      const source = 'const quote = /(["\'`])/;\nconst url = "http://queue"; it("splits a big queue", f); // it("reads the snapshot", f);\n';
      expect(portTests(source)).toEqual(["splits a big queue"]);
      expect(check(["refusescallsafteritran", "readsthesnapshot", ...portTests('it.skip("splits a big queue", f);').map(key)])).toEqual([
        `QueueContainerTest#splitsABigQueue has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
    });

    it("fails on a Java test that isn't here", () => {
      expect(check(without("splitsabigqueue"))).toEqual([
        `QueueContainerTest#splitsABigQueue has no test here: port it (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
    });

    it("wants a test for each Java class that has one of the name", () => {
      const shared = new Map([
        ["QueueSourceTest", ["findsNoQueue"]],
        ["SnapshotSourceTest", ["findsNoQueue", "readsTheSnapshot"]],
        ["LedgerSourceTest", ["findsNoQueue"]],
      ]);
      const among = (here: string[], ...excluded: string[]): string[] => problems(shared, counted(here), exclusions(excluded));
      const three = ["findsnoqueue", "findsnoqueue", "findsnoqueue", "readsthesnapshot"];
      expect(among(three)).toEqual([]);
      expect(among(three.slice(1))).toEqual([
        "QueueSourceTest#findsNoQueue, SnapshotSourceTest#findsNoQueue, LedgerSourceTest#findsNoQueue: 3 Java classes have a test of this name, " +
          `and 2 tests here have it: port each class's (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
      expect(among(three.slice(2))[0]).toContain("3 Java classes have a test of this name, and 1 test here has it");
      expect(among(["readsthesnapshot"])[0]).toContain("3 Java classes have a test of this name, and 0 tests here have it");
      // An excluded class doesn't need its test, and its line is stale once there's one for it too
      expect(among(three.slice(1), "LedgerSourceTest#findsNoQueue: has no queue")).toEqual([]);
      expect(among(three.slice(1), "LedgerSourceTest: has no ledger")).toEqual([]);
      expect(among(three.slice(2), "LedgerSourceTest#findsNoQueue: has no queue")).toEqual([
        "QueueSourceTest#findsNoQueue, SnapshotSourceTest#findsNoQueue: 2 Java classes have a test of this name, " +
          `and 1 test here has it: port each class's (and what it tests), or say why not in ${EXCLUDED}`,
      ]);
      expect(among(three, "LedgerSourceTest#findsNoQueue: has no queue")).toEqual([
        `${EXCLUDED} excludes LedgerSourceTest#findsNoQueue, but it is here: take the line off`,
      ]);
      expect(among(three, "LedgerSourceTest: has no ledger")).toEqual([
        `${EXCLUDED} excludes the whole of LedgerSourceTest, but every test of it is here: take the line off`,
      ]);
    });

    it("accepts an exclusion with a reason, for a test or a class", () => {
      expect(
        check(["refusescallsafteritran"], "QueueContainerTest#splitsABigQueue: this package sends a queue whole", "SnapshotSourceTest: tests Java's snapshot"),
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
      expect(check(without("readsthesnapshot"), "SnapshotSourceTest: a", "SnapshotSourceTest: b"), "twice").toHaveLength(1);
    });

    it("fails on the exclusion of a class whose every test is here", () => {
      const stale = `${EXCLUDED} excludes the whole of QueueContainerTest, but every test of it is here: take the line off`;
      expect(check(complete, "QueueContainerTest: this package has no queue")).toEqual([stale]);
      expect(check(without("splitsabigqueue"), "QueueContainerTest: this package has no queue"), "one of its tests isn't").toEqual([]);
      expect(check(["readsthesnapshot"], "QueueContainerTest: this package has no queue"), "none is").toEqual([]);
    });
  });
});
