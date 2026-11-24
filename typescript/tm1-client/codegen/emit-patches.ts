// SPDX-License-Identifier: Apache-2.0

// Emits patches.ts: a patch per type, as the Java model's `CubePatch`:
// `CubePatch.builder().rules(rules).build()`, and a create per probed type, as `CubeCreate`. Only the properties
// set are sent, an explicit `null` included; a subtype's patch sends its `@odata.type` first.

import { element } from "./emit-model.js";
import { HEADER, Names, type Spec, type SpecType } from "./spec.js";

function patchType(spec: Spec, typeName: string, collection: boolean): string | undefined {
  const t = spec.typeByName.get(typeName);
  let el: string | undefined;
  // A nested entity is sent as its create where it has one: a deep insert creates it
  if (t) el = t.name + (creatable(spec, t) ? "Create" : "Patch");
  else el = element(spec, typeName);
  if (el === undefined) return undefined;
  if (!t && spec.enumByName.has(typeName) && !spec.enumByName.get(typeName)!.flags) el = "model." + el;
  return collection ? `readonly ${el}[] | null` : `${el} | null`;
}

export function emitPatches(spec: Spec): string {
  let out = HEADER;
  out += 'import { Tm1Patch, Tm1PatchBuilder } from "../patch.js";\n';
  out += 'import type { ODataPath } from "../odata/index.js";\n';
  out += 'import type * as model from "./model.js";\n';
  // A class extends its base's, so bases come first
  const done = new Set<string>();
  const visit = (t: SpecType): void => {
    if (done.has(t.name)) return;
    const base = spec.base(t);
    if (base) visit(base);
    done.add(t.name);
    out += emitPatch(spec, t);
  };
  for (const t of spec.types) visit(t);
  // Creates, for the types the probe has writes for, and the roots their creates extend
  const created = new Set<string>();
  const visitCreate = (t: SpecType): void => {
    if (created.has(t.name) || !creatable(spec, t)) return;
    const base = spec.base(t);
    if (base) visitCreate(base);
    created.add(t.name);
    out += emitPatch(spec, t, true);
  };
  for (const t of spec.types) visitCreate(t);
  return out;
}

const WRITING = new Set(["applied", "writeOnly", "transformed", "untested"]);

function writes(outcome: string | undefined): boolean {
  return outcome === undefined || WRITING.has(outcome);
}

/** Whether a POST of `t` takes a property, by the probe's writes (yes, for a type it has none for). */
export function creates(t: SpecType, prop: string, spec?: Spec): boolean {
  // The root of a hierarchy without writes of its own offers what every probed subtype takes, so
  // each subtype's create offers at least what its root's does
  if (!t.write && spec)
    return spec
      .derived(t)
      .filter((d) => !!d.write)
      .every((d) => creates(d, prop));
  return writes(t.write?.create.properties[prop]);
}

/** Whether `builder(read)` copies a property into a patch of `t`: a PATCH applies it, and it isn't a
 * collection a PATCH appends to (copying one would add its members again), ignores or rejects. A
 * collection a PATCH replaces is copied: the read holds all of it. As the Java generator. */
export function patches(t: SpecType, prop: string): boolean {
  const collection = t.write?.collections?.[prop];
  return writes(t.write?.patch.properties[prop]) && !["appends", "ignored", "rejected"].includes(collection ?? "");
}

function writeOffersInPatch(write: NonNullable<SpecType["write"]>, prop: string): boolean {
  if (prop === "Name") return write.patch.rename === undefined || write.patch.rename === null || write.patch.rename === "applied";
  const outcome = write.patch.properties[prop];
  const collection = write.collections?.[prop];
  return outcome !== "rejected" && outcome !== "ignored" && collection !== "rejected" && collection !== "ignored";
}

/**
 * Whether a patch of `t` offers a property: not if a PATCH rejects or ignores it, by the probe, nor
 * `Name` where a PATCH doesn't rename; for the root of a hierarchy without writes of its own, if
 * every probed subtype's patch offers it. As the Java generator.
 */
export function offersInPatch(spec: Spec, t: SpecType, prop: string): boolean {
  if (t.write) return writeOffersInPatch(t.write, prop);
  return spec
    .derived(t)
    .filter((d) => !!d.write)
    .every((d) => writeOffersInPatch(d.write!, prop));
}

/** Whether a type gets a create: the probe has writes for it, or a subtype it's the root of does. */
export function creatable(spec: Spec, t: SpecType): boolean {
  return t.kind === "entity" && (!!t.write || spec.derived(t).some((d) => !!d.write));
}

function emitPatch(spec: Spec, t: SpecType, create = false): string {
  const name = t.name;
  const suffix = create ? "Create" : "Patch";
  const patch = name + suffix;
  const builder = patch + "Builder";
  const base = spec.base(t);
  const typeId = base ? JSON.stringify("#" + t.qualifiedName) : "null";
  const ticks = "`@odata.type`";
  let out = create
    ? `\n/** A create of {@link model.${name}}, for a POST: only the properties set are sent, and only those TM1 applies in a create are offered${t.write ? ", as the probe found" : ""}. Its build() checks what a create needs. */\n`
    : `\n/** A write of {@link model.${name}}: only the properties set are sent. */\n`;
  out += `export class ${patch} extends ${base ? base.name + suffix : "Tm1Patch"} {\n`;
  if (spec.derived(t).length) {
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}. */\n`;
    out += `  static ${base ? "override " : ""}builder(): ${builder} {\n    return new ${builder}();\n  }\n}\n\n`;
  } else {
    const copied: string[] = [];
    for (const level of spec.chain(t))
      for (const p of level.properties ?? [])
        if (p.type !== "Edm.Stream" && !copied.includes(p.name) && (create ? creates(t, p.name, spec) : patches(t, p.name) && offersInPatch(spec, t, p.name)))
          copied.push(p.name);
    const declared = spec.chain(t).some((l) => l.open)
      ? JSON.stringify(spec.chain(t).flatMap((l) => [...(l.properties ?? []), ...(l.navigationProperties ?? [])].map((p) => p.name)))
      : "null";
    const what = create ? "a POST applies, to recreate it" : "a PATCH applies, leaving out collections a PATCH appends to";
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}; or, given an entity read from TM1, one holding what it holds that ${what}. Navigation properties aren't copied, nor nulls. */\n`;
    out += `  static ${base ? "override " : ""}builder(read?: model.${name}): ${builder} {\n`;
    out += `    const b = new ${builder}();\n    return read ? b.copy(read, ${JSON.stringify(copied)}, ${declared}) : b;\n  }\n}\n\n`;
  }
  out += `/** Builds a {@link ${patch}}; each setter replaces what an earlier call set. */\n`;
  out += `export class ${builder} extends Tm1PatchBuilder<${patch}> {\n`;
  out += `  constructor() {\n    super((values) => new ${patch}(values), ${typeId});\n  }\n`;
  const taken = new Set(["build", "property", "set", "bind", "bindAll", "constructor"]);
  const required = create ? (t.write?.create.required ?? []) : [];
  if (required.length) {
    out += `\n  /** The create, with what's been set so far. @throws Error if it lacks what a create needs. */\n`;
    out += `  override build(): ${patch} {\n`;
    for (const r of required) {
      const bare = r.replace("@odata.bind", "");
      out += `    if (!this.has(${JSON.stringify(bare)})) throw new Error(${JSON.stringify(`A ${name} create needs ${bare}: TM1 refuses one without it`)});\n`;
    }
    out += "    return super.build();\n  }\n";
  }
  for (const level of spec.chain(t)) {
    for (const p of level.properties ?? []) {
      const type = p.departure ? "unknown" : patchType(spec, p.type, !!p.collection);
      if (type === undefined || (create ? !creates(t, p.name, spec) : !offersInPatch(spec, t, p.name))) continue;
      const method = Names.unique(Names.method(p.name), taken);
      out += `\n  ${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(p.name)}, value);\n  }\n`;
    }
    for (const n of level.navigationProperties ?? []) {
      const type = patchType(spec, n.type, !!n.collection);
      // A navigation a PATCH ignores or rejects (a cube's Dimensions) isn't offered either
      if (type === undefined || (!create && !offersInPatch(spec, t, n.name))) continue;
      const method = Names.unique(Names.method(n.name), taken);
      out += `\n  /** Sets \`${n.name}\` (nested, for a deep insert). */\n`;
      out += `  ${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(n.name)}, value);\n  }\n`;
      if (spec.isEntity(n.type)) {
        const bind = Names.unique("bind" + Names.member(n.name), taken);
        if (n.collection) {
          out += `\n  /** Binds \`${n.name}\` to existing entities (\`${n.name}@odata.bind\`). */\n`;
          out += `  ${bind}(...targets: ODataPath[]): this {\n    return this.bindAll(${JSON.stringify(n.name)}, targets);\n  }\n`;
        } else {
          out += `\n  /** Binds \`${n.name}\` to an existing entity (\`${n.name}@odata.bind\`). */\n`;
          out += `  ${bind}(target: ODataPath): this {\n    return this.bind(${JSON.stringify(n.name)}, target);\n  }\n`;
        }
      }
    }
  }
  if (spec.chain(t).some((l) => l.open)) {
    out += "\n  /** Sets a property `$metadata` doesn't declare, which this open type can have, such as an attribute's value. */\n";
    out += "  property(name: string, value: unknown): this {\n    return this.set(name, value);\n  }\n";
  }
  out += "}\n";
  return out;
}
