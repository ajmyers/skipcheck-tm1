/** Objects the image puts into an empty database on first start (its `TM1_SEED` setting), and the
 * login that goes with them, as the Java enum `Tm1Seed`. The samples come from the TM1 install
 * itself, so they're only available where the install includes them. */
export class Tm1Seed {
  /** No objects: an empty database with the login `admin` and no password. */
  static readonly EMPTY = new Tm1Seed("EMPTY", "empty", "admin", "");

  /** IBM's SData sample, with the login `admin` / `apple`. */
  static readonly SDATA = new Tm1Seed("SDATA", "sdata", "admin", "apple");

  private constructor(
    /** The constant's name, as Java's `name()`. */
    readonly name: string,
    /** What the image calls it (package-private in Java).
     * @internal */
    readonly value: string,
    /** @internal */
    readonly username: string,
    /** @internal */
    readonly password: string,
  ) {}

  /** Every seed, as Java's `values()`. */
  static values(): Tm1Seed[] {
    return [Tm1Seed.EMPTY, Tm1Seed.SDATA];
  }

  toString(): string {
    return this.name;
  }
}
