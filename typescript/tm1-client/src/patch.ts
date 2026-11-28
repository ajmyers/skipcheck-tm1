// SPDX-License-Identifier: Apache-2.0

import type { ODataPath } from "./odata/index.js";
import { writeJson } from "./json.js";

/** A write, as the Java model's patches (`CubePatch`): the properties set, explicit nulls included,
 * and `@odata.bind` references, in the order they were set. A subtype's patch sends its
 * `@odata.type` first. Never send a model read from TM1 back: it carries server-computed properties. */
export abstract class Tm1Patch {
  readonly #values: ReadonlyMap<string, unknown>;

  /** @internal Patches are made by their builders. */
  constructor(values: ReadonlyMap<string, unknown>) {
    this.#values = new Map(values);
  }

  /** The properties set, by their names in TM1's JSON, in the order they were set. */
  values(): [string, unknown][] {
    return [...this.#values.entries()];
  }

  /** The JSON TM1 is sent: nested patches as theirs. */
  toJSON(): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [k, v] of this.#values) out[k] = v;
    return out;
  }

  /** Whether `other` is a patch of the same type that sets the same properties to the same values. */
  equals(other: unknown): boolean {
    return other instanceof Tm1Patch && other.constructor === this.constructor && writeJson(other) === writeJson(this);
  }

  /** The patch's type and the JSON it sends, for logs. */
  toString(): string {
    return `${this.constructor.name}${writeJson(this)}`;
  }
}

/** What every generated builder is made of. */
export abstract class Tm1PatchBuilder<P extends Tm1Patch> {
  readonly #values = new Map<string, unknown>();
  readonly #make: (values: ReadonlyMap<string, unknown>) => P;

  protected constructor(make: (values: ReadonlyMap<string, unknown>) => P, odataType: string | null) {
    this.#make = make;
    if (odataType !== null) this.#values.set("@odata.type", odataType);
  }

  protected set(name: string, value: unknown): this {
    // Map order is first-set order: setting again keeps the place, as Java's LinkedHashMap
    this.#values.set(name, Array.isArray(value) ? [...value] : value);
    return this;
  }

  protected bind(name: string, target: ODataPath): this {
    this.#values.set(name + "@odata.bind", target.toString());
    return this;
  }

  protected bindAll(name: string, targets: readonly ODataPath[]): this {
    this.#values.set(
      name + "@odata.bind",
      targets.map((t) => t.toString()),
    );
    return this;
  }

  /** @internal What `read`, an entity read from TM1, holds of `names`, nulls left out, a structured
   * value (each member of a collection of them) through its converter in `nested`; and, for an open
   * type (`declared` given), its undeclared properties. The generated `builder(read)` calls it. */
  copy(read: object, names: readonly string[], declared: readonly string[] | null, nested: Readonly<Record<string, (value: never) => unknown>> = {}): this {
    const data = read as Record<string, unknown>;
    for (const name of names) {
      const value = data[name];
      if (value === undefined || value === null) continue;
      const convert = nested[name];
      if (!convert) this.set(name, value);
      else this.set(name, Array.isArray(value) ? value.map((v) => convert(v as never)) : convert(value as never));
    }
    if (declared) {
      for (const [name, value] of Object.entries(data)) {
        if (!declared.includes(name) && !name.startsWith("@") && value !== undefined && value !== null) this.set(name, value);
      }
    }
    return this;
  }

  /** Whether a property, or a binding of it, has been set. */
  protected has(name: string): boolean {
    return this.#values.has(name) || this.#values.has(name + "@odata.bind");
  }

  /** The patch, with what's been set so far. */
  build(): P {
    return this.#make(this.#values);
  }
}
