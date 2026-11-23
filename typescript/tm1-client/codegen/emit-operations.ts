// SPDX-License-Identifier: Apache-2.0

// Emits operations.ts: `Tm1Operations`, a `Tm1Operation` per action and function, named as in the
// Java client (`CHORE_ACTIVATE` → `ChoreActivate`, as the .NET client).

import { HEADER, Names, type Spec, type SpecOperation } from "./spec.js";

export function emitOperations(spec: Spec): string {
  const typeText = (r: { type: string; collection?: boolean }): string => {
    const name = r.type.startsWith("Edm.") ? r.type : spec.aliasName(r.type);
    return r.collection ? `Collection(${name})` : name;
  };
  const lit = (s: string | undefined | null): string => (s == null ? "null" : JSON.stringify(s));
  let out = HEADER;
  out += 'import { Tm1Operation } from "../operation.js";\n\n';
  out +=
    "/** Every action and function of the TM1 REST API, as `$metadata` declares them. Services refer to\n * these, so a request checks the server has the operation. */\n";
  out += "export const Tm1Operations = {\n";
  const taken = new Set(["All"]);
  const all: string[] = [];
  const line = (constant: string, op: SpecOperation, name: string, binding: string | null, importName: string | null): void => {
    const params = (op.parameters ?? []).map((p) => `{ name: ${lit(p.name)}, type: ${lit(typeText(p))}, nullable: ${p.nullable ? "true" : "false"} }`);
    const returns = op.returns && typeof op.returns === "object" ? lit(typeText(op.returns)) : "null";
    const deprecated = op.deprecated && typeof op.deprecated === "object" ? lit(op.deprecated.version) : "null";
    if (op.description) out += `  /** ${Names.doc(op.description)} */\n`;
    out += `  ${constant}: new Tm1Operation(${lit(op.kind === "action" ? "Action" : "Function")}, ${lit(name)}, ${lit(binding)}, ${lit(importName)}, [${params.join(", ")}], ${returns}, ${lit(op.since)}, ${deprecated}),\n`;
    all.push(constant);
  };
  const bound = spec.doc.operations.filter((op) => op.bound).sort((a, b) => cmp(a.binding!.type, b.binding!.type) || cmp(a.name, b.name));
  for (const op of bound) {
    const binding = spec.aliasName(op.binding!.type);
    const simple = binding.slice(binding.lastIndexOf(".") + 1);
    line(Names.unique(Names.constant(simple) + Names.constant(op.name), taken), op, op.pathName, binding, null);
  }
  const imports = [...(spec.doc.container.actionImports ?? []), ...(spec.doc.container.functionImports ?? [])].sort((a, b) => cmp(a.name, b.name));
  const unbound = new Map(spec.doc.operations.filter((op) => !op.bound).map((op) => [op.qualifiedName, op]));
  for (const i of imports) line(Names.unique(Names.constant(i.name), taken), unbound.get(i.operation)!, spec.aliasName(i.operation), null, i.name);
  out += "} as const;\n\n";
  out += "/** Every operation above, bound ones first. */\n";
  out += `export const allOperations: readonly Tm1Operation[] = [${all.map((c) => "Tm1Operations." + c).join(", ")}];\n`;
  return out;
}

function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
