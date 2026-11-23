// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { ODataPath, ODataQuery, Tm1Capabilities, Tm1Capability, Tm1Dialect, Tm1Operation, Tm1Request, Tm1Session, Tm1Version } from "../../src/index.js";
import { Repo } from "../support/repo.js";

const SNAPSHOT = readFileSync(Repo.snapshot, "utf8");

/** The snapshot as an approximate server of schema `version`, as the Java tests'
 * `ApproximateSchemas.asOf`: without the elements whose `Core.Revisions` say they were added after
 * it (a bound operation's binding parameter never goes), and reporting that schema version. */
function asOf(xml: string, version: string): string {
  const target = Tm1Version.parse(version);
  const removable = new Set([
    "EntityType",
    "ComplexType",
    "EnumType",
    "Member",
    "Property",
    "NavigationProperty",
    "Action",
    "Function",
    "Parameter",
    "EntitySet",
    "Singleton",
    "ActionImport",
    "FunctionImport",
  ]);
  interface Open {
    name: string;
    start: number;
    added?: string;
    version?: string;
    revisions?: Open;
    owner?: Open;
    firstParameter?: boolean;
  }
  const stack: Open[] = [];
  const cuts: [number, number][] = [];
  const tag = /<(\/?)([A-Za-z_][\w.:-]*)([^>]*?)(\/?)>|([^<]+)/g;
  let m: RegExpExecArray | null;
  while ((m = tag.exec(xml)) !== null) {
    if (m[5] !== undefined) {
      const text = m[5].trim();
      const top = stack[stack.length - 1];
      if (top?.name === "String" && stack[stack.length - 2]?.name === "PropertyValue") {
        const record = stack.findLast((e) => e.name === "Record");
        if (record) record.version = text;
      }
      if (top?.name === "EnumMember" && text === "Core.RevisionKind/Added") {
        const record = stack.findLast((e) => e.name === "Record");
        const revisions = stack.findLast((e) => e.name === "Annotation" && e.owner);
        if (record?.version && revisions?.owner) revisions.owner.added = record.version;
      }
      continue;
    }
    const name = m[2]!.replace(/^.*:/, "");
    if (m[1] === "/") {
      const open = stack.pop()!;
      if (removable.has(open.name) && open.added && target.isBefore(Tm1Version.parse(open.added)) && !open.firstParameter)
        cuts.push([open.start, tag.lastIndex]);
      continue;
    }
    const open: Open = { name, start: m.index };
    const parent = stack[stack.length - 1];
    if (name === "Annotation" && /Term="Core\.Revisions"/.test(m[3]!) && parent && removable.has(parent.name)) open.owner = parent;
    if (
      name === "Parameter" &&
      parent &&
      (parent.name === "Action" || parent.name === "Function") &&
      /IsBound="true"/.test(xml.slice(parent.start, parent.start + 200)) &&
      !xml.slice(parent.start, m.index).includes("<Parameter")
    )
      open.firstParameter = true;
    if (m[4] !== "/") stack.push(open);
  }
  let out = "";
  let at = 0;
  for (const [start, end] of cuts.filter(([s, e]) => !cuts.some(([s2, e2]) => s2 < s && e <= e2)).sort((a, b) => a[0] - b[0])) {
    out += xml.slice(at, start);
    at = end;
  }
  out += xml.slice(at);
  return out
    .replace(/(<Annotation Term="Core\.SchemaVersion">\s*<String>)[^<]*(<\/String>)/, `$1${version}$2`)
    .replace(/(<Annotation Term="Core\.SchemaVersion" String=")[^"]*"/, `$1${version}"`);
}

/** The Java client's `Tm1VersionTest`. */
describe("Tm1Version", () => {
  it("keeps the text as reported", () => {
    expect(Tm1Version.parse(" 11.8.03500.6\n").toString()).toBe("11.8.03500.6");
  });

  it("compares parts numerically", () => {
    expect(Tm1Version.parse("11.8.03500.6").equals(Tm1Version.parse("11.8.3500.6"))).toBe(true);
    expect(Tm1Version.parse("11.8.03500.6").compareTo(Tm1Version.parse("11.8.02800.9"))).toBeGreaterThan(0);
    expect(Tm1Version.parse("11.10").compareTo(Tm1Version.parse("11.9.99999"))).toBeGreaterThan(0);
    expect(Tm1Version.parse("12.0.0").compareTo(Tm1Version.parse("11.8.03500.6"))).toBeGreaterThan(0);
    expect(Tm1Version.parse("11.8.02800.9").compareTo("11.8.03500.6")).toBeLessThan(0);
  });

  it("treats missing parts as zero", () => {
    expect(Tm1Version.parse("11.8").equals(Tm1Version.parse("11.8.0.0"))).toBe(true);
    expect(Tm1Version.parse("11.8").compareTo(Tm1Version.parse("11.8.0.0"))).toBe(0);
    expect(Tm1Version.parse("11.8.1").compareTo(Tm1Version.parse("11.8"))).toBeGreaterThan(0);
  });

  it("answers range questions", () => {
    const v = Tm1Version.parse("11.8.03500.6");
    expect(v.isAtLeast(Tm1Version.parse("11.8.03500.6"))).toBe(true);
    expect(v.isAtLeast(Tm1Version.parse("11.8.02300"))).toBe(true);
    expect(v.isAtLeast(Tm1Version.parse("11.8.03600"))).toBe(false);
    expect(v.isBefore(Tm1Version.parse("12"))).toBe(true);
    expect(v.isBefore(v)).toBe(false);
  });

  it.each(["", "11.", ".8", "11..8", "v11.8", "11.8-beta", "99999999999"])("rejects %j, which isn't a version", (text) => {
    expect(() => Tm1Version.parse(text)).toThrow(/Not a TM1 version/);
  });
});

/** The Java client's `Tm1DialectTest`. */
describe("Tm1Dialect", () => {
  it("finds the TM1 11 service root from an address", () => {
    expect(Tm1Dialect.V11.serviceRoot("http://tm1:8010")).toBe("http://tm1:8010/api/v1/");
    expect(Tm1Dialect.V11.serviceRoot("https://tm1.example.com:12354/")).toBe("https://tm1.example.com:12354/api/v1/");
    expect(Tm1Dialect.V11.serviceRoot("http://tm1:8010/api/v1"), "already a root").toBe("http://tm1:8010/api/v1/");
    expect(Tm1Dialect.V11.serviceRoot("http://proxy/tm1/sales/"), "behind a proxy path").toBe("http://proxy/tm1/sales/api/v1/");
  });

  it("refuses what isn't an address", () => {
    expect(() => Tm1Dialect.V11.serviceRoot("tm1:8010/x")).toThrow(RangeError);
    expect(() => Tm1Dialect.V11.serviceRoot("/api/v1/")).toThrow(RangeError);
    expect(() => Tm1Dialect.V11.serviceRoot("http://tm1:8010?x=1")).toThrow(RangeError);
  });

  it("lets a session be built from an address", () => {
    expect(Tm1Session.builder().address("http://tm1:8010").basicAuth("admin", "").build().serviceRoot()).toBe("http://tm1:8010/api/v1/");
    expect(() => Tm1Session.builder().address("tm1:8010/x"), "refused at once").toThrow(RangeError);
  });
});

/** The Java client's `Tm1CapabilitiesTest` (in its `metadata` package): capability checks against
 * the committed snapshot, and against an approximate older schema made from it. */
describe("Tm1Capabilities", () => {
  const RESERVE = Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve").since("11.8.34");
  const current = Tm1Capabilities.parse(SNAPSHOT, Tm1Version.parse("11.8.03500.6"));
  const older = Tm1Capabilities.parse(asOf(SNAPSHOT, "11.8.20"));

  it("finds each kind of element", () => {
    expect(current.supports(Tm1Capability.entitySet("Cubes"))).toBe(true);
    expect(current.supports(Tm1Capability.entitySet("Bananas"))).toBe(false);
    expect(current.supports(Tm1Capability.singleton("ActiveSession"))).toBe(true);
    expect(current.supports(Tm1Capability.singleton("Cubes")), "an entity set isn't a singleton").toBe(false);
    expect(current.supports(Tm1Capability.unboundOperation("ExecuteMDX"))).toBe(true);
    expect(current.supports(Tm1Capability.unboundOperation("ControlCubes")), "a function import").toBe(true);
    expect(current.supports(RESERVE)).toBe(true);
    expect(current.supports(Tm1Capability.boundOperation("ibm.tm1.api.v1.Cube", "ibm.tm1.api.v1.Reserve")), "by namespace rather than alias").toBe(true);
    expect(current.supports(Tm1Capability.boundOperation("tm1.Dimension", "tm1.CheckRules")), "bound elsewhere").toBe(false);
    expect(current.supports(Tm1Capability.property("tm1.Cube", "Rules"))).toBe(true);
    expect(current.supports(Tm1Capability.property("tm1.Cube", "Views")), "a navigation property").toBe(true);
    expect(current.supports(Tm1Capability.property("tm1.Cube", "Colour"))).toBe(false);
  });

  it("finds operations and properties of base types", () => {
    expect(current.supports(Tm1Capability.boundOperation("tm1.Folder", "tm1.Copy")), "Entry.Copy on a Folder").toBe(true);
    expect(current.supports(Tm1Capability.property("tm1.NativeView", "Name")), "View.Name on a NativeView").toBe(true);
  });

  it("reports what an older server lacks, comparing schema versions", () => {
    expect(older.schemaVersion).toBe("11.8.20");
    expect(older.supports(RESERVE)).toBe(false);
    expect(older.supports(Tm1Capability.entitySet("Cubes"))).toBe(true);

    const e = older.check(RESERVE, "http://tm1:8010/api/v1/")!;
    expect(e.missing).toBe("Cube.Reserve");
    expect(e.message).toBe(
      "Cube.Reserve isn't defined by the TM1 server at http://tm1:8010/api/v1/: it was added in schema 11.8.34, and the server reports schema 11.8.20",
    );
    expect(e.mayHaveApplied()).toBe(false);
    expect(current.check(RESERVE, "x")).toBeUndefined();
  });

  it("says what it knows when the added version isn't recorded", () => {
    expect(current.check(Tm1Capability.entitySet("Bananas"), "http://tm1:8010/api/v1/")!.message).toBe(
      "entity set Bananas isn't defined by the TM1 server at http://tm1:8010/api/v1/ (the server reports schema 11.8.35, product version 11.8.03500.6)",
    );
  });

  it("approximates an older schema without what came later, never a binding parameter", () => {
    expect(asOf(SNAPSHOT, "11.8.35")).toBe(SNAPSHOT.replace(/(<Annotation Term="Core\.SchemaVersion">\s*<String>)[^<]*(<\/String>)/, "$111.8.35$2"));
    const old = Tm1Capabilities.parse(asOf(SNAPSHOT, "11.0"));
    expect(old.endpoints.size).toBeLessThan(current.endpoints.size);
    for (const e of old.endpoints) expect(current.endpoints.has(e), e).toBe(true);
    expect(old.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve"))).toBe(false);
  });
});

/** The Java client's `Tm1RequestTest`. */
describe("Tm1Request", () => {
  it("describes a GET", () => {
    const get = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"));
    expect(get.method).toBe("GET");
    expect(get.relativeUri()).toBe("Cubes('Sales')?$select=Name");
    expect(get.response).toBe("Single");
    expect(get.isIdempotent()).toBe(true);
    expect(get.toString()).toBe("GET Cubes('Sales')?$select=Name");
  });

  it("gives actions a JSON body even without parameters", () => {
    const reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), null);
    expect(reserve.body).toEqual({});
    expect(reserve.response).toBe("None");
    expect(reserve.isIdempotent()).toBe(false);
  });

  it("describes other kinds", () => {
    expect(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none()).response).toBe("Collection");
    expect(Tm1Request.text(ODataPath.of("Dimensions").count()).response).toBe("Text");
    expect(Tm1Request.patch(ODataPath.of("Cubes").key("x"), { Rules: "" }).isIdempotent()).toBe(false);
    expect(Tm1Request.delete(ODataPath.of("Cubes").key("x")).isIdempotent()).toBe(true);
  });

  it("is immutable", () => {
    const del = Tm1Request.delete(ODataPath.of("Cubes").key("x"));
    const guarded = del.ifMatch('W/"1"').withQuery(ODataQuery.none().option("!sandbox", "s"));
    expect(del.headers).toEqual({});
    expect(guarded.headers["If-Match"]).toBe('W/"1"');
    expect(guarded.relativeUri()).toBe("Cubes('x')?!sandbox=s");
    expect(del.relativeUri()).toBe("Cubes('x')");
  });

  it("carries its own timeout through copies", () => {
    const run = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null);
    expect(run.timeout).toBeUndefined();
    const slow = run.withTimeout(2 * 3_600_000).withHeader("Prefer", "respond-async");
    expect(slow.timeout).toBe(2 * 3_600_000);
    expect(run.timeout).toBeUndefined();
    expect(() => run.withTimeout(0)).toThrow(RangeError);
  });

  it("posts and streams a collection", () => {
    const request = Tm1Request.postList(ODataPath.of("Cubes").key("c").action("tm1.CheckRules"), {});
    expect(request.method).toBe("POST");
    expect(request.response).toBe("Collection");
    expect(request.isIdempotent()).toBe(false);
  });

  it("describes raw bytes both ways", () => {
    const download = Tm1Request.bytes(ODataPath.of("Contents").key("Blobs").nav("Contents").key("a.txt"));
    expect(download.response).toBe("Bytes");
    expect(download.method).toBe("GET");
    const content = new Uint8Array([1, 2]);
    const upload = Tm1Request.put(ODataPath.of("Contents").key("Blobs"), content);
    expect(upload.method).toBe("PUT");
    expect(upload.isIdempotent()).toBe(true);
    expect(upload.body).toBe(content);
    expect(upload.response).toBe("None");
  });

  it("carries what it requires", () => {
    const capability = Tm1Capability.entitySet("Cubes");
    const plain = Tm1Request.get(ODataPath.of("Cubes"));
    expect(plain.requirement()).toBeUndefined();
    expect(plain.requires(capability).withHeader("A", "b").requirement()).toBe(capability);
  });
});

/** The Java client's `Tm1OperationTest`. */
describe("Tm1Operation", () => {
  it("a bound operation needs its binding and operation", () => {
    const activate = new Tm1Operation("Action", "tm1.Activate", "tm1.Chore", null, [], null, "10.2.2.1", "11.8.3");
    expect(activate.bound()).toBe(true);
    expect(activate.deprecated()).toBe(true);
    expect(activate.endpoint()).toBe("Action Chore/Activate");
    expect(activate.capability()).toEqual(Tm1Capability.boundOperation("tm1.Chore", "tm1.Activate").since("10.2.2.1"));
  });

  it("an unbound operation needs its import", () => {
    const mdx = new Tm1Operation(
      "Action",
      "tm1.ExecuteMDX",
      null,
      "ExecuteMDX",
      [{ name: "MDX", type: "Edm.String", nullable: false }],
      "tm1.Cellset",
      null,
      null,
    );
    expect(mdx.bound()).toBe(false);
    expect(mdx.deprecated()).toBe(false);
    expect(mdx.endpoint()).toBe("ActionImport ExecuteMDX");
    expect(mdx.capability()).toEqual(Tm1Capability.unboundOperation("ExecuteMDX"));
  });

  it("needs exactly one of a binding and an import", () => {
    expect(() => new Tm1Operation("Function", "tm1.F", null, null, [], null, null, null)).toThrow(TypeError);
    expect(() => new Tm1Operation("Function", "tm1.F", "tm1.Cube", "F", [], null, null, null)).toThrow(TypeError);
  });
});
