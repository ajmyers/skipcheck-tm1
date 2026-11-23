import { entriesOf, type Tm1Map } from "../util.js";
import { ODataEncoding } from "./encoding.js";
import { ODataLiterals } from "./literals.js";

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
const QUALIFIED = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/;

function identifier(name: string): string {
  if (typeof name !== "string" || !IDENTIFIER.test(name)) {
    throw new RangeError(`Not an OData identifier: '${name}'. Key values go in key(), not in a segment name.`);
  }
  return name;
}

function qualified(name: string): string {
  if (typeof name !== "string" || !QUALIFIED.test(name)) throw new RangeError(`Not a qualified OData name: '${name}'`);
  return name;
}

function keyValue(value: unknown): string {
  return typeof value === "string" ? "'" + ODataEncoding.pathValue(value.replace(/'/g, "''")) + "'" : ODataEncoding.pathValue(ODataLiterals.of(value));
}

function call(fn: string, parameters: Tm1Map<unknown> | undefined): string {
  return (
    fn +
    "(" +
    entriesOf(parameters)
      .map(([k, v]) => identifier(k) + "=" + (v instanceof ODataPath.Alias ? v.name : ODataEncoding.pathValue(ODataLiterals.of(v))))
      .join(",") +
    ")"
  );
}

/** A resource path relative to the service root, as the Java client's `ODataPath`:
 * `Cubes('Sales')/Views('Default')`. Immutable; names are checked and key values encoded. */
export class ODataPath {
  private constructor(private readonly segments: readonly string[]) {}

  static of(name: string): ODataPath {
    return new ODataPath([identifier(name)]);
  }

  static metadata(): ODataPath {
    return new ODataPath(["$metadata"]);
  }

  static batch(): ODataPath {
    return new ODataPath(["$batch"]);
  }

  /** A path TM1 sent (an `@odata.id`, a `Location`), re-encoded segment by segment. */
  static link(link: string): ODataPath {
    if (typeof link !== "string" || link.length === 0 || link.startsWith("/") || link.includes("://") || link.includes("?")) {
      throw new RangeError("Not a relative path without a query: " + link);
    }
    return new ODataPath(link.split("/").map((s) => ODataEncoding.linkSegment(s)));
  }

  static functionImport(name: string, parameters?: Tm1Map<unknown>): ODataPath {
    return new ODataPath([call(identifier(name), parameters)]);
  }

  /** `Cubes` → `Cubes('Sales')`; a compound key from a map: `Edges(ParentName='a',ComponentName='b')`. */
  key(value: unknown): ODataPath {
    if (value !== null && typeof value === "object" && !(value instanceof Date) && !("odataLiteral" in value)) {
      const parts = entriesOf(value as Tm1Map<unknown>);
      if (parts.length === 0) throw new RangeError("A compound key needs at least one part");
      return this.appendToLast("(" + parts.map(([k, v]) => identifier(k) + "=" + keyValue(v)).join(",") + ")");
    }
    return this.appendToLast("(" + keyValue(value) + ")");
  }

  nav(property: string): ODataPath {
    return this.append(identifier(property));
  }

  cast(qualifiedType: string): ODataPath {
    return this.append(qualified(qualifiedType));
  }

  action(qualifiedName: string): ODataPath {
    return this.append(qualified(qualifiedName));
  }

  function(qualifiedName: string, parameters?: Tm1Map<unknown>): ODataPath {
    return this.append(call(qualified(qualifiedName), parameters));
  }

  value(): ODataPath {
    return this.append("$value");
  }

  count(): ODataPath {
    return this.append("$count");
  }

  ref(): ODataPath {
    return this.append("$ref");
  }

  equals(other: unknown): boolean {
    return other instanceof ODataPath && other.toString() === this.toString();
  }

  toString(): string {
    return this.segments.join("/");
  }

  toJSON(): string {
    return this.toString();
  }

  private append(segment: string): ODataPath {
    return new ODataPath([...this.segments, segment]);
  }

  private appendToLast(suffix: string): ODataPath {
    const last = this.segments[this.segments.length - 1];
    if (last === undefined) throw new Error("A key follows a collection");
    if (last.endsWith(")") || last.startsWith("$")) throw new Error(`Can't add a key to '${last}': a key follows a collection, once`);
    return new ODataPath([...this.segments.slice(0, -1), last + suffix]);
  }
}

export namespace ODataPath {
  /** A parameter alias, `@p`: a function's structured argument, given as JSON in the query
   * (`Fn(p=@p)?@p={…}`). */
  export class Alias {
    constructor(readonly name: string) {
      if (typeof name !== "string" || !name.startsWith("@") || !IDENTIFIER.test(name.slice(1))) {
        throw new RangeError("A parameter alias is @ followed by an identifier: " + name);
      }
    }

    toString(): string {
      return this.name;
    }
  }
}
