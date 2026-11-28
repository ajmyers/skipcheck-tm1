// SPDX-License-Identifier: Apache-2.0

// Emits model.ts: an enum per CSDL enum type (a string union and a const of its members), an
// interface per entity and complex type with TM1's own property names, and a model constant of the
// same name with its `Fields` (`Cube.Fields.Name`, the Java model's `Cube.NAME`); and schema.ts, what
// the reader needs: enums' members, structured and duration properties, hierarchies by `@odata.type`.

import { HEADER, jsdoc, Names, type Spec, type SpecProperty, type SpecType } from "./spec.js";

const EDM: Record<string, string> = {
  "Edm.String": "string",
  "Edm.Boolean": "boolean",
  "Edm.Byte": "number",
  "Edm.SByte": "number",
  "Edm.Int16": "number",
  "Edm.Int32": "number",
  "Edm.Int64": "number",
  "Edm.Single": "number",
  "Edm.Double": "number",
  "Edm.Decimal": "number",
  "Edm.Date": "string",
  "Edm.TimeOfDay": "string",
  "Edm.DateTimeOffset": "string",
  "Edm.Duration": "string",
  "Edm.Guid": "string",
  "Edm.PrimitiveType": "unknown",
  "Edm.Untyped": "unknown",
};

/** The TypeScript type of a CSDL type; undefined for streams. */
export function element(spec: Spec, typeName: string): string | undefined {
  if (typeName === "Edm.Stream") return undefined;
  if (EDM[typeName]) return EDM[typeName];
  const e = spec.enumByName.get(typeName);
  if (e) return e.flags ? "string" : e.name;
  const t = spec.typeByName.get(typeName);
  if (t) return t.name;
  return "unknown";
}

export function propertyType(spec: Spec, p: SpecProperty): string | undefined {
  if (p.departure) return "unknown";
  const el = element(spec, p.type);
  if (el === undefined) return undefined;
  return p.collection ? `${el}[]` : el;
}

function all(t: SpecType): SpecProperty[] {
  return [...(t.properties ?? []), ...(t.navigationProperties ?? [])];
}

export function emitModel(spec: Spec): string {
  let out = HEADER;
  out += 'import { ODataLiterals, ODataProperty } from "../odata/index.js";\n';
  out += 'import { model, type Tm1Model } from "../model.js";\n';
  out += 'import type { Tm1Departure } from "../version/index.js";\n';
  for (const e of spec.enums) {
    const members = e.members.map((m) => m.name);
    const fallback = members.includes("Unknown") ? "Unrecognized" : "Unknown";
    out += "\n";
    out += jsdoc(
      "",
      (e.description ?? `The \`${e.name}\` enum type of the TM1 REST API.`) +
        ` A value this client doesn't know reads as \`"${fallback}"\`, which is never sent.` +
        (e.flags ? ' Its values combine, as in `"Unfed, Fed"`: a property of this type reads as a string.' : ""),
      e,
    );
    out += `export type ${e.name} = ${[...members, fallback].map((m) => JSON.stringify(m)).join(" | ")};\n`;
    out += `export const ${e.name} = {\n`;
    const taken = new Set<string>(["literal", "qualifiedName"]);
    for (const m of e.members) {
      out += jsdoc("  ", m.description, m);
      out += `  ${Names.unique(Names.member(m.name), taken)}: ${JSON.stringify(m.name)},\n`;
    }
    out += `  /** A value this client doesn't know. It's never sent. */\n  ${Names.unique(fallback, taken)}: ${JSON.stringify(fallback)},\n`;
    out += `  qualifiedName: ${JSON.stringify(e.qualifiedName)},\n`;
    out += `  /** The member as an OData literal, for filters and parameters: \`${e.qualifiedName}'${e.members[0]?.name ?? "X"}'\`. */\n`;
    out += `  literal(value: ${e.name}): ODataLiterals.Raw {\n`;
    out += `    if (value === ${JSON.stringify(fallback)}) throw new Error(${JSON.stringify(`${e.name}.${fallback} stands for a value this client doesn't know, and isn't sent`)});\n`;
    out += `    return new ODataLiterals.Raw(ODataLiterals.enumMember(${JSON.stringify(e.qualifiedName)}, value));\n  },\n} as const;\n`;
  }
  for (const t of spec.types) out += emitType(spec, t);
  out += emitDepartures(spec);
  return out;
}

/** How a property departs from `$metadata`, as Java's `@Tm1Departure`, or undefined. */
export function departure(p: SpecProperty): { kind: string; observedIn: string[]; note: string } | undefined {
  if (p.undeclared) return { kind: "UNDECLARED", observedIn: p.undeclared.observedIn ?? [], note: p.description ?? "" };
  if (p.departure) return { kind: "TYPE", observedIn: [], note: p.departure.note ?? "" };
  if (p.lenientDate) return { kind: "LENIENT_DATE", observedIn: [], note: p.lenientDate.note ?? "" };
  return undefined;
}

/** `DEPARTURES`: every property that departs from `$metadata`, in the spec's order. TypeScript's
 * types don't survive to run time, so this is what Java's `@Tm1Departure` is here. */
function emitDepartures(spec: Spec): string {
  let out = "\n/** Every property of the model that departs from TM1's `$metadata`, as the Java model's `@Tm1Departure`. */\n";
  out += "export const DEPARTURES: readonly Tm1Departure[] = [\n";
  for (const t of spec.types) {
    for (const p of t.properties ?? []) {
      const d = departure(p);
      if (!d) continue;
      out += `  { type: ${JSON.stringify(t.name)}, property: ${JSON.stringify(p.name)}, kind: ${JSON.stringify(d.kind)}, observedIn: ${JSON.stringify(d.observedIn)}, note: ${JSON.stringify(d.note.replaceAll("`", ""))} },\n`;
    }
  }
  return out + "];\n";
}

const BIGINT_IDS = new Set(["Session.ID", "Thread.ID", "Thread.SessionID"]);

function emitType(spec: Spec, t: SpecType): string {
  const base = spec.base(t);
  const root = spec.root(t);
  const entity = t.kind === "entity";
  let out = "\n";
  let head = t.description ?? `The \`${t.name}\` ${entity ? "entity" : "complex"} type of the TM1 REST API.`;
  if (base) head += ` A kind of {@link ${root.name}}, read by \`@odata.type\` \`#${t.qualifiedName}\`.`;
  if (spec.derived(t).length)
    head += ` Reads as one of ${spec
      .derived(t)
      .map((d) => `{@link ${d.name}}`)
      .join(", ")} by \`@odata.type\`, or as this type itself.`;
  out += jsdoc("", head + " Every property is optional: `$select` can leave any out, and navigation properties are there only when expanded.", t);
  out += `export interface ${t.name}${base ? ` extends ${base.name}` : ""} {\n`;
  if (entity && !base) out += `  /** The entity's ETag, for \`If-Match\`. */\n  "@odata.etag"?: string;\n`;
  if (!base && spec.derived(t).length)
    out += `  /** The type TM1 says this is, such as \`#${spec.derived(t)[0]!.qualifiedName}\`. */\n  "@odata.type"?: string;\n`;
  for (const p of t.properties ?? []) {
    let type = propertyType(spec, p);
    if (type === undefined) continue;
    if (BIGINT_IDS.has(`${t.name}.${p.name}`)) type = "number | bigint";
    const doc = [p.description, p.departure?.note, p.lenientDate?.note].filter(Boolean).join(" ");
    out += jsdoc("  ", doc, p);
    out += `  ${Names.key(p.name)}?: ${type}${p.nullable === false ? "" : " | null"};\n`;
  }
  for (const n of t.navigationProperties ?? []) {
    const type = propertyType(spec, n);
    if (type === undefined) continue;
    out += jsdoc("  ", (n.description ?? "") + " (When expanded.)", n);
    out += `  ${Names.key(n.name)}?: ${type}${n.nullable === false ? "" : " | null"};\n`;
  }
  if (t.open && !(base && base.open))
    out += "  /** The properties `$metadata` doesn't declare, which an open type can have, such as attribute values. */\n  [property: string]: unknown;\n";
  out += "}\n";
  // Fields, as the Java model's Cube.NAME: its own and its root's
  const fields: string[] = [];
  const seen = new Set<string>();
  for (const level of spec.chain(t)) {
    for (const p of all(level)) {
      if (p.type === "Edm.Stream" || seen.has(p.name)) continue;
      seen.add(p.name);
      fields.push(`  ${Names.key(p.name)}: ODataProperty.of(${JSON.stringify(p.name)}),\n`);
    }
  }
  out += `/** \`${t.name}\`'s properties, for queries and filters (\`${t.name}.Fields.Name\`), and what reads it. */\n`;
  out += `export const ${t.name}: Tm1Model<${t.name}> & { readonly Fields: {\n${fields.map((f) => f.replace(/: ODataProperty.of\(.*\),\n$/, ": ODataProperty;\n")).join("")}} } = model(${JSON.stringify(t.name)}, ${JSON.stringify(t.qualifiedName)}, {\n${fields.join("")}});\n`;
  return out;
}

/** schema.ts: what reading needs, by type name. A property is listed when reading changes it: a
 * structured type ("t:Name"), an enum ("e:Name"), a duration ("d"), a date-time ("dt") or a
 * lenient date ("l"). */
export function emitSchema(spec: Spec): string {
  let out = HEADER;
  out += 'import type { Tm1Schema } from "../json.js";\n\n';
  out += "export const schema: Tm1Schema = {\n  enums: {\n";
  for (const e of spec.enums) {
    if (e.flags) continue;
    const members = e.members.map((m) => m.name);
    const fallback = members.includes("Unknown") ? "Unrecognized" : "Unknown";
    out += `    ${e.name}: { qualifiedName: ${JSON.stringify(e.qualifiedName)}, members: ${JSON.stringify(members)}, fallback: ${JSON.stringify(fallback)} },\n`;
  }
  out += "  },\n  types: {\n";
  for (const t of spec.types) {
    const props: string[] = [];
    for (const p of all(t)) {
      if (p.departure) continue;
      let kind: string | undefined;
      if (p.lenientDate) kind = "l";
      else if (p.type === "Edm.Duration") kind = "d";
      else if (p.type === "Edm.DateTimeOffset") kind = "dt";
      else if (spec.typeByName.has(p.type)) kind = "t:" + spec.typeByName.get(p.type)!.name;
      else if (spec.enumByName.has(p.type) && !spec.enumByName.get(p.type)!.flags) kind = "e:" + spec.enumByName.get(p.type)!.name;
      if (kind) props.push(`${Names.key(p.name)}: ${JSON.stringify(kind)}`);
    }
    const base = spec.base(t);
    const parts = [`qualifiedName: ${JSON.stringify(t.qualifiedName)}`];
    if (base) parts.push(`base: ${JSON.stringify(base.name)}`, `typeId: ${JSON.stringify("#" + t.qualifiedName)}`);
    if (spec.derived(t).length) {
      const derived: string[] = [];
      for (const d of [...spec.derived(t), t]) {
        derived.push(
          `${JSON.stringify(d.qualifiedName)}: ${JSON.stringify(d.name)}`,
          `${JSON.stringify(spec.aliasName(d.qualifiedName))}: ${JSON.stringify(d.name)}`,
        );
      }
      parts.push(`derived: { ${derived.join(", ")} }`);
    }
    parts.push(`properties: { ${props.join(", ")} }`);
    out += `    ${t.name}: { ${parts.join(", ")} },\n`;
  }
  out += "  },\n};\n";
  return out;
}
