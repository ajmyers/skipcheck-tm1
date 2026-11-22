import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import * as model from "../src/generated/index.js";
import {
  AccessPrivilege,
  AttributesPatch,
  CAMSettings,
  ChorePatch,
  Cube,
  CubePatch,
  MessageLogEntry,
  NativeView,
  NativeViewPatch,
  ODataLiterals,
  ODataPath,
  ODataQuery,
  readAs,
  Tm1Duration,
  typeOf,
  User,
  View,
  type Tm1Model,
} from "../src/index.js";
import { Repo } from "./support/repo.js";

interface SpecType {
  name: string;
  baseType: string | null;
  open?: boolean;
  properties?: { name: string; type: string }[];
  navigationProperties?: { name: string; type: string }[];
}

const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as { types: SpecType[] };
const byQualified = new Map<string, SpecType>();
for (const t of spec.types) byQualified.set((t as unknown as { qualifiedName: string }).qualifiedName, t);
const byName = new Map(spec.types.map((t) => [t.name, t]));

function declared(t: SpecType): Map<string, string> {
  const out = new Map<string, string>();
  for (let c: SpecType | undefined = t; c; c = c.baseType ? byQualified.get(c.baseType) : undefined) {
    for (const p of [...(c.properties ?? []), ...(c.navigationProperties ?? [])]) out.set(p.name, p.type);
  }
  return out;
}

function isOpen(t: SpecType): boolean {
  for (let c: SpecType | undefined = t; c; c = c.baseType ? byQualified.get(c.baseType) : undefined) if (c.open) return true;
  return false;
}

/** Every property TM1 sent is one the model declares (or the type is open), recursively. */
function unknownProperties(typeName: string, value: unknown, path: string, out: Set<string>): void {
  if (Array.isArray(value)) {
    value.forEach((v, i) => unknownProperties(typeName, v, `${path}[${i}]`, out));
    return;
  }
  if (typeof value !== "object" || value === null) return;
  const chosen = typeOf({ $type: typeName, qualifiedName: "" } as Tm1Model<unknown>, value);
  const t = byName.get(chosen)!;
  const props = declared(t);
  for (const [k, v] of Object.entries(value)) {
    if (k.startsWith("@odata.")) continue;
    const type = props.get(k);
    if (type === undefined) {
      if (!isOpen(t)) out.add(`${path}/${k}`);
      continue;
    }
    const nested = byQualified.get(type);
    if (nested) unknownProperties(nested.name, v, `${path}/${k}`, out);
  }
}

/** The payloads the Java client captured from a real TM1 read into the model and back, as its
 * PayloadRoundTripTest: what reading changes (enum fallbacks) would show as a difference. */
describe("payloads", () => {
  const payloads: [string, Tm1Model<unknown>][] = [
    ["server", model.Server],
    ["configuration", model.Configuration],
    ["active-session", model.Session],
    ["users", model.User],
    ["groups", model.Group],
    ["dimension", model.Dimension],
    ["members", model.Member],
    ["cube", model.Cube],
    ["views", model.View],
    ["process", model.Process],
    ["chore", model.Chore],
    ["threads", model.Thread],
    ["cellset", model.Cellset],
  ];

  it.each(payloads)("reads %s as TM1 sent it", (payload, type) => {
    const text = readFileSync(join(Repo.payloads, payload + ".json"), "utf8");
    const doc = JSON.parse(text) as Record<string, unknown>;
    const items = Array.isArray(doc["value"]) ? (doc["value"] as unknown[]) : [doc];
    expect(items.length).toBeGreaterThan(0);
    const original = JSON.parse(text) as Record<string, unknown>;
    const originals = Array.isArray(original["value"]) ? (original["value"] as unknown[]) : [original];
    const unknown = new Set<string>();
    items.forEach((item, i) => {
      const read = readAs(type, item);
      expect(JSON.parse(JSON.stringify(read))).toEqual(originals[i]);
      unknownProperties(type.$type, read, `[${i}]`, unknown);
    });
    expect([...unknown]).toEqual([]);
  });

  it("chooses a subtype by @odata.type", () => {
    const views = (JSON.parse(readFileSync(join(Repo.payloads, "views.json"), "utf8")) as { value: View[] }).value.map((v) => readAs(View, v));
    for (const v of views) expect(["NativeView", "MDXView"]).toContain(typeOf(View, v));
    expect(typeOf(View, readAs(View, { "@odata.type": "#tm1.MDXView", Name: "a" }))).toBe("MDXView");
    const unknown = readAs(View, { "@odata.type": "#tm1.FutureView", Name: "a" });
    expect([typeOf(View, unknown), unknown.Name]).toEqual(["View", "a"]);
    // A subtype's own properties are read as its: NativeView's Columns hold ViewAxisSelections
    const native = readAs(View, { "@odata.type": "#ibm.tm1.api.v1.NativeView", Columns: [{ Subset: { Name: "s", Elements: [{ Type: "Future" }] } }] }) as NativeView;
    expect(native.Columns?.[0]?.Subset?.Elements?.[0]?.Type).toBe("Unknown");
  });

  it("reads an unknown enum value as the fallback", () => {
    expect(readAs(User, { Type: "SomethingNew" }).Type).toBe("Unknown");
    expect(readAs(User, { Type: "Admin" }).Type).toBe("Admin");
    expect(ODataLiterals.of(AccessPrivilege.literal("Read"))).toBe("ibm.tm1.api.v1.AccessPrivilege'Read'");
    expect(() => AccessPrivilege.literal("Unknown")).toThrow();
  });

  it("reads departures and lenient dates from the overlay", () => {
    expect(readAs(CAMSettings, { ClientPingCAMPassport: "PT15M" }).ClientPingCAMPassport).toBe("PT15M");
    expect(readAs(CAMSettings, { ClientPingCAMPassport: 900 }).ClientPingCAMPassport).toBe(900);
    expect(readAs(MessageLogEntry, { TimeStamp: "0000-00-00T00:00Z" }).TimeStamp).toBeNull();
    expect(readAs(MessageLogEntry, { TimeStamp: "2024-01-02T03:04:05Z" }).TimeStamp).toBe("2024-01-02T03:04:05Z");
  });

  it("reads durations as ISO 8601, bare seconds too", () => {
    expect(readAs(model.Chore, { Frequency: 3600 }).Frequency).toBe("PT1H");
    expect(readAs(model.Chore, { Frequency: "P1DT00H00M00S" }).Frequency).toBe("P1DT00H00M00S");
    expect(Tm1Duration.toMillis("P1DT00H00M00S")).toBe(86_400_000);
    expect(Tm1Duration.ofMillis(93_600_000)).toBe("P1DT2H");
    expect(Tm1Duration.ofMillis(0)).toBe("PT0S");
  });

  it("writes patches as set", () => {
    const dimensions = ODataPath.of("Dimensions");
    const patch = CubePatch.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).rules(null).build();
    expect(JSON.stringify(patch)).toBe('{"Name":"Sales","Dimensions@odata.bind":["Dimensions(\'Region\')","Dimensions(\'Month\')"],"Rules":null}');
    expect(JSON.stringify(NativeViewPatch.builder().name("v").suppressEmptyRows(true).build())).toBe('{"@odata.type":"#ibm.tm1.api.v1.NativeView","Name":"v","SuppressEmptyRows":true}');
    expect(JSON.stringify(ChorePatch.builder().frequency("P1D").build())).toBe('{"Frequency":"P1D"}');
    expect(JSON.stringify(AttributesPatch.builder().property("Caption", "x").build())).toBe('{"Caption":"x"}');
    expect(CubePatch.builder().name("a").build().equals(CubePatch.builder().name("a").build())).toBe(true);
  });

  it("has field constants for queries", () => {
    expect(Cube.Fields.Name.path).toBe("Name");
    expect(Cube.Fields.DrillthroughRules.path).toBe("DrillthroughRules");
    expect(NativeView.Fields.Name.path).toBe("Name");
    expect(ODataQuery.none().select(Cube.Fields.Name).filter(Cube.Fields.Name.eq("x")).toString()).toBe("$select=Name&$filter=(Name eq 'x')");
  });
});
