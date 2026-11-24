// SPDX-License-Identifier: Apache-2.0

// The spec (spec/tm1-api.json), with the lookups every emitter needs, as tm1_pygen's spec.py and
// Tm1.Codegen's Spec.cs.

export interface SpecProperty {
  name: string;
  type: string;
  collection?: boolean;
  nullable?: boolean;
  description?: string;
  since?: string;
  deprecated?: { version?: string; reason?: string } | null;
  departure?: { read?: string; note?: string };
  undeclared?: { source?: string; observedIn?: string[] };
  lenientDate?: { note?: string };
  containsTarget?: boolean;
}

export interface SpecType {
  name: string;
  qualifiedName: string;
  kind: "entity" | "complex";
  baseType: string | null;
  abstract?: boolean;
  open?: boolean;
  derivedTypes?: string[];
  description?: string;
  since?: string;
  deprecated?: { version?: string; reason?: string } | null;
  properties?: SpecProperty[];
  navigationProperties?: SpecProperty[];
}

export interface SpecEnum {
  name: string;
  qualifiedName: string;
  flags: boolean;
  description?: string;
  since?: string;
  deprecated?: { version?: string; reason?: string } | null;
  members: { name: string; value: number; description?: string; since?: string }[];
}

export interface SpecOperation {
  kind: "action" | "function";
  name: string;
  qualifiedName: string;
  pathName: string;
  bound: boolean;
  binding?: { type: string; collection: boolean };
  description?: string;
  since?: string;
  deprecated?: { version?: string; reason?: string } | null;
  parameters?: { name: string; type: string; collection?: boolean; nullable?: boolean }[];
  returns?: { type: string; collection?: boolean } | null;
}

export interface SpecDocument {
  specVersion: number;
  source: { schemaVersion: string };
  schemas: { namespace: string; alias?: string }[];
  enums: SpecEnum[];
  types: SpecType[];
  operations: SpecOperation[];
  container: {
    actionImports?: { name: string; operation: string }[];
    functionImports?: { name: string; operation: string }[];
  };
  endpoints: { id: string }[];
}

export class Spec {
  readonly typeByName = new Map<string, SpecType>();
  readonly enumByName = new Map<string, SpecEnum>();
  readonly aliases = new Map<string, string>();

  constructor(readonly doc: SpecDocument) {
    for (const t of doc.types) this.typeByName.set(t.qualifiedName, t);
    for (const e of doc.enums) this.enumByName.set(e.qualifiedName, e);
    for (const s of doc.schemas) if (s.alias) this.aliases.set(s.namespace, s.alias);
  }

  get types(): SpecType[] {
    return this.doc.types;
  }

  get enums(): SpecEnum[] {
    return this.doc.enums;
  }

  base(t: SpecType): SpecType | undefined {
    return t.baseType ? this.typeByName.get(t.baseType) : undefined;
  }

  root(t: SpecType): SpecType {
    let current = t;
    for (let b = this.base(current); b; b = this.base(current)) current = b;
    return current;
  }

  /** The type and the types it derives from, root first. */
  chain(t: SpecType): SpecType[] {
    const chain = [t];
    for (let b = this.base(t); b; b = this.base(b)) chain.unshift(b);
    return chain;
  }

  derived(t: SpecType): SpecType[] {
    return (t.derivedTypes ?? []).map((d) => this.typeByName.get(d)!);
  }

  isEntity(typeName: string): boolean {
    return this.typeByName.get(typeName)?.kind === "entity";
  }

  aliasName(qualified: string): string {
    for (const [ns, alias] of this.aliases) {
      if (qualified.startsWith(ns + ".")) return alias + qualified.slice(ns.length);
    }
    return qualified;
  }
}

/** TypeScript names for TM1's, by the Java client's rules. */
export const Names = {
  /** The Java client's `componentName`, capitalized: `ID` → `Id`, `UIData` → `UiData`. */
  member(property: string): string {
    let upper = 0;
    while (upper < property.length && /[A-Z]/.test(property[upper]!)) upper++;
    let camel: string;
    if (upper === property.length) camel = property.toLowerCase();
    else if (upper <= 1) camel = property[0]!.toLowerCase() + property.slice(1);
    else camel = property.slice(0, upper - 1).toLowerCase() + property.slice(upper - 1);
    const pascal = camel[0]!.toUpperCase() + camel.slice(1);
    return pascal.replace(/[^A-Za-z0-9_]/g, "_");
  },

  /** The Java client's `componentName`: a builder method, `ID` → `id`, `Name` → `name`. */
  method(property: string): string {
    const m = Names.member(property);
    return m[0]!.toLowerCase() + m.slice(1);
  },

  /** The Java client's `constantName` words, in Pascal case: `Chore` + `Activate` → `ChoreActivate`. */
  constant(name: string): string {
    let snake = name.replace(/([a-z])([A-Z])/g, "$1_$2");
    snake = snake.replace(/([A-Z]+)([A-Z][a-z])/g, "$1_$2");
    snake = snake.replace(/[^A-Za-z0-9_]/g, "_");
    const s = snake
      .split("_")
      .filter((w) => w.length > 0)
      .map((w) => w[0]!.toUpperCase() + w.slice(1).toLowerCase())
      .join("");
    return /^[0-9]/.test(s) ? "_" + s : s;
  },

  unique(name: string, taken: Set<string>): string {
    let candidate = name;
    for (let n = 2; taken.has(candidate); n++) candidate = name + n;
    taken.add(candidate);
    return candidate;
  },

  /** A property key: bare when it's an identifier, quoted otherwise. */
  key(name: string): string {
    return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) ? name : JSON.stringify(name);
  },

  /** Text for a JSDoc comment. */
  doc(text: string): string {
    return text.replace(/\*\//g, "*\\/").replace(/\s+/g, " ").trim();
  },
};

export const HEADER =
  "// SPDX-License-Identifier: Apache-2.0\n\n// Generated from spec/tm1-api.json by the TypeScript client's codegen: don't edit, don't commit.\n/* eslint-disable */\n";

/** A JSDoc comment from a description and the item's since/deprecated notes, or nothing. */
export function jsdoc(indent: string, text: string | undefined, item: { since?: string; deprecated?: { version?: string; reason?: string } | null }): string {
  const parts: string[] = [];
  if (text && text.trim()) parts.push(Names.doc(text));
  if (item.since) parts.push(`Since TM1 schema ${item.since}.`);
  let deprecated = "";
  if (item.deprecated && typeof item.deprecated === "object") {
    deprecated = `Deprecated in TM1 schema ${item.deprecated.version}` + (item.deprecated.reason ? ": " + Names.doc(item.deprecated.reason) : ".");
  }
  if (parts.length === 0 && !deprecated) return "";
  let out = `${indent}/** ${parts.join(" ")}`;
  if (deprecated) out += `${parts.length ? ` @deprecated ` : `@deprecated `}${deprecated}`;
  return out + " */\n";
}
