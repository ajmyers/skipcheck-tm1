// SPDX-License-Identifier: Apache-2.0

// Emits patches.ts: the write types the spec's offers list, as the Java model's: a patch, as
// `CubePatch.builder().rules(rules).build()`, and a create, as `CubeCreate`, each offering only the
// setters the probe verified or an exception kept (#338). Only the properties set are sent, an
// explicit `null` included; a subtype's write sends its `@odata.type` first.

import { element } from "./emit-model.js";
import { HEADER, Names, type Spec, type SpecBuilder, type SpecProperty, type SpecType } from "./spec.js";

const BIND = "@odata.bind";

/** A type's create or patch, as the write plan (the spec's `builders`) has it, which the Java
 * generator works out once. */
function plan(spec: Spec, t: SpecType, create: boolean): SpecBuilder {
  return spec.builder(t.name + (create ? "Create" : "Patch"))!;
}

/** The type a setter of `p` takes: the write type the plan gives a structured value, else the
 * model's type for it. */
function valueType(spec: Spec, p: SpecProperty, value: string | null): string | undefined {
  let el: string | undefined;
  if (value !== null) el = value;
  else {
    el = element(spec, p.type);
    if (el === undefined) return undefined;
    if (spec.enumByName.has(p.type) && !spec.enumByName.get(p.type)!.flags) el = "model." + el;
  }
  return p.collection ? `readonly ${el}[] | null` : `${el} | null`;
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

/** Entities in a set, by their keys: null if any key is missing, or there are none. */
function byKeys(set: string, keys: readonly unknown[]): ODataPath[] | null {
  if (!keys.length) return null;
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

/** Elements or members, by their \`UniqueName\`s: null if any can't be made, or there are none. */
function allInHierarchy(collection: string, uniqueNames: readonly unknown[]): ODataPath[] | null {
  if (!uniqueNames.length) return null;
  const out: ODataPath[] = [];
  for (const name of uniqueNames) {
    const path = inHierarchy(collection, name);
    if (path === null) return null;
    out.push(path);
  }
  return out;
}
`;

/** Whether a type gets a patch: the write plan has one. */
export function patchable(spec: Spec, t: SpecType): boolean {
  return spec.builder(t.name + "Patch") !== undefined;
}

/** Whether a type gets a create: the write plan has one. */
export function creatable(spec: Spec, t: SpecType): boolean {
  return spec.builder(t.name + "Create") !== undefined;
}

const HELPERS: Record<string, string> = { key: "byKey", keys: "byKeys", uniqueName: "inHierarchy", uniqueNames: "allInHierarchy" };

/** What `builder(read)` does with a builder `b` and a read `read`, as statements, as the write plan
 * says: copies what the read holds that the write takes, binds the navigations whose paths follow
 * from it, then copies an open type's undeclared properties. */
function fromRead(spec: Spec, t: SpecType, create: boolean): { statements: string; plain: string | undefined } {
  const from = plan(spec, t, create).fromRead!;
  const copied = from.copy.map((c) => c.json);
  // A structured value is copied as the write type its setter takes, through that type's
  // builder(read), or a root patch's of(): what it holds that a write takes
  const nested = from.copy
    .filter((c) => c.via !== null)
    .map((c) => {
      const read = c.value!.replace(/(Create|Patch)$/, "");
      return `${JSON.stringify(c.json)}: (v: model.${read}) => ${c.via === "of" ? `of${c.value}(v)` : `${c.value}.builder(v).build()`}`;
    });
  const bindings = from.bindings.map((n) => {
    const helper = HELPERS[n.by + (n.collection ? "s" : "")]!;
    const bind = "bind" + Names.member(n.json);
    const read = `read[${JSON.stringify(n.json)}]`;
    const argument = `[${JSON.stringify(n.key)}]`;
    return n.collection
      ? `    if (${read}) {\n      const targets = ${helper}(${JSON.stringify(n.set)}, ${read}.map((v) => v${argument}));\n      if (targets) b.${bind}(...targets);\n    }\n`
      : `    if (${read}) {\n      const target = ${helper}(${JSON.stringify(n.set)}, ${read}${argument});\n      if (target) b.${bind}(target);\n    }\n`;
  });
  const declared = from.undeclared ? JSON.stringify(from.undeclared) : "null";
  const converters = nested.length ? `, { ${nested.join(", ")} }` : "";
  // Without bindings it's one call, which the caller can write as an expression
  if (!bindings.length) {
    const plain = `b.copy(read, ${JSON.stringify(copied)}, ${declared}${converters})`;
    return { statements: `    ${plain};\n`, plain };
  }
  let statements = `    b.copy(read, ${JSON.stringify(copied)}, null${converters});\n${bindings.join("")}`;
  if (declared !== "null") statements += `    b.copy(read, [], ${declared});\n`;
  return { statements, plain: undefined };
}

/** A builder's setters, in the order they're written: each one's method, the JSON name it writes
 * and its code but for `override`. A subtype's builder has its base's too, as the Java generator's:
 * those of them the subtype is offered, written again. */
function setters(spec: Spec, t: SpecType, create: boolean): { method: string; json: string; doc: string; code: string }[] {
  const offered = plan(spec, t, create).setters;
  const taken = new Set(["build", "property", "set", "bind", "bindAll", "copy", "has", "constructor"]);
  const out: { method: string; json: string; doc: string; code: string }[] = [];
  for (const level of spec.chain(t)) {
    const setter = (name: string) => offered.find((s) => s.json === name && s.declaredOn === level.qualifiedName);
    for (const p of level.properties ?? []) {
      const s = setter(p.name);
      if (!s?.set) continue;
      const type = p.departure ? "unknown" : valueType(spec, p, s.value);
      if (type === undefined) continue;
      const method = Names.unique(Names.method(p.name), taken);
      out.push({ method, json: p.name, doc: "", code: `${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(p.name)}, value);\n  }\n` });
    }
    for (const n of level.navigationProperties ?? []) {
      const s = setter(n.name);
      if (!s) continue;
      const type = valueType(spec, n, s.value);
      if (type !== undefined && s.set) {
        const method = Names.unique(Names.method(n.name), taken);
        out.push({
          method,
          json: n.name,
          doc: `  /** Sets \`${n.name}\` (nested, for a deep insert). */\n`,
          code: `${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(n.name)}, value);\n  }\n`,
        });
      }
      // Set and bind are offered apart: a binding where TM1 binds, a nested setter where it inserts
      if (s.bind) {
        const method = Names.unique("bind" + Names.member(n.name), taken);
        out.push(
          n.collection
            ? {
                method,
                json: n.name + BIND,
                doc: `  /** Binds \`${n.name}\` to existing entities (\`${n.name}@odata.bind\`). */\n`,
                code: `${method}(...targets: ODataPath[]): this {\n    return this.bindAll(${JSON.stringify(n.name)}, targets);\n  }\n`,
              }
            : {
                method,
                json: n.name + BIND,
                doc: `  /** Binds \`${n.name}\` to an existing entity (\`${n.name}@odata.bind\`). */\n`,
                code: `${method}(target: ODataPath): this {\n    return this.bind(${JSON.stringify(n.name)}, target);\n  }\n`,
              },
        );
      }
    }
  }
  return out;
}

function emitPatch(spec: Spec, t: SpecType, create = false): string {
  const name = t.name;
  const suffix = create ? "Create" : "Patch";
  const patch = name + suffix;
  const builder = patch + "Builder";
  const base = spec.base(t);
  const typeId = base ? JSON.stringify("#" + t.qualifiedName) : "null";
  const ticks = "`@odata.type`";
  const subtypes = spec.derived(t);
  let out = create
    ? `\n/** A create of {@link model.${name}}, for a POST: only the properties set are sent, and only those TM1 was found to apply in a create are offered. Its build() checks what a create needs. */\n`
    : `\n/** A write of {@link model.${name}}: only the properties set are sent, and only those TM1 was found to apply are offered. */\n`;
  out += `export class ${patch} extends ${base ? base.name + suffix : "Tm1Patch"} {\n`;
  // Nothing at run time: it makes the type nominal. Without it every write type has the same
  // shape, so a CubeCreate would pass for a ProcessPatch; a subtype's stays its base's, which it
  // extends
  out += `  /** @internal What makes a value of another write type not one of this. */\n  declare private readonly _${patch}: never;\n\n`;
  if (subtypes.length && create) {
    // A hierarchy's root: its create starts from a read of any of its types, with that type's
    const cases = plan(spec, t, true)
      .dispatch!.map((c) => spec.typeByName.get(c.type)!.name)
      .map((d) => `    if (type === ${JSON.stringify(d)}) return ${d}Create.builder(read as model.${d});\n`)
      .join("");
    out += `  /** A builder with nothing set; or, given an entity read from TM1, a create of what it holds that a POST applies, to recreate it, by its type: that type's builder(read), which extends this one's. @throws RangeError if TM1 makes none of its type */\n`;
    out += `  static builder(read?: model.${name}): ${builder} {\n    if (!read) return new ${builder}();\n`;
    out += `    const type = typeOf(model.${name}, read);\n${cases}`;
    out += "    throw new RangeError(`A ${type} has no create: TM1 makes none of its type`);\n  }\n}\n\n";
  } else if (subtypes.length) {
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}. */\n`;
    out += `  static ${base ? "override " : ""}builder(): ${builder} {\n    return new ${builder}();\n  }\n}\n\n`;
    // A hierarchy's root: a nested value of any of its types is written by its type, as the Java
    // patch's of(); one of a type this client doesn't know as the root's own, without the
    // @odata.type a builder of the root doesn't send
    const cases = plan(spec, t, false)
      .dispatch!.map((c) => spec.typeByName.get(c.type)!.name)
      .map((d) => `  if (type === ${JSON.stringify(d)}) return ${d}Patch.builder(read as model.${d}).build();\n`)
      .join("");
    out += `/** A patch of what \`read\`, a value read from TM1, holds that a write takes, by its type: for nested values. */\n`;
    out += `function of${patch}(read: model.${name}): ${patch} {\n  const type = typeOf(model.${name}, read);\n${cases}`;
    out += `  const b = new ${builder}();\n${fromRead(spec, t, false).statements.replace(/^ {2}/gm, "")}  return b.build();\n}\n\n`;
  } else {
    const from = fromRead(spec, t, create);
    const what = create ? "a POST applies, to recreate it" : "a PATCH applies, leaving out collections a PATCH appends to";
    const never = create ? "" : ", nor a collection, which a PATCH would replace or append to";
    out += `  /** A builder with nothing set${base ? " but " + ticks : ""}; or, given an entity read from TM1, one holding what it holds that ${what}. A navigation the read holds expanded is bound, where its targets' paths follow from it: an entity set's by key, an element's or member's by its \`UniqueName\`; one it doesn't hold, or holds empty, or a subset or a view (whose path the read can't tell), isn't, nor are nested ones or nulls${never}. */\n`;
    out += `  static ${base ? "override " : ""}builder(read?: model.${name}): ${builder} {\n    const b = new ${builder}();\n`;
    out += from.plain ? `    return read ? ${from.plain} : b;\n  }\n}\n\n` : `    if (!read) return b;\n${from.statements}    return b;\n  }\n}\n\n`;
  }
  // A subtype's builder extends its base's, as the Java model's: a root's builder(read) returns
  // one, typed as the root's
  const baseBuilder = base ? base.name + suffix + "Builder" : undefined;
  const inherited = new Map((base ? setters(spec, base, create) : []).map((s) => [s.method, s.json]));
  const made = "(values: ReadonlyMap<string, unknown>) => " + patch;
  out += `/** Builds a {@link ${patch}}; each setter replaces what an earlier call set. */\n`;
  out += `export class ${builder} extends ${baseBuilder ?? `Tm1PatchBuilder<${patch}>`} {\n`;
  if (subtypes.length) {
    // What a subtype's builder makes, and the @odata.type it sends, are its own
    out += `  /** A builder with nothing set. @internal The arguments are a subtype's builder's. */\n`;
    out += `  constructor(make: ${made} = (values) => new ${patch}(values), odataType: string | null = ${typeId}) {\n    super(make, odataType);\n  }\n`;
  } else {
    out += `  constructor() {\n    super((values) => new ${patch}(values), ${typeId});\n  }\n`;
  }
  // A subtype's build() checks what its own create needs, as Java's: the plan holds its root's to no more
  const required = plan(spec, t, create).required;
  if (required.length || base) {
    out += required.length
      ? `\n  /** The create, with what's been set so far. @throws Error if it lacks what a create needs. */\n`
      : `\n  /** The ${create ? "create" : "patch"}, with what's been set so far. */\n`;
    out += `  override build(): ${patch} {\n`;
    for (const r of required) {
      out += `    if (!this.has(${JSON.stringify(r)})) throw new Error(${JSON.stringify(`A ${name} create needs ${r}: TM1 refuses one without it`)});\n`;
    }
    // The constructor gave the base's builder what makes this type's
    out += base ? `    return super.build() as ${patch};\n  }\n` : "    return super.build();\n  }\n";
  }
  for (const s of setters(spec, t, create)) {
    const overrides = inherited.has(s.method);
    if (overrides && inherited.get(s.method) !== s.json)
      throw new Error(`${builder}.${s.method} writes ${s.json}, but ${baseBuilder}'s writes ${inherited.get(s.method)}`);
    out += `\n${s.doc}  ${overrides ? "override " : ""}${s.code}`;
  }
  if (spec.chain(t).some((l) => l.open)) {
    const overrides = base !== undefined && spec.chain(base).some((l) => l.open);
    out += "\n  /** Sets a property `$metadata` doesn't declare, which this open type can have, such as an attribute's value. */\n";
    out += `  ${overrides ? "override " : ""}property(name: string, value: unknown): this {\n    return this.set(name, value);\n  }\n`;
  }
  out += "}\n";
  return out;
}
