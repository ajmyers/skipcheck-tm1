// SPDX-License-Identifier: Apache-2.0

// Emits patches.ts: the write types the spec's offers list, as the Java model's: a patch, as
// `CubePatch.builder().rules(rules).build()`, and a create, as `CubeCreate`, each offering only the
// setters the probe verified or an exception kept (#338). Only the properties set are sent, an
// explicit `null` included; a subtype's write sends its `@odata.type` first.

import { element } from "./emit-model.js";
import { HEADER, Names, type Spec, type SpecProperty, type SpecType } from "./spec.js";

const BIND = "@odata.bind";

/** The type a setter takes: a complex type's patch; an entity's create where it has one (a nested
 * entity is new), else its patch. As the Java generator. */
function patchType(spec: Spec, typeName: string, collection: boolean): string | undefined {
  const t = spec.typeByName.get(typeName);
  let el: string | undefined;
  if (t) el = t.name + (creatable(spec, t) ? "Create" : "Patch");
  else el = element(spec, typeName);
  if (el === undefined) return undefined;
  if (!t && spec.enumByName.has(typeName) && !spec.enumByName.get(typeName)!.flags) el = "model." + el;
  return collection ? `readonly ${el}[] | null` : `${el} | null`;
}

export function emitPatches(spec: Spec): string {
  let out = HEADER;
  out += 'import { Tm1Patch, Tm1PatchBuilder } from "../patch.js";\n';
  out += 'import { typeOf } from "../json.js";\n';
  out += 'import { ODataPath } from "../odata/index.js";\n';
  out += 'import * as model from "./model.js";\n';
  out += READ_BINDINGS;
  // A class extends its base's, so bases come first
  const done = new Set<string>();
  const visit = (t: SpecType): void => {
    if (done.has(t.name) || !patchable(spec, t)) return;
    const base = spec.base(t);
    if (base) visit(base);
    done.add(t.name);
    out += emitPatch(spec, t);
  };
  for (const t of spec.types) visit(t);
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

/** The Java model's `ReadBindings`: the paths a write's `builder(read)` binds a read's navigations
 * to, private to the module. */
const READ_BINDINGS = `
// The paths a write's builder(read) binds a read's navigations to: null where one can't be made,
// as the Java model's ReadBindings

/** An entity in a set, by its key: \`Dimensions('Region')\`. */
function byKey(set: string, key: unknown): ODataPath | null {
  return key === null || key === undefined ? null : ODataPath.of(set).key(key);
}

/** Entities in a set, by their keys: null if any key is missing. */
function byKeys(set: string, keys: readonly unknown[]): ODataPath[] | null {
  const out: ODataPath[] = [];
  for (const key of keys) {
    const path = byKey(set, key);
    if (path === null) return null;
    out.push(path);
  }
  return out;
}

/** An element or member, by its \`UniqueName\`: \`[d].[h].[e]\`, or \`[d].[e]\` in the dimension's own
 * hierarchy, with \`]]\` for \`]\`. Null for any other shape. */
function inHierarchy(collection: string, uniqueName: unknown): ODataPath | null {
  if (typeof uniqueName !== "string") return null;
  const parts: string[] = [];
  let i = 0;
  while (i < uniqueName.length) {
    if (uniqueName[i] !== "[") return null;
    let part = "";
    let j = i + 1;
    for (;;) {
      if (j >= uniqueName.length) return null;
      const c = uniqueName[j]!;
      if (c === "]") {
        if (uniqueName[j + 1] === "]") {
          part += "]";
          j += 2;
          continue;
        }
        break;
      }
      part += c;
      j++;
    }
    parts.push(part);
    i = j + 1;
    if (i < uniqueName.length) {
      if (uniqueName[i] !== ".") return null;
      i++;
      if (i === uniqueName.length) return null;
    }
  }
  if (parts.length === 2) parts.splice(1, 0, parts[0]!);
  if (parts.length !== 3) return null;
  return ODataPath.of("Dimensions").key(parts[0]).nav("Hierarchies").key(parts[1]).nav(collection).key(parts[2]);
}

/** Elements or members, by their \`UniqueName\`s: null if any can't be made. */
function allInHierarchy(collection: string, uniqueNames: readonly unknown[]): ODataPath[] | null {
  const out: ODataPath[] = [];
  for (const name of uniqueNames) {
    const path = inHierarchy(collection, name);
    if (path === null) return null;
    out.push(path);
  }
  return out;
}
`;

/** Whether a type gets a patch: the offers list one. */
export function patchable(spec: Spec, t: SpecType): boolean {
  return spec.offered(t.name + "Patch") !== undefined;
}

/** Whether a type gets a create: it's an entity, and the offers list one. */
export function creatable(spec: Spec, t: SpecType): boolean {
  return t.kind === "entity" && spec.offered(t.name + "Create") !== undefined;
}

const WRITING = new Set(["applied", "writeOnly", "transformed", "untested"]);

/** Whether `builder(read)` copies a property into a patch of `t`: a PATCH applies it, and it isn't a
 * collection a PATCH appends to (copying one would add its members again), ignores or rejects. A
 * collection a PATCH replaces is copied: the read holds all of it. As the Java generator. */
export function patches(t: SpecType, prop: string): boolean {
  const outcome = t.write?.patch.properties[prop];
  const collection = t.write?.collections?.[prop];
  return (outcome === undefined || WRITING.has(outcome)) && !["appends", "ignored", "rejected"].includes(collection ?? "");
}

/** How `builder(read)` binds a navigation the read holds, or undefined where the read can't tell the
 * targets' paths: by key for a target in an entity set with a single key, by `UniqueName` for an
 * element or a member; not a subset (public or private) or a view, whose cube the read doesn't
 * say. As the Java generator. */
function readBinding(spec: Spec, n: SpecProperty): { helper: string; argument: string; collection: string } | undefined {
  const target = spec.typeByName.get(n.type);
  if (!target || target.kind !== "entity") return undefined;
  let set: string | undefined;
  let key: string[] = [];
  // The type, then the types it derives from, as Java's typeHierarchy
  for (const level of spec.chain(target).reverse()) {
    if (level.key?.length && !key.length) key = level.key;
    set ??= (spec.doc.container.entitySets ?? []).find((s) => s.entityType === level.qualifiedName)?.name;
  }
  if (set !== undefined && key.length === 1) return { helper: n.collection ? "byKeys" : "byKey", argument: key[0]!, collection: set };
  if (target.name === "Element" || target.name === "Member")
    return { helper: n.collection ? "allInHierarchy" : "inHierarchy", argument: "UniqueName", collection: target.name + "s" };
  return undefined;
}

function emitPatch(spec: Spec, t: SpecType, create = false): string {
  const name = t.name;
  const suffix = create ? "Create" : "Patch";
  const patch = name + suffix;
  const builder = patch + "Builder";
  // What it offers, by the JSON name each setter writes: a navigation's nested setter by its name,
  // its binding by its @odata.bind one
  const offered = spec.offered(patch)!;
  const base = spec.base(t);
  if (base && !spec.offered(base.name + suffix)) throw new Error(`${patch} extends ${base.name + suffix}, which the offers don't list`);
  const typeId = base ? JSON.stringify("#" + t.qualifiedName) : "null";
  const ticks = "`@odata.type`";
  const subtypes = spec.derived(t);
  let out = create
    ? `\n/** A create of {@link model.${name}}, for a POST: only the properties set are sent, and only those TM1 was found to apply in a create are offered. Its build() checks what a create needs. */\n`
    : `\n/** A write of {@link model.${name}}: only the properties set are sent, and only those TM1 was found to apply are offered. */\n`;
  out += `export class ${patch} extends ${base ? base.name + suffix : "Tm1Patch"} {\n`;
  if (subtypes.length && create) {
    // A hierarchy's root: its create starts from a read of any of its types, with that type's
    const cases = subtypes
      .filter((d) => creatable(spec, d))
      .map((d) => `    if (type === ${JSON.stringify(d.name)}) return ${d.name}Create.builder(read as model.${d.name});\n`)
      .join("");
    out += `  /** A builder with nothing set; or, given an entity read from TM1, a create of what it holds that a POST applies, to recreate it, by its type: that type's builder(read). @throws RangeError if TM1 makes none of its type */\n`;
    out += `  static builder(read?: model.${name}): ${builder} {\n    if (!read) return new ${builder}();\n`;
    out += `    const type = typeOf(model.${name}, read);\n${cases}`;
    out += "    throw new RangeError(`A ${type} has no create: TM1 makes none of its type`);\n  }\n}\n\n";
  } else if (subtypes.length) {
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}. */\n`;
    out += `  static ${base ? "override " : ""}builder(): ${builder} {\n    return new ${builder}();\n  }\n}\n\n`;
    // A hierarchy's root: a nested value of any of its types is written by its type, as the Java
    // patch's of()
    const cases = subtypes
      .filter((d) => patchable(spec, d))
      .map((d) => `  if (type === ${JSON.stringify(d.name)}) return ${d.name}Patch.builder(read as model.${d.name}).build();\n`)
      .join("");
    const declared = t.open ? JSON.stringify([...(t.properties ?? []), ...(t.navigationProperties ?? [])].map((p) => p.name)) : undefined;
    out += `/** A patch of what \`read\`, a value read from TM1, holds that a write takes, by its type: for nested values. */\n`;
    out += `function of${patch}(read: model.${name}): ${patch} {\n  const type = typeOf(model.${name}, read);\n${cases}`;
    out += declared ? `  return ${patch}.builder().copy(read, [], ${declared}).build();\n}\n\n` : `  return ${patch}.builder().build();\n}\n\n`;
  } else {
    const copied: string[] = [];
    // A structured value is copied as the write type its setter takes, through that type's
    // builder(read), or a root patch's of(): what it holds that a write takes
    const nested: string[] = [];
    const bindings: string[] = [];
    for (const level of spec.chain(t)) {
      for (const p of level.properties ?? []) {
        if (p.type === "Edm.Stream" || copied.includes(p.name) || !(p.name in offered) || (!create && !patches(t, p.name))) continue;
        const value = p.departure ? undefined : spec.typeByName.get(p.type);
        if (value) {
          const write = value.name + (creatable(spec, value) ? "Create" : "Patch");
          const root = spec.derived(value).length > 0;
          // A root's create starts from a read only by its type, which a nested value's read
          // doesn't settle: not copied, as the Java generator
          if (root && write.endsWith("Create")) continue;
          nested.push(`${JSON.stringify(p.name)}: (v: model.${value.name}) => ${root ? `of${write}(v)` : `${write}.builder(v).build()`}`);
        }
        copied.push(p.name);
      }
      for (const n of level.navigationProperties ?? []) {
        const how = n.name + BIND in offered ? readBinding(spec, n) : undefined;
        if (!how) continue;
        const bind = "bind" + Names.member(n.name);
        const read = `read[${JSON.stringify(n.name)}]`;
        const argument = `[${JSON.stringify(how.argument)}]`;
        bindings.push(
          n.collection
            ? `    if (${read}) {\n      const targets = ${how.helper}(${JSON.stringify(how.collection)}, ${read}.map((v) => v${argument}));\n      if (targets) b.${bind}(...targets);\n    }\n`
            : `    if (${read}) {\n      const target = ${how.helper}(${JSON.stringify(how.collection)}, ${read}${argument});\n      if (target) b.${bind}(target);\n    }\n`,
        );
      }
    }
    const declared = spec.chain(t).some((l) => l.open)
      ? JSON.stringify(spec.chain(t).flatMap((l) => [...(l.properties ?? []), ...(l.navigationProperties ?? [])].map((p) => p.name)))
      : "null";
    const converters = nested.length ? `, { ${nested.join(", ")} }` : "";
    const what = create ? "a POST applies, to recreate it" : "a PATCH applies, leaving out collections a PATCH appends to";
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}; or, given an entity read from TM1, one holding what it holds that ${what}. A navigation the read holds expanded is bound, where its targets' paths follow from it: an entity set's by key, an element's or member's by its \`UniqueName\`; one it doesn't hold, or a subset or a view (whose path the read can't tell), isn't, nor are nested ones or nulls. */\n`;
    out += `  static ${base ? "override " : ""}builder(read?: model.${name}): ${builder} {\n`;
    if (!bindings.length)
      out += `    const b = new ${builder}();\n    return read ? b.copy(read, ${JSON.stringify(copied)}, ${declared}${converters}) : b;\n  }\n}\n\n`;
    else {
      out += `    const b = new ${builder}();\n    if (!read) return b;\n    b.copy(read, ${JSON.stringify(copied)}, null${converters});\n${bindings.join("")}`;
      out += declared === "null" ? "    return b;\n  }\n}\n\n" : `    return b.copy(read, [], ${declared});\n  }\n}\n\n`;
    }
  }
  out += `/** Builds a {@link ${patch}}; each setter replaces what an earlier call set. */\n`;
  out += `export class ${builder} extends Tm1PatchBuilder<${patch}> {\n`;
  out += `  constructor() {\n    super((values) => new ${patch}(values), ${typeId});\n  }\n`;
  const taken = new Set(["build", "property", "set", "bind", "bindAll", "copy", "has", "constructor"]);
  const required = create ? (t.write?.create.required ?? []) : [];
  if (required.length) {
    out += `\n  /** The create, with what's been set so far. @throws Error if it lacks what a create needs. */\n`;
    out += `  override build(): ${patch} {\n`;
    for (const r of required) {
      const bare = r.replace(BIND, "");
      out += `    if (!this.has(${JSON.stringify(bare)})) throw new Error(${JSON.stringify(`A ${name} create needs ${bare}: TM1 refuses one without it`)});\n`;
    }
    out += "    return super.build();\n  }\n";
  }
  for (const level of spec.chain(t)) {
    for (const p of level.properties ?? []) {
      const type = p.departure ? "unknown" : patchType(spec, p.type, !!p.collection);
      if (type === undefined || !(p.name in offered)) continue;
      const method = Names.unique(Names.method(p.name), taken);
      out += `\n  ${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(p.name)}, value);\n  }\n`;
    }
    for (const n of level.navigationProperties ?? []) {
      const type = patchType(spec, n.type, !!n.collection);
      if (type !== undefined && n.name in offered) {
        const method = Names.unique(Names.method(n.name), taken);
        out += `\n  /** Sets \`${n.name}\` (nested, for a deep insert). */\n`;
        out += `  ${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(n.name)}, value);\n  }\n`;
      }
      // Set and bind are offered apart: a binding where TM1 binds, a nested setter where it inserts
      if (spec.isEntity(n.type) && n.name + BIND in offered) {
        const binder = Names.unique("bind" + Names.member(n.name), taken);
        if (n.collection) {
          out += `\n  /** Binds \`${n.name}\` to existing entities (\`${n.name}@odata.bind\`). */\n`;
          out += `  ${binder}(...targets: ODataPath[]): this {\n    return this.bindAll(${JSON.stringify(n.name)}, targets);\n  }\n`;
        } else {
          out += `\n  /** Binds \`${n.name}\` to an existing entity (\`${n.name}@odata.bind\`). */\n`;
          out += `  ${binder}(target: ODataPath): this {\n    return this.bind(${JSON.stringify(n.name)}, target);\n  }\n`;
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
