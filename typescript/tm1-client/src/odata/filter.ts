// SPDX-License-Identifier: Apache-2.0

import { ODataLiterals } from "./literals.js";

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** A property path, for queries and filters: the generated models have one per property,
 * `Cube.Fields.Name` (the Java model's `Cube.NAME`). Comparisons make filters:
 * `Cube.Fields.Name.eq("Sales")` is `(Name eq 'Sales')`. */
export class ODataProperty {
  private constructor(readonly path: string) {}

  /** A property path from text, with `/` between segments. */
  static of(path: string): ODataProperty {
    if (typeof path !== "string" || path.length === 0) throw new TypeError("a property path is a non-empty string");
    return new ODataProperty(path);
  }

  /** `this eq value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  eq(value: unknown): Filter {
    return Filter.compare(this, "eq", value);
  }

  /** `this ne value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  ne(value: unknown): Filter {
    return Filter.compare(this, "ne", value);
  }

  /** `this gt value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  gt(value: unknown): Filter {
    return Filter.compare(this, "gt", value);
  }

  /** `this ge value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  ge(value: unknown): Filter {
    return Filter.compare(this, "ge", value);
  }

  /** `this lt value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  lt(value: unknown): Filter {
    return Filter.compare(this, "lt", value);
  }

  /** `this le value`. The value is written as an OData literal ({@link ODataLiterals.of}); null
   * isn't allowed, use {@link isNull}. */
  le(value: unknown): Filter {
    return Filter.compare(this, "le", value);
  }

  /** `this eq null`. */
  isNull(): Filter {
    return Filter.compare(this, "eq", null);
  }

  /** Whether `other` is the same property path. */
  equals(other: unknown): boolean {
    return other instanceof ODataProperty && other.path === this.path;
  }

  /** The path, as it's written in a query. */
  toString(): string {
    return this.path;
  }
}

/** A `$filter` expression, built from {@link ODataProperty} comparisons and the canonical functions
 * TM1 supports, as the Java client's `Filter`. Every binary expression is parenthesized, so
 * precedence never depends on how a filter was composed. TM1 compares names in `$filter` case- and
 * space-sensitively, while keyed access ignores both: to find one object by name, use its key. */
export class Filter {
  private constructor(private readonly expression: string) {}

  /** An expression written by hand, used as is. */
  static raw(expression: string): Filter {
    if (typeof expression !== "string") throw new TypeError("a filter expression is a string");
    return new Filter(expression);
  }

  /** All of the given filters, at least one: joined with `and`, or the filter itself if there's
   * only one. */
  static and(...filters: Filter[]): Filter {
    return Filter.join("and", filters);
  }

  /** Any of the given filters, at least one: joined with `or`, or the filter itself if there's only
   * one. */
  static or(...filters: Filter[]): Filter {
    return Filter.join("or", filters);
  }

  /** `not filter`. */
  static not(filter: Filter): Filter {
    return new Filter(`not(${filter.expression})`);
  }

  /** `startswith(property,'prefix')`: the string property must start with the text. */
  static startsWith(property: ODataProperty, prefix: string): Filter {
    return Filter.fn("startswith", property, prefix);
  }

  /** `endswith(property,'suffix')`: the string property must end with the text. */
  static endsWith(property: ODataProperty, suffix: string): Filter {
    return Filter.fn("endswith", property, suffix);
  }

  /** `contains(property,'text')`: the string property must contain the text. */
  static contains(property: ODataProperty, text: string): Filter {
    return Filter.fn("contains", property, text);
  }

  /** `collection/any(v:condition)`: true if any member of a collection matches. The callback
   * receives the range variable, from which to build paths (`v.nav("Name")`). @param
   * collectionPath the path to the collection, such as `Hierarchies`, used as is @param variable
   * the range variable's name, an OData identifier @param condition builds the condition from the
   * range variable */
  static any(collectionPath: string, variable: string, condition: (v: Filter.Variable) => Filter): Filter {
    return Filter.lambda("any", collectionPath, variable, condition);
  }

  /** `collection/all(v:condition)`: true if every member of a collection matches. @param
   * collectionPath the path to the collection, such as `Hierarchies`, used as is @param variable
   * the range variable's name, an OData identifier @param condition builds the condition from the
   * range variable */
  static all(collectionPath: string, variable: string, condition: (v: Filter.Variable) => Filter): Filter {
    return Filter.lambda("all", collectionPath, variable, condition);
  }

  /** `property in (a,b,c)`, with at least one value, each written as an OData literal ({@link
   * ODataLiterals.of}). Not every TM1 version supports `in`; {@link Filter.or} of `eq`s always
   * works. */
  static in(property: ODataProperty, ...values: unknown[]): Filter {
    if (values.length === 0) throw new RangeError("in() needs at least one value: an empty set matches nothing");
    return new Filter(`(${property.path} in (${values.map((v) => ODataLiterals.of(v)).join(",")}))`);
  }

  /** @internal */
  static compare(property: ODataProperty, op: string, value: unknown): Filter {
    return new Filter(`(${property.path} ${op} ${ODataLiterals.of(value)})`);
  }

  /** This filter and the others. */
  and(...others: Filter[]): Filter {
    return Filter.and(this, ...others);
  }

  /** This filter or any of the others, joined with `or`. */
  or(...others: Filter[]): Filter {
    return Filter.or(this, ...others);
  }

  /** Whether `other` is the same expression. */
  equals(other: unknown): boolean {
    return other instanceof Filter && other.expression === this.expression;
  }

  /** The expression, unencoded, such as `(Name eq 'Sales')`. */
  toString(): string {
    return this.expression;
  }

  private static join(op: string, filters: Filter[]): Filter {
    if (filters.length === 0) throw new RangeError(`${op}() needs at least one filter`);
    return filters.length === 1 ? filters[0]! : new Filter("(" + filters.map((f) => f.expression).join(` ${op} `) + ")");
  }

  private static fn(name: string, property: ODataProperty, argument: string): Filter {
    return new Filter(`${name}(${property.path},${ODataLiterals.string(argument)})`);
  }

  private static lambda(kind: string, collectionPath: string, variable: string, condition: (v: Filter.Variable) => Filter): Filter {
    if (!IDENTIFIER.test(variable)) throw new RangeError("Not a valid range variable: " + variable);
    return new Filter(`${collectionPath}/${kind}(${variable}:${condition(new Filter.Variable(variable)).expression})`);
  }
}

/** The range variable of {@link Filter.any} and {@link Filter.all}, nested as in Java. */
export namespace Filter {
  /** The range variable of {@link Filter.any} and {@link Filter.all}. */
  export class Variable {
    /** @internal */
    constructor(private readonly name: string) {}

    nav(path: string): ODataProperty {
      return ODataProperty.of(`${this.name}/${path}`);
    }

    self(): ODataProperty {
      return ODataProperty.of(this.name);
    }
  }
}
