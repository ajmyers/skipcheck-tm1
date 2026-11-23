// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  allOperations,
  ODataPath,
  ODataQuery,
  Tm1Capabilities,
  Tm1Capability,
  Tm1Dialect,
  Tm1Operation,
  Tm1Operations,
  Tm1ProtocolException,
  Tm1Request,
  Tm1Session,
  Tm1Version,
} from "../../src/index.js";
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

  it.each(["", "11.", ".8", "11..8", "v11.8", "11.8-beta", "99999999999"])("rejects non-versions: %j", (text) => {
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

  /** A CSDL document whose one schema holds `body`. */
  const csdl = (body: string): string =>
    `<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>` +
    `<Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">${body}</Schema></edmx:DataServices></edmx:Edmx>`;

  it("refuses doctypes so entities can't be expanded", () => {
    const xxe = `<?xml version="1.0"?>
<!DOCTYPE edmx:Edmx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">&x;</edmx:Edmx>
`;
    expect(() => Tm1Capabilities.parse(xxe)).toThrow(Tm1ProtocolException);
    expect(() => Tm1Capabilities.parse(xxe)).toThrow(/DOCTYPE/);
    // Only a declaration: the word in a comment or in text is nothing to refuse
    expect(Tm1Capabilities.parse(csdl('<!-- <!DOCTYPE x> --><EntityType Name="Cube"/>')).schemaVersion).toBeUndefined();
  });

  it("reports an operation it can't model as a parse error", () => {
    // Bound to nothing: an operation is bound to its first parameter
    const boundToNothing = csdl('<Action Name="Reserve" IsBound="true"/>');
    expect(() => Tm1Capabilities.parse(boundToNothing)).toThrow(Tm1ProtocolException);
    expect(() => Tm1Capabilities.parse(boundToNothing)).toThrow(/Reserve/);
  });

  it.each([
    ["a binding parameter with no type", csdl('<Action Name="Reserve" IsBound="true"><Parameter Name="Cube"/></Action>')],
    ["a character reference that is no character", csdl('<EntityType Name="Cu&#x110000;be"/>')],
    ["text that isn't XML", "{}"],
    ["XML that isn't CSDL", "<html>Not metadata</html>"],
    ["nothing", ""],
    ["an element left open", '<edmx:Edmx Version="4.0"><edmx:DataServices>'],
  ])("reports %s as a parse error, never a raw exception", (_, metadata) => {
    expect(() => Tm1Capabilities.parse(metadata)).toThrow(Tm1ProtocolException);
  });

  // The Java client's ApproximateSchemasTest, on this file's asOf: the approximate older servers
  // the tests above (and Java's) are checked against are what they claim to be

  it("the snapshot's own version changes nothing", () => {
    expect(asOf(SNAPSHOT, "11.8.35")).toBe(SNAPSHOT.replace(/(<Annotation Term="Core\.SchemaVersion">\s*<String>)[^<]*(<\/String>)/, "$111.8.35$2"));
    expect(Tm1Capabilities.parse(asOf(SNAPSHOT, "11.8.35")).endpoints).toEqual(current.endpoints);
  });

  it("an older server lacks what came later", () => {
    expect(older.schemaVersion).toBe("11.8.20");
    // Cube.Reserve and Release arrived in 11.8.34; Load in 11.6.0
    expect(older.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Load"))).toBe(true);
    expect(older.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.CheckRules"))).toBe(true);
    expect(older.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve"))).toBe(false);
    expect(older.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Release"))).toBe(false);
    // Encryption-at-rest configuration arrived in 11.8.35; data file encryption in 11.3.0
    expect(older.supports(Tm1Capability.unboundOperation("DecryptDataFile"))).toBe(true);
    expect(older.supports(Tm1Capability.unboundOperation("ConfigureEncryptionAtRest"))).toBe(false);
  });

  it("an old server lacks whole areas", () => {
    const v10 = Tm1Capabilities.parse(asOf(SNAPSHOT, "10.2.2.0"));
    // Git support arrived in 11.5.0
    expect(v10.supports(Tm1Capability.entitySet("Cubes"))).toBe(true);
    expect(v10.supports(Tm1Capability.entitySet("Dimensions"))).toBe(true);
    expect(v10.supports(Tm1Capability.entitySet("GitPlans"))).toBe(false);
    expect(v10.endpoints.size).toBeLessThan(current.endpoints.size);
    for (const e of v10.endpoints) expect(current.endpoints.has(e), e).toBe(true);
  });

  it("never drops a binding parameter", () => {
    const v10 = asOf(SNAPSHOT, "10.2.2.0");
    const bound = [...v10.matchAll(/<(Action|Function) Name="(\w+)" IsBound="true">([\s\S]*?)<\/\1>/g)];
    expect(bound.length).toBeGreaterThan(0);
    for (const [, , name, body] of bound) expect(body, name).toMatch(/<Parameter Name="\w+" Type="[^"]+"/);
    // And so it parses: a bound operation without one is refused (see "reports an operation it
    // can't model as a parse error")
    expect(() => Tm1Capabilities.parse(v10)).not.toThrow();
  });
});

/** The Java client's `OperationCatalogTest`: the generated operation catalog matches the inventory
 * the coverage gate counts, and the snapshot. */
describe("Tm1Operations", () => {
  const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as { endpoints: { id: string }[] };

  it("has one descriptor per operation endpoint of the coverage gate", () => {
    const operations = spec.endpoints.map((e) => e.id).filter((e) => /^(Action|Function|ActionImport|FunctionImport) /.test(e));
    const catalog = allOperations.map((op) => op.endpoint());
    expect(new Set(catalog).size, "no duplicates").toBe(catalog.length);
    expect([...catalog].sort()).toEqual([...operations].sort());
    expect(catalog).toHaveLength(105);
  });

  it("every capability is one the snapshot has", () => {
    const snapshot = Tm1Capabilities.parse(SNAPSHOT);
    for (const op of allOperations) expect(snapshot.supports(op.capability()), op.endpoint()).toBe(true);
  });

  it("carries revisions, parameters and return types", () => {
    expect(Tm1Operations.ChoreActivate.deprecatedIn).toBe("11.8.3");
    const mdx = Tm1Operations.ExecuteMdx;
    expect(mdx.kind).toBe("Action");
    expect(mdx.importName).toBe("ExecuteMDX");
    expect(mdx.parameters).toEqual([{ name: "MDX", type: "Edm.String", nullable: false }]);
    expect(mdx.returnType).toBe("tm1.Cellset");
    expect(mdx.addedIn).toBe("10.2.2.1");
    expect(allOperations.filter((op) => op.deprecated())).not.toHaveLength(0);
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
