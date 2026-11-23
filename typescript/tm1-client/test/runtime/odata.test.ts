// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { Filter, ODataLiterals, ODataPath, ODataProperty, ODataQuery, type ODataLiteral } from "../../src/index.js";
import { seeded } from "../support/scripted-tm1.js";

/** Splits and decodes a query string once, as a server would. No '+' is ever sent, so '+' needs no
 * rule of its own. */
function parse(query: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const part of query.split("&")) {
    const eq = part.indexOf("=");
    out.set(decodeURIComponent(part.slice(0, eq)), decodeURIComponent(part.slice(eq + 1)));
  }
  return out;
}

/** What a URI may hold in its query (RFC 3986), as Java's strict `URI.create` checks. */
const QUERY_CHARS = /^([A-Za-z0-9\-._~!$&'()*+,;=:@/?]|%[0-9A-F]{2})*$/;
/** What a URI may hold in a path segment. */
const SEGMENT_CHARS = /^([A-Za-z0-9\-._~!$&'()*+,;=:@]|%[0-9A-F]{2})*$/;

/** Generates strings of 1 to `max` code points from `alphabet`. */
function fuzz(seed: number, alphabet: string, max: number): () => string {
  const random = seeded(seed);
  const cps = [...alphabet];
  return () => {
    let v = "";
    for (let j = 0, n = 1 + random(max); j < n; j++) v += cps[random(cps.length)];
    return v;
  };
}

/** The Java client's `ODataQueryTest`. */
describe("ODataQuery", () => {
  it("is empty by default", () => {
    expect(ODataQuery.none().toQueryString()).toBe("");
    expect(ODataQuery.none().isEmpty()).toBe(true);
  });

  it("writes options in a fixed order", () => {
    const query = ODataQuery.none().top(10).withCount().orderBy("Name").skip(5).filter(Filter.raw("Level eq 0")).expand("Hierarchies").select("Name", "Type");
    expect(query.toString()).toBe("$select=Name,Type&$expand=Hierarchies&$filter=Level eq 0&$orderby=Name&$top=10&$skip=5&$count=true");
  });

  it("nests expand options with semicolons", () => {
    const query = ODataQuery.none()
      .select("Name")
      .expand("Hierarchies", (h) => h.select("Name").expand("Elements", (e) => e.select("Name").top(1)))
      .expand("Cube");
    expect(query.toString()).toBe("$select=Name&$expand=Hierarchies($select=Name;$expand=Elements($select=Name;$top=1)),Cube");
    // '=' inside a nested option is encoded; TM1 accepts it
    expect(query.toQueryString()).toBe("$select=Name&$expand=Hierarchies($select%3DName;$expand%3DElements($select%3DName;$top%3D1)),Cube");
  });

  it("applies nested options in any order", () => {
    expect(
      ODataQuery.none()
        .expand("Hierarchies", (h) => h.top(1).select("Name").orderBy("Name"))
        .toString(),
    ).toBe("$expand=Hierarchies($select=Name;$orderby=Name;$top=1)");
  });

  it("merges repeated expands of one navigation", () => {
    expect(
      ODataQuery.none()
        .expand("Hierarchies", (h) => h.select("Name"))
        .expand("Hierarchies", (h) => h.top(2))
        .toString(),
    ).toBe("$expand=Hierarchies($select=Name;$top=2)");
  });

  it("orders descending", () => {
    expect(ODataQuery.none().orderByDescending("TimeStamp").orderBy("ID").toString()).toBe("$orderby=TimeStamp desc,ID");
  });

  it("encodes each value once", () => {
    const encoded = ODataQuery.none().filter(ODataProperty.of("Name").eq("a&b=c+d #e%f")).option("!sandbox", "What if & more").toQueryString();
    expect(encoded).not.toContain(" ");
    expect(encoded).not.toContain("+");
    for (const escape of ["%26", "%3D", "%2B", "%23", "%25"]) expect(encoded).toContain(escape);
    const back = parse(encoded);
    expect(back.get("$filter")).toBe("(Name eq 'a&b=c+d #e%f')");
    expect(back.get("!sandbox")).toBe("What if & more");
  });

  it("survives fuzzed filter values", () => {
    const next = fuzz(42, "aZ0 '\"/%{}&=+#?!$@:;,()*É日😀", 10);
    for (let i = 0; i < 3000; i++) {
      const v = next();
      const encoded = ODataQuery.none().filter(ODataProperty.of("Name").eq(v)).option("!sandbox", v).toQueryString();
      expect(QUERY_CHARS.test(encoded), v).toBe(true);
      const back = parse(encoded);
      expect(back.get("$filter"), v).toBe("(Name eq " + ODataLiterals.string(v) + ")");
      expect(back.get("!sandbox"), v).toBe(v);
    }
  });

  it("defines parameter aliases", () => {
    const query = ODataQuery.none().alias(new ODataPath.Alias("@tuple"), '["a","b"]');
    expect(query.toString()).toBe('@tuple=["a","b"]');
    expect(parse(query.toQueryString()).get("@tuple")).toBe('["a","b"]');
  });

  it("refuses bad options", () => {
    expect(() => ODataQuery.none().option("$top", "1")).toThrow(RangeError);
    expect(() => ODataQuery.none().option("@p", "1")).toThrow(RangeError);
    expect(() => ODataQuery.none().top(-1)).toThrow(RangeError);
    expect(() => new ODataPath.Alias("p")).toThrow(RangeError);
  });

  it("is immutable", () => {
    const base = ODataQuery.none().select("Name");
    const more = base.top(1);
    expect(base.toString()).toBe("$select=Name");
    expect(more.toString()).toBe("$select=Name&$top=1");
    expect(base.option("!sandbox")).toBeUndefined();
    expect(ODataQuery.none().isEmpty(), "none() is never changed").toBe(true);
  });

  it("takes properties for expand and order by", () => {
    const dimensions = ODataProperty.of("Dimensions");
    const name = ODataProperty.of("Name");
    const withProperties = ODataQuery.none()
      .expand(dimensions, (d) => d.select(name))
      .expand(ODataProperty.of("Views"))
      .orderBy(name)
      .orderByDescending(ODataProperty.of("LastDataUpdate"));
    const withNames = ODataQuery.none()
      .expand("Dimensions", (d) => d.select("Name"))
      .expand("Views")
      .orderBy("Name")
      .orderByDescending("LastDataUpdate");
    expect(withProperties.equals(withNames)).toBe(true);
    expect(withProperties.toString()).toBe(withNames.toString());
  });
});

/** The Java client's `FilterTest`. */
describe("Filter", () => {
  const NAME = ODataProperty.of("Name");
  const LEVEL = ODataProperty.of("Level");

  it("compares with literals", () => {
    expect(NAME.eq("O'Neil").toString()).toBe("(Name eq 'O''Neil')");
    expect(LEVEL.ge(0).toString()).toBe("(Level ge 0)");
    // Java's BigDecimal keeps its scale; here a preformed literal does
    expect(ODataProperty.of("Weight").lt(new ODataLiterals.Raw("1.50")).toString()).toBe("(Weight lt 1.50)");
    expect(ODataProperty.of("Value").gt(2.5).toString()).toBe("(Value gt 2.5)");
    expect(ODataProperty.of("Active").eq(true).toString()).toBe("(Active eq true)");
    expect(ODataProperty.of("Parent").isNull().toString()).toBe("(Parent eq null)");
    expect(
      ODataProperty.of("Type")
        .eq(new ODataLiterals.Raw(ODataLiterals.enumMember("ibm.tm1.api.v1.ElementType", "Numeric")))
        .toString(),
    ).toBe("(Type eq ibm.tm1.api.v1.ElementType'Numeric')");
    expect(ODataProperty.of("Level").ne(1).toString()).toBe("(Level ne 1)");
    expect(ODataProperty.of("Level").le(1).toString()).toBe("(Level le 1)");
  });

  it("parenthesizes every combination", () => {
    const f = Filter.or(Filter.and(NAME.eq("a"), LEVEL.eq(0)), Filter.not(Filter.startsWith(NAME, "}")));
    expect(f.toString()).toBe("(((Name eq 'a') and (Level eq 0)) or not(startswith(Name,'}')))");
    expect(Filter.and(NAME.eq("a")).equals(NAME.eq("a"))).toBe(true);
    expect(NAME.eq("a").and(LEVEL.eq(0)).or(LEVEL.eq(1)).toString()).toBe("(((Name eq 'a') and (Level eq 0)) or (Level eq 1))");
  });

  it("keeps a raw expression's meaning when combined", () => {
    const either = Filter.raw("A eq 1 or B eq 2");
    expect(either.toString()).toBe("A eq 1 or B eq 2");
    expect(Filter.and(either, NAME.eq("x")).toString()).toBe("((A eq 1 or B eq 2) and (Name eq 'x'))");
    expect(NAME.eq("x").or(Filter.raw("A eq 1 and B eq 2")).toString()).toBe("((Name eq 'x') or (A eq 1 and B eq 2))");
    expect(Filter.and(either).toString()).toBe("A eq 1 or B eq 2");
    expect(Filter.not(either).toString()).toBe("not(A eq 1 or B eq 2)");
  });

  it("uses canonical functions", () => {
    expect(Filter.contains(NAME, "Q1").toString()).toBe("contains(Name,'Q1')");
    expect(Filter.endsWith(NAME, "'s").toString()).toBe("endswith(Name,'''s')");
    expect(Filter.in(NAME, "a", "b").toString()).toBe("(Name in ('a','b'))");
  });

  it("writes lambdas", () => {
    expect(Filter.any("Hierarchies", "h", (h) => h.nav("Name").eq("}Clients")).toString()).toBe("Hierarchies/any(h:(h/Name eq '}Clients'))");
    expect(Filter.all("Tags", "t", (t) => t.self().ne("x")).toString()).toBe("Tags/all(t:(t ne 'x'))");
  });

  it("refuses values without a clear literal", () => {
    expect(() => NAME.eq({})).toThrow(TypeError);
    expect(() => NAME.eq(Symbol("x"))).toThrow(TypeError);
    expect(() => Filter.in(NAME)).toThrow(RangeError);
    expect(() => Filter.and()).toThrow(RangeError);
    expect(() => Filter.any("X", "1v", (v) => v.self().eq(1))).toThrow(RangeError);
  });

  it("writes special numbers", () => {
    expect(ODataLiterals.of(Number.NaN)).toBe("NaN");
    expect(ODataLiterals.of(Number.NEGATIVE_INFINITY)).toBe("-INF");
    expect(ODataLiterals.of(Number.POSITIVE_INFINITY)).toBe("INF");
    expect(ODataLiterals.of(1e-7)).toBe("0.0000001");
    expect(ODataLiterals.of(12n)).toBe("12");
    expect(ODataLiterals.of(12)).toBe("12");
  });

  it("writes a value that knows its own literal", () => {
    const numeric: ODataLiteral = { odataLiteral: () => "ibm.tm1.api.v1.ElementType'Numeric'" };
    expect(ODataProperty.of("Type").eq(numeric).toString()).toBe("(Type eq ibm.tm1.api.v1.ElementType'Numeric')");
  });
});

/** Decodes a path once, as a server would, and reads back the first segment's key literal. */
function keyOf(path: ODataPath): string {
  const segment = path.toString().split("/")[0]!;
  expect(SEGMENT_CHARS.test(segment), segment).toBe(true);
  const decoded = decodeURIComponent(segment);
  const literal = decoded.slice(decoded.indexOf("(") + 1, decoded.lastIndexOf(")"));
  expect(literal.startsWith("'") && literal.endsWith("'"), literal).toBe(true);
  return literal.slice(1, -1).replace(/''/g, "'");
}

/** The Java client's `ODataPathTest`. */
describe("ODataPath", () => {
  it("addresses the metadata document", () => {
    expect(ODataPath.metadata().toString()).toBe("$metadata");
    expect(() => ODataPath.metadata().key("x")).toThrow(Error);
  });

  it("builds nested paths", () => {
    expect(ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements").toString()).toBe(
      "Dimensions('Region')/Hierarchies('Region')/Elements",
    );
    expect(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve").toString()).toBe("Cubes('Sales')/tm1.Reserve");
    expect(ODataPath.of("Cubes").key("Sales").nav("Views").cast("tm1.NativeView").toString()).toBe("Cubes('Sales')/Views/tm1.NativeView");
    expect(ODataPath.of("Configuration").nav("ProductVersion").value().toString()).toBe("Configuration/ProductVersion/$value");
    expect(ODataPath.of("Dimensions").count().toString()).toBe("Dimensions/$count");
    expect(ODataPath.of("Cubes").key("Sales").nav("Dimensions").ref().toString()).toBe("Cubes('Sales')/Dimensions/$ref");
    expect(ODataPath.of("Threads").key(42).toString()).toBe("Threads(42)");
  });

  it("writes compound keys in order", () => {
    const key = new Map<string, unknown>([
      ["ParentName", "Total"],
      ["ComponentName", "Q1 '26"],
    ]);
    expect(ODataPath.of("Edges").key(key).toString()).toBe("Edges(ParentName='Total',ComponentName='Q1%20''26')");
  });

  it("calls functions with literals or aliases", () => {
    expect(ODataPath.of("Cellsets").key("abc").function("tm1.GetPartition", { Begin: 0, End: 99 }).toString()).toBe(
      "Cellsets('abc')/tm1.GetPartition(Begin=0,End=99)",
    );
    expect(
      ODataPath.of("Hierarchies")
        .function("tm1.Fn", { p: new ODataPath.Alias("@p") })
        .toString(),
    ).toBe("Hierarchies/tm1.Fn(p=@p)");
  });

  it.each(["}Clients", "Year 2026", "O'Neil", "a/b", "50%", "{x}", "a&b=c+d#e", "Région", "日本", "😀", "''", "%2F", "?", "\\", "a\tb"])(
    "keeps the awkward name %j intact",
    (name) => {
      expect(keyOf(ODataPath.of("Dimensions").key(name).nav("Hierarchies"))).toBe(name);
    },
  );

  it("encodes slashes so they can't split the path", () => {
    expect(ODataPath.of("Dimensions").key("a/b").toString()).toBe("Dimensions('a%2Fb')");
    expect(ODataPath.of("Dimensions").key("O'Neil").toString()).toBe("Dimensions('O''Neil')");
    expect(ODataPath.of("Dimensions").key("}Clients").toString()).toBe("Dimensions('%7DClients')");
    expect(ODataPath.of("Dimensions").key("a b").toString()).toBe("Dimensions('a%20b')");
  });

  it("survives fuzzed names", () => {
    const next = fuzz(20261121, "aZ09 '\"/\\%{}[]()&=+#?!$@:;,.*~_-^|<>`\tÉé日😀", 12);
    for (let i = 0; i < 5000; i++) {
      const name = next();
      const path = ODataPath.of("Cubes").key(name).nav("Views").key(name);
      for (const segment of path.toString().split("/")) expect(SEGMENT_CHARS.test(segment), name).toBe(true);
      expect(keyOf(ODataPath.of("Cubes").key(name)), name).toBe(name);
    }
  });

  it.each(["", "Cubes('x')", "a/b", "1abc", "$metadata", "Cubes Sales"])("refuses the name %j, which isn't an identifier", (name) => {
    expect(() => ODataPath.of(name)).toThrow(RangeError);
    expect(() => ODataPath.of("Cubes").nav(name)).toThrow(RangeError);
  });

  it("refuses two keys on one segment", () => {
    expect(() => ODataPath.of("Cubes").key("a").key("b")).toThrow(Error);
    expect(() => ODataPath.of("Cubes").count().key("a")).toThrow(Error);
  });

  it("is a value type", () => {
    expect(ODataPath.of("Cubes").key("a").equals(ODataPath.of("Cubes").key("a"))).toBe(true);
    expect(ODataPath.of("Cubes").key("a").equals(ODataPath.of("Cubes").key("b"))).toBe(false);
  });

  it("calls a function import from the service root", () => {
    expect(ODataPath.functionImport("ModelCubes", {}).toString()).toBe("ModelCubes()");
    expect(ODataPath.functionImport("TailMessageLog", { Top: 10 }).toString()).toBe("TailMessageLog(Top=10)");
  });

  it("follows a server link, encoding each segment", () => {
    expect(ODataPath.link("MessageLogEntries/!delta('0[4096]38@11!19')").toString()).toBe("MessageLogEntries/!delta('0%5B4096%5D38%4011!19')");
    expect(() => ODataPath.link("Cubes?$top=1")).toThrow(RangeError);
    expect(() => ODataPath.link("/api/v1/Cubes")).toThrow(RangeError);
  });
});
