// SPDX-License-Identifier: Apache-2.0

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

  /** A path starting at an entity set, singleton or operation import, such as `Cubes`. @throws
   * RangeError if `name` isn't an OData identifier */
  static of(name: string): ODataPath {
    return new ODataPath([identifier(name)]);
  }

  /** The service's metadata document, `$metadata`: its CSDL XML. A path of its own, since
   * `$metadata` isn't an identifier {@link of} accepts. */
  static metadata(): ODataPath {
    return new ODataPath(["$metadata"]);
  }

  /** The service's batch endpoint, `$batch`: a path of its own, as for {@link metadata}. */
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

  /** A function import called from the service root, such as `ModelCubes()` or
   * `TailMessageLog(Top=10)`. @param name the import's name, unqualified @param parameters the
   * parameter names and values, in order, as for {@link function} */
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

  /** Adds a navigation property or structural property segment, such as `Views`. The name is an
   * OData identifier. */
  nav(property: string): ODataPath {
    return this.append(identifier(property));
  }

  /** Adds a type-cast segment, such as `tm1.NativeView`: the namespace-qualified type name. */
  cast(qualifiedType: string): ODataPath {
    return this.append(qualified(qualifiedType));
  }

  /** Adds a bound action segment, such as `tm1.Reserve`: the namespace-qualified action name.
   * Actions are invoked with POST. */
  action(qualifiedName: string): ODataPath {
    return this.append(qualified(qualifiedName));
  }

  /** Adds a bound function call with literal parameters, such as
   * `tm1.GetPartition(Begin=0,End=99)`. Use parameter aliases (in {@link ODataQuery}) for complex
   * or collection parameters. @param qualifiedName the namespace-qualified function name @param
   * parameters the parameter names and values, in order; each value is written as an OData
   * literal, or as the alias's name if it's an {@link ODataPath.Alias} */
  function(qualifiedName: string, parameters?: Tm1Map<unknown>): ODataPath {
    return this.append(call(qualified(qualifiedName), parameters));
  }

  /** Adds `$value`: a property's or media entity's raw value. */
  value(): ODataPath {
    return this.append("$value");
  }

  /** Adds `$count`: the number of items in a collection, as plain text. */
  count(): ODataPath {
    return this.append("$count");
  }

  /** Adds `$ref`: references to entities rather than the entities themselves. */
  ref(): ODataPath {
    return this.append("$ref");
  }

  /** Whether `other` is the same path. */
  equals(other: unknown): boolean {
    return other instanceof ODataPath && other.toString() === this.toString();
  }

  /** The encoded path, without a leading slash, such as `Cubes('Sales')/Views`. */
  toString(): string {
    return this.segments.join("/");
  }

  /** The encoded path, where a path is written into JSON: an `@odata.bind` reference. */
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

/** The parameter alias of {@link ODataPath}, nested as in Java. */
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
