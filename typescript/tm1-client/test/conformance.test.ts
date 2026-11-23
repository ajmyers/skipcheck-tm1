// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ODataLiterals, ODataPath, ODataQuery, Filter, ODataProperty } from "../src/index.js";
import { Repo } from "./support/repo.js";

/** The OData encoding every client must agree on (spec/conformance/odata-encoding.json), which the
 * Java, Python and .NET clients' tests read too. */
const cases = JSON.parse(readFileSync(join(Repo.conformance, "odata-encoding.json"), "utf8")) as {
  paths: { steps: unknown[][]; expect: string }[];
  literals: { value: unknown; as?: string; expect: string }[];
};

describe("conformance", () => {
  it.each(cases.paths.map((c) => [c.expect, c.steps] as const))("builds %s", (expected, steps) => {
    let path: ODataPath | undefined;
    for (const [op, a, b] of steps as [string, unknown, unknown][]) {
      switch (op) {
        case "of":
          path = ODataPath.of(a as string);
          break;
        case "functionImport":
          path = ODataPath.functionImport(a as string, b as Record<string, unknown>);
          break;
        case "key":
          path = path!.key(a);
          break;
        case "compoundKey":
          path = path!.key(a as Record<string, unknown>);
          break;
        case "nav":
          path = path!.nav(a as string);
          break;
        case "cast":
          path = path!.cast(a as string);
          break;
        case "action":
          path = path!.action(a as string);
          break;
        case "function":
          path = path!.function(a as string, b as Record<string, unknown>);
          break;
        case "value":
          path = path!.value();
          break;
        case "count":
          path = path!.count();
          break;
        case "ref":
          path = path!.ref();
          break;
        default:
          throw new Error(op);
      }
    }
    expect(path!.toString()).toBe(expected);
  });

  it.each(cases.literals.map((c) => [c.expect, c.value] as const))("writes %s", (expected, value) => {
    expect(ODataLiterals.of(value)).toBe(expected);
  });

  it("writes queries in a fixed order, encoding each value once", () => {
    const q = ODataQuery.none()
      .top(5)
      .filter(ODataProperty.of("Name").eq("a b"))
      .select("Name", "Type")
      .expand("Hierarchies", (h) => h.select("Name").top(2))
      .orderByDescending("Name")
      .option("!sandbox", "What if");
    expect(q.toString()).toBe("$select=Name,Type&$expand=Hierarchies($select=Name;$top=2)&$filter=(Name eq 'a b')&$orderby=Name desc&$top=5&!sandbox=What if");
    expect(q.toQueryString()).toBe(
      "$select=Name,Type&$expand=Hierarchies($select%3DName;$top%3D2)&$filter=(Name%20eq%20'a%20b')&$orderby=Name%20desc&$top=5&!sandbox=What%20if",
    );
    expect(q.option("!sandbox")).toBe("What if");
    expect(ODataQuery.none().isEmpty()).toBe(true);
  });

  it("parenthesizes every binary filter", () => {
    const name = ODataProperty.of("Name");
    expect(Filter.and(name.eq("a"), Filter.or(name.eq("b"), Filter.not(name.isNull()))).toString()).toBe(
      "((Name eq 'a') and ((Name eq 'b') or not((Name eq null))))",
    );
    expect(Filter.any("Elements", "e", (e) => e.nav("Name").eq("x")).toString()).toBe("Elements/any(e:(e/Name eq 'x'))");
    expect(Filter.in(name, "a", 1).toString()).toBe("(Name in ('a',1))");
    expect(() => Filter.in(name)).toThrow();
  });

  it("checks names and keys", () => {
    expect(() => ODataPath.of("Cubes('x')")).toThrow(/Not an OData identifier/);
    expect(() => ODataPath.of("Cubes").key("a").key("b")).toThrow(/once/);
    expect(ODataPath.link("Cubes('a b')/Views").toString()).toBe("Cubes('a%20b')/Views");
    expect(
      ODataPath.of("Cubes")
        .function("tm1.Fn", { P: new ODataPath.Alias("@p") })
        .toString(),
    ).toBe("Cubes/tm1.Fn(P=@p)");
  });
});
