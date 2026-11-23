// SPDX-License-Identifier: Apache-2.0

import { Tm1ProtocolException, Tm1UnsupportedOperationException } from "../errors/index.js";

/** A TM1 version, such as `11.8.03500.6` or schema `11.8.35`: compared part by part, missing parts
 * as 0, as the Java client's `Tm1Version`. */
export class Tm1Version {
  private constructor(
    private readonly text: string,
    private readonly parts: readonly number[],
  ) {}

  /** Parses a version such as `11.8.03500.6`. Surrounding whitespace is ignored. @throws RangeError
   * if the text isn't dot-separated numbers */
  static parse(text: string): Tm1Version {
    const trimmed = String(text).trim();
    if (!/^\d+(\.\d+)*$/.test(trimmed)) {
      throw new RangeError(`Not a TM1 version: '${text}' (expected numbers separated by dots, like 11.8.03500.6)`);
    }
    const parts = trimmed.split(".").map((p) => {
      const n = Number(p);
      if (n > 2147483647) throw new RangeError(`Not a TM1 version: '${text}' (part '${p}' is too large)`);
      return n;
    });
    return new Tm1Version(trimmed, parts);
  }

  /** True if this version is the same as or newer than `other`. */
  isAtLeast(other: Tm1Version | string): boolean {
    return this.compareTo(other) >= 0;
  }

  /** True if this version is older than `other`. */
  isBefore(other: Tm1Version | string): boolean {
    return this.compareTo(other) < 0;
  }

  /** Negative, zero or positive as this version is older than, the same as or newer than `other`,
   * comparing number by number. */
  compareTo(other: Tm1Version | string): number {
    const o = typeof other === "string" ? Tm1Version.parse(other) : other;
    const length = Math.max(this.parts.length, o.parts.length);
    for (let i = 0; i < length; i++) {
      const a = this.parts[i] ?? 0;
      const b = o.parts[i] ?? 0;
      if (a !== b) return a < b ? -1 : 1;
    }
    return 0;
  }

  /** Whether `other` is the same version, number by number: `11.8.03500.6` equals `11.8.3500.6`. */
  equals(other: unknown): boolean {
    return other instanceof Tm1Version && this.compareTo(other) === 0;
  }

  /** The version exactly as parsed, such as `11.8.03500.6`. */
  toString(): string {
    return this.text;
  }
}

/** What a {@link Tm1Capability} names: an entity set, a singleton, a bound or unbound operation, or
 * a property. */
export type Tm1CapabilityKind = "EntitySet" | "Singleton" | "BoundOperation" | "UnboundOperation" | "Property";

function simple(qualified: string): string {
  return qualified.slice(qualified.lastIndexOf(".") + 1);
}

/** Something a request needs the server's `$metadata` to define, as the Java client's `Tm1Capability`. */
export class Tm1Capability {
  /** Something a request needs the server to define in its `$metadata`; made by the static methods.
   * Names are qualified by namespace or alias (`tm1.Cube` or `ibm.tm1.api.v1.Cube`), as in
   * `$metadata`. */
  constructor(
    readonly kind: Tm1CapabilityKind,
    readonly type: string | null,
    readonly name: string,
    readonly addedIn: string | null = null,
  ) {
    if ((kind === "BoundOperation" || kind === "Property") && type === null) throw new TypeError(kind + " needs the type it belongs to");
  }

  /** An entity set, such as `GitPlans`. */
  static entitySet(name: string): Tm1Capability {
    return new Tm1Capability("EntitySet", null, name);
  }

  /** A singleton, such as `ActiveSession`. */
  static singleton(name: string): Tm1Capability {
    return new Tm1Capability("Singleton", null, name);
  }

  /** An action or function bound to a type, or to one of its base types. @param bindingType the
   * qualified type, such as `tm1.Cube` @param operation the qualified operation name, such as
   * `tm1.Reserve` */
  static boundOperation(bindingType: string, operation: string): Tm1Capability {
    return new Tm1Capability("BoundOperation", bindingType, operation);
  }

  /** An action or function import, invoked from the service root, such as `ExecuteMDX`. */
  static unboundOperation(name: string): Tm1Capability {
    return new Tm1Capability("UnboundOperation", null, name);
  }

  /** A structural or navigation property of a type, or of one of its base types. @param type the
   * qualified type, such as `tm1.Cube` @param property the property's name, such as `Rules` */
  static property(type: string, property: string): Tm1Capability {
    return new Tm1Capability("Property", type, property);
  }

  /** A copy recording the schema version that added it: the version from `Core.Revisions`, such as
   * `11.8.34`. */
  since(schemaVersion: string): Tm1Capability {
    return new Tm1Capability(this.kind, this.type, this.name, schemaVersion);
  }

  /** The schema version that added it, or undefined if not known. */
  addedInVersion(): string | undefined {
    return this.addedIn ?? undefined;
  }

  /** How it reads in messages, with aliases and namespaces dropped: `Cube.Reserve`, `entity set
   * GitPlans`. */
  describe(): string {
    switch (this.kind) {
      case "EntitySet":
        return "entity set " + this.name;
      case "Singleton":
        return "singleton " + this.name;
      case "UnboundOperation":
        return "operation " + this.name;
      default:
        return simple(this.type!) + "." + simple(this.name);
    }
  }

  /** The capability, as {@link describe} reads, for logs. */
  toString(): string {
    return `Tm1Capability[${this.describe()}]`;
  }
}

interface XmlElement {
  name: string;
  attributes: Record<string, string>;
  children: XmlElement[];
  text: string;
}

function decodeEntities(s: string): string {
  return s.replace(/&(lt|gt|amp|quot|apos|#\d+|#x[0-9a-fA-F]+);/g, (_, e: string) => {
    switch (e) {
      case "lt":
        return "<";
      case "gt":
        return ">";
      case "amp":
        return "&";
      case "quot":
        return '"';
      case "apos":
        return "'";
    }
    return String.fromCodePoint(e.startsWith("#x") ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
  });
}

/** Just enough XML for CSDL: elements, attributes and text, namespaces by local name. */
function parseXml(text: string): XmlElement {
  const root: XmlElement = { name: "#document", attributes: {}, children: [], text: "" };
  const stack = [root];
  const tag =
    /<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[([\s\S]*?)\]\]>|<!DOCTYPE[^>]*>|<(\/?)([A-Za-z_][\w.:-]*)((?:\s+[^\s=/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/?)>|([^<]+)/g;
  let m: RegExpExecArray | null;
  let last = 0;
  while ((m = tag.exec(text)) !== null) {
    if (m.index !== last) throw new SyntaxError("Not XML near offset " + last);
    last = tag.lastIndex;
    const top = stack[stack.length - 1]!;
    if (m[1] !== undefined) top.text += m[1];
    else if (m[6] !== undefined) top.text += decodeEntities(m[6]);
    else if (m[3] !== undefined) {
      const name = m[3].includes(":") ? m[3].slice(m[3].indexOf(":") + 1) : m[3];
      if (m[2] === "/") {
        if (stack.length < 2 || top.name !== name) throw new SyntaxError(`Unexpected </${m[3]}>`);
        stack.pop();
        continue;
      }
      const attributes: Record<string, string> = {};
      for (const a of (m[4] ?? "").matchAll(/([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) {
        const key = a[1]!.includes(":") && !a[1]!.startsWith("xmlns") ? a[1]!.slice(a[1]!.indexOf(":") + 1) : a[1]!;
        attributes[key] = decodeEntities(a[2] ?? a[3] ?? "");
      }
      const element: XmlElement = { name, attributes, children: [], text: "" };
      top.children.push(element);
      if (m[5] !== "/") stack.push(element);
    }
  }
  if (last !== text.length || stack.length !== 1) throw new SyntaxError("Unterminated XML");
  return root;
}

function descendants(e: XmlElement, name: string): XmlElement[] {
  const out: XmlElement[] = [];
  const walk = (x: XmlElement): void => {
    for (const c of x.children) {
      if (c.name === name) out.push(c);
      walk(c);
    }
  };
  walk(e);
  return out;
}

const textOf = (e: XmlElement): string => e.text + e.children.map(textOf).join("");

const childrenOf = (e: XmlElement, name: string): XmlElement[] => e.children.filter((c) => c.name === name);

/** What a server's `$metadata` defines, and its versions: what requests check before they're sent,
 * as the Java client's `Tm1Capabilities`. {@link endpoints} are made the way the spec's inventory is. */
export class Tm1Capabilities {
  private constructor(
    readonly schemaVersion: string | undefined,
    readonly productVersion: Tm1Version | undefined,
    /** The endpoints, as coverage-gate IDs. */
    readonly endpoints: ReadonlySet<string>,
    private readonly types: Map<string, { base: string | undefined; members: Set<string> }>,
    private readonly bound: Map<string, Set<string>>,
    private readonly entitySets: Set<string>,
    private readonly singletons: Set<string>,
    private readonly imports: Set<string>,
    private readonly aliases: Map<string, string>,
  ) {}

  /** Reads a `$metadata` document. */
  static parse(metadata: string, productVersion?: Tm1Version): Tm1Capabilities {
    let doc: XmlElement;
    try {
      doc = parseXml(metadata.replace(/^\uFEFF/, ""));
    } catch (e) {
      throw new Tm1ProtocolException("TM1's $metadata isn't XML: " + (e as Error).message);
    }
    const top = doc.children[0];
    if (top?.name !== "Edmx") throw new Tm1ProtocolException("TM1's $metadata isn't CSDL: its root is " + top?.name);
    const schemas = descendants(top, "Schema");
    const aliases = new Map<string, string>();
    for (const s of schemas) if (s.attributes["Alias"]) aliases.set(s.attributes["Alias"], s.attributes["Namespace"]!);
    const qualify = (name: string): string => {
      const dot = name.lastIndexOf(".");
      if (dot < 0) return name;
      const prefix = name.slice(0, dot);
      return (aliases.get(prefix) ?? prefix) + name.slice(dot);
    };
    const endpoints = new Set<string>();
    const types = new Map<string, { base: string | undefined; members: Set<string> }>();
    const bound = new Map<string, Set<string>>();
    const sets = new Set<string>();
    const singletons = new Set<string>();
    const imports = new Set<string>();
    let version: string | undefined;
    for (const schema of schemas) {
      const ns = schema.attributes["Namespace"]!;
      for (const a of childrenOf(schema, "Annotation")) {
        if ((a.attributes["Term"] ?? "").endsWith("SchemaVersion")) version = a.attributes["String"] ?? textOf(a).trim();
      }
      for (const kind of ["Action", "Function"]) {
        for (const op of childrenOf(schema, kind)) {
          if (op.attributes["IsBound"] !== "true") continue;
          const first = childrenOf(op, "Parameter")[0];
          if (!first) continue;
          const binding = first.attributes["Type"]!;
          const collection = binding.startsWith("Collection(");
          const inner = collection ? binding.slice("Collection(".length, -1) : binding;
          const s = inner.slice(inner.lastIndexOf(".") + 1);
          endpoints.add(`${kind} ${collection ? `Collection(${s})` : s}/${op.attributes["Name"]}`);
          const key = qualify(inner);
          if (!bound.has(key)) bound.set(key, new Set());
          bound.get(key)!.add(op.attributes["Name"]!);
        }
      }
      for (const t of [...childrenOf(schema, "EntityType"), ...childrenOf(schema, "ComplexType")]) {
        const name = t.attributes["Name"]!;
        const members = new Set<string>();
        for (const p of childrenOf(t, "Property")) members.add(p.attributes["Name"]!);
        for (const n of childrenOf(t, "NavigationProperty")) {
          members.add(n.attributes["Name"]!);
          endpoints.add(`Navigation ${name}/${n.attributes["Name"]}`);
        }
        const base = t.attributes["BaseType"];
        types.set(`${ns}.${name}`, { base: base === undefined ? undefined : qualify(base), members });
      }
      for (const c of childrenOf(schema, "EntityContainer")) {
        for (const [tag, set] of [
          ["EntitySet", sets],
          ["Singleton", singletons],
          ["ActionImport", imports],
          ["FunctionImport", imports],
        ] as const) {
          for (const item of childrenOf(c, tag)) {
            endpoints.add(`${tag} ${item.attributes["Name"]}`);
            set.add(item.attributes["Name"]!);
          }
        }
      }
    }
    return new Tm1Capabilities(version, productVersion, endpoints, types, bound, sets, singletons, imports, aliases);
  }

  /** A copy that knows the server's product version, for the message of a refusal. */
  withProductVersion(productVersion: Tm1Version | undefined): Tm1Capabilities {
    return new Tm1Capabilities(
      this.schemaVersion,
      productVersion,
      this.endpoints,
      this.types,
      this.bound,
      this.entitySets,
      this.singletons,
      this.imports,
      this.aliases,
    );
  }

  /** Whether the server's `$metadata` defines `capability`. */
  supports(capability: Tm1Capability): boolean {
    switch (capability.kind) {
      case "EntitySet":
        return this.entitySets.has(capability.name);
      case "Singleton":
        return this.singletons.has(capability.name);
      case "UnboundOperation":
        return this.imports.has(capability.name);
      case "BoundOperation":
        return this.hierarchy(capability.type!).some((t) => this.bound.get(t)?.has(simple(capability.name)) ?? false);
      default:
        return this.hierarchy(capability.type!).some((t) => this.types.get(t)!.members.has(capability.name));
    }
  }

  /** Undefined if the server has it; otherwise the error saying what's missing, as Java's. */
  check(capability: Tm1Capability, server: string): Tm1UnsupportedOperationException | undefined {
    if (this.supports(capability)) return undefined;
    const missing = capability.describe();
    const added = capability.addedInVersion();
    let message = `${missing} isn't defined by the TM1 server at ${server}` + (added !== undefined ? `: it was added in schema ${added}, and ` : " (");
    message += this.schemaVersion !== undefined ? "the server reports schema " + this.schemaVersion : "the server reports no schema version";
    if (this.productVersion) message += ", product version " + this.productVersion.toString();
    message += added !== undefined ? "" : ")";
    return new Tm1UnsupportedOperationException(message, missing);
  }

  /** How much the server defines, for logs. */
  toString(): string {
    return `Tm1Capabilities[schema ${this.schemaVersion ?? "unknown"}, product ${this.productVersion?.toString() ?? "unknown"}]`;
  }

  private hierarchy(type: string): string[] {
    const result: string[] = [];
    let current: string | undefined = this.qualify(type);
    while (current !== undefined && this.types.has(current)) {
      result.push(current);
      current = this.types.get(current)!.base;
    }
    return result;
  }

  private qualify(name: string): string {
    const dot = name.lastIndexOf(".");
    if (dot < 0) return name;
    const prefix = name.slice(0, dot);
    return (this.aliases.get(prefix) ?? prefix) + name.slice(dot);
  }
}

/** How a server's address becomes its REST API's service root, as the Java client's `Tm1Dialect`:
 * {@link Tm1Dialect.V11} turns `http://tm1:8010` into `http://tm1:8010/api/v1/`. */
export abstract class Tm1Dialect {
  /** TM1 11 on-premises: the REST API is at `http(s)://host:port/api/v1/`. */
  static readonly V11: Tm1Dialect = new (class extends Tm1Dialect {
    name(): string {
      return "TM1 11";
    }

    serviceRoot(address: string): string {
      let url: URL;
      try {
        url = new URL(String(address).trim());
      } catch {
        url = undefined as unknown as URL;
      }
      if (!url || (url.protocol !== "http:" && url.protocol !== "https:") || url.search || url.hash) {
        throw new RangeError("A TM1 11 address is the server's scheme, host and HTTP port, such as http://tm1:8010: " + address);
      }
      let path = url.pathname.replace(/\/+$/, "");
      if (!path.endsWith("/api/v1")) path += "/api/v1";
      return `${url.protocol}//${url.host}${path}/`;
    }
  })();

  abstract name(): string;

  abstract serviceRoot(address: string): string;

  /** The dialect's name. */
  toString(): string {
    return `Tm1Dialect[${this.name()}]`;
  }
}
