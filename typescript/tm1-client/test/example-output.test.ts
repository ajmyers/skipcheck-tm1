// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { lines, sourceHash } from "./support/example-output.js";

/** The file every language hashes, with this result: api-site's tests check it too. */
const SOURCE = [
  "class Example {",
  "\tvoid other() {",
  "\t\t// @start region = other",
  "\t\tignored();",
  "\t\t// @end",
  "\t}",
  "",
  "\tvoid list(List<String> names) {",
  "\t\t// @start region = list",
  "\t\tfor (String name : names) {",
  "",
  "\t\t\tSystem.out.println(name);",
  "\t\t}",
  "\t\t// @end",
  "\t}",
  "}",
  "",
].join("\n");

const HASH = "674465729e3fa78a6fe01513b135a8aeaad2efe66b046efc5cd13eb55235eebc";

/** How a recording of an example's output hashes its code and keeps what it printed: the same in
 * every language, and in the site's build, which compares the hashes. The Java client's
 * `ExampleOutputTest`. */
describe("ExampleOutput", () => {
  it("hashes a region's lines without their indentation", () => {
    expect(sourceHash(SOURCE, "list")).toBe(HASH);
    expect(sourceHash(SOURCE.replace(/\t/g, "  ").replace(/\n/g, "\r\n"), "list"), "indented and ended another way").toBe(HASH);
    expect(sourceHash(SOURCE.replace("println(name)", "println(name.strip())"), "list")).not.toBe(HASH);
  });

  it("fails on a region the file doesn't have", () => {
    expect(() => sourceHash(SOURCE, "missing")).toThrow("no region missing");
  });

  it("keeps printed lines without their trailing space", () => {
    expect(lines("Jan: 12  \r\nFeb: 9\n")).toEqual(["Jan: 12", "Feb: 9"]);
    expect(lines("")).toEqual([]);
    expect(lines("a\n\nb\n\n")).toEqual(["a", "", "b"]);
  });
});
