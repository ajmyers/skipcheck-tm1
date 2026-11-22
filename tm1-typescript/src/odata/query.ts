import { ODataEncoding } from "./encoding.js";
import { Filter, ODataProperty } from "./filter.js";
import type { ODataPath } from "./path.js";

const CUSTOM = /^[A-Za-z!][A-Za-z0-9_\-.!]*$/;

type Pair = readonly [string, string];

function pathOf(p: string | ODataProperty): string {
  return p instanceof ODataProperty ? p.path : p;
}

/** OData query options: `$select`, `$expand` (with nested options), `$filter`, `$orderby`, `$top`,
 * `$skip`, `$count`, custom options such as TM1's `!sandbox`, and parameter aliases. Immutable:
 * each method returns a new query, as the Java client's `ODataQuery`:
 * ```ts
 * ODataQuery.none().select("Name", "Type").expand("Hierarchies", (h) => h.select("Name").top(5))
 * ```
 * Options are written in a fixed order, and each value is percent-encoded once by
 * {@link toQueryString}. Nested options inside `$expand` are separated by `;`. */
export class ODataQuery {
  private static readonly EMPTY = new ODataQuery();

  private selectList: readonly string[] = [];
  private expandList: readonly (readonly [string, ODataQuery])[] = [];
  private filterText: string | undefined;
  private orderByList: readonly string[] = [];
  private topValue: number | undefined;
  private skipValue: number | undefined;
  private countValue: boolean | undefined;
  private customList: readonly Pair[] = [];
  private aliasList: readonly Pair[] = [];

  private constructor() {}

  /** No options: the server's default response. */
  static none(): ODataQuery {
    return ODataQuery.EMPTY;
  }

  select(...properties: (string | ODataProperty)[]): ODataQuery {
    return this.with((q) => (q.selectList = [...this.selectList, ...properties.map(pathOf)]));
  }

  /** Expands a navigation property, with nested options applied to what's there for it already. */
  expand(navigation: string | ODataProperty, options: (q: ODataQuery) => ODataQuery = (q) => q): ODataQuery {
    const name = pathOf(navigation);
    const index = this.expandList.findIndex(([n]) => n === name);
    const current = index < 0 ? ODataQuery.EMPTY : this.expandList[index]![1];
    const item = [name, options(current)] as const;
    const next = [...this.expandList];
    if (index < 0) next.push(item);
    else next[index] = item;
    return this.with((q) => (q.expandList = next));
  }

  filter(filter: Filter): ODataQuery {
    if (!(filter instanceof Filter)) throw new TypeError("filter() takes a Filter (Filter.raw for a hand-written one)");
    return this.with((q) => (q.filterText = filter.toString()));
  }

  orderBy(property: string | ODataProperty): ODataQuery {
    return this.with((q) => (q.orderByList = [...this.orderByList, pathOf(property)]));
  }

  orderByDescending(property: string | ODataProperty): ODataQuery {
    return this.with((q) => (q.orderByList = [...this.orderByList, pathOf(property) + " desc"]));
  }

  top(n: number): ODataQuery {
    if (!Number.isInteger(n) || n < 0) throw new RangeError("$top must be a non-negative integer: " + n);
    return this.with((q) => (q.topValue = n));
  }

  skip(n: number): ODataQuery {
    if (!Number.isInteger(n) || n < 0) throw new RangeError("$skip must be a non-negative integer: " + n);
    return this.with((q) => (q.skipValue = n));
  }

  withCount(): ODataQuery {
    return this.with((q) => (q.countValue = true));
  }

  /** With a value, sets a custom option, such as TM1's `!sandbox`; without, reads it (or undefined). */
  option(name: string): string | undefined;
  option(name: string, value: string): ODataQuery;
  option(name: string, value?: string): ODataQuery | string | undefined {
    if (value === undefined) return this.customList.find(([n]) => n === name)?.[1];
    if (typeof name !== "string" || !CUSTOM.test(name)) throw new RangeError("Not a valid custom query option name: " + name);
    return this.with((q) => (q.customList = [...this.customList.filter(([n]) => n !== name), [name, value]]));
  }

  /** A parameter alias's value: `Fn(p=@p)` with `?@p=…`. */
  alias(alias: ODataPath.Alias, literalOrJson: string): ODataQuery {
    if (typeof literalOrJson !== "string") throw new TypeError("an alias's value is a literal or JSON text");
    return this.with((q) => (q.aliasList = [...this.aliasList.filter(([n]) => n !== alias.name), [alias.name, literalOrJson]]));
  }

  isEmpty(): boolean {
    return this.systemOptions().length === 0 && this.customList.length === 0 && this.aliasList.length === 0;
  }

  /** @internal */
  customOptions(): readonly Pair[] {
    return this.customList;
  }

  /** Every option, unencoded, in order. */
  parameters(): Pair[] {
    return [...this.systemOptions(), ...this.customList, ...this.aliasList];
  }

  /** The encoded query string, without `?`. */
  toQueryString(): string {
    return this.parameters()
      .map(([k, v]) => k + "=" + ODataEncoding.queryValue(v))
      .join("&");
  }

  equals(other: unknown): boolean {
    return other instanceof ODataQuery && other.toQueryString() === this.toQueryString();
  }

  toString(): string {
    return this.parameters()
      .map(([k, v]) => k + "=" + v)
      .join("&");
  }

  private with(change: (q: ODataQuery) => void): ODataQuery {
    const copy = Object.assign(Object.create(ODataQuery.prototype) as ODataQuery, this);
    change(copy);
    return copy;
  }

  private systemOptions(): Pair[] {
    const options: Pair[] = [];
    if (this.selectList.length) options.push(["$select", this.selectList.join(",")]);
    if (this.expandList.length) {
      options.push([
        "$expand",
        this.expandList
          .map(([name, q]) => {
            const inner = q.systemOptions();
            return inner.length === 0 ? name : name + "(" + inner.map(([k, v]) => k + "=" + v).join(";") + ")";
          })
          .join(","),
      ]);
    }
    if (this.filterText !== undefined) options.push(["$filter", this.filterText]);
    if (this.orderByList.length) options.push(["$orderby", this.orderByList.join(",")]);
    if (this.topValue !== undefined) options.push(["$top", String(this.topValue)]);
    if (this.skipValue !== undefined) options.push(["$skip", String(this.skipValue)]);
    if (this.countValue !== undefined) options.push(["$count", this.countValue ? "true" : "false"]);
    return options;
  }
}
