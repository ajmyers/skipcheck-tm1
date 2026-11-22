import type { ODataPath } from "./odata/index.js";

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

  equals(other: unknown): boolean {
    return other instanceof Tm1Patch && other.constructor === this.constructor && JSON.stringify(other) === JSON.stringify(this);
  }

  toString(): string {
    return `${this.constructor.name}${JSON.stringify(this)}`;
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
    this.#values.set(name + "@odata.bind", targets.map((t) => t.toString()));
    return this;
  }

  /** The patch, with what's been set so far. */
  build(): P {
    return this.#make(this.#values);
  }
}
