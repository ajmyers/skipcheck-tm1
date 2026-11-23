// Emits patches.ts: a patch per type, as the Java model's `CubePatch`:
// `CubePatch.builder().name("Sales").bindDimensions(dims.key("Region")).build()`. Only the properties
// set are sent, an explicit `null` included; a subtype's patch sends its `@odata.type` first.

import { element } from "./emit-model.js";
import { HEADER, Names, type Spec, type SpecType } from "./spec.js";

function patchType(spec: Spec, typeName: string, collection: boolean): string | undefined {
  const t = spec.typeByName.get(typeName);
  let el: string | undefined;
  if (t) el = t.name + "Patch";
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
  return out;
}

function emitPatch(spec: Spec, t: SpecType): string {
  const name = t.name;
  const patch = name + "Patch";
  const builder = patch + "Builder";
  const base = spec.base(t);
  const typeId = base ? JSON.stringify("#" + t.qualifiedName) : "null";
  let out = `\n/** A write of {@link model.${name}}: only the properties set are sent. */\n`;
  out += `export class ${patch} extends ${base ? base.name + "Patch" : "Tm1Patch"} {\n`;
  out += `  /** A builder with nothing set${base ? " but `@odata.type`" : ""}. */\n`;
  out += `  static ${base ? "override " : ""}builder(): ${builder} {\n    return new ${builder}();\n  }\n}\n\n`;
  out += `/** Builds a {@link ${patch}}; each setter replaces what an earlier call set. */\n`;
  out += `export class ${builder} extends Tm1PatchBuilder<${patch}> {\n`;
  out += `  constructor() {\n    super((values) => new ${patch}(values), ${typeId});\n  }\n`;
  const taken = new Set(["build", "property", "set", "bind", "bindAll", "constructor"]);
  for (const level of spec.chain(t)) {
    for (const p of level.properties ?? []) {
      const type = p.departure ? "unknown" : patchType(spec, p.type, !!p.collection);
      if (type === undefined) continue;
      const method = Names.unique(Names.method(p.name), taken);
      out += `\n  ${method}(value: ${type}): this {\n    return this.set(${JSON.stringify(p.name)}, value);\n  }\n`;
    }
    for (const n of level.navigationProperties ?? []) {
      const type = patchType(spec, n.type, !!n.collection);
      if (type === undefined) continue;
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
