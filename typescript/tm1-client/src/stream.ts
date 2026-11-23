/** A collection TM1 sends, item by item, fetching pages as they're needed (Java's `Flux`):
 * `for await (const cube of tm1.cubes.list())`, or `await tm1.cubes.list().toArray()`. Nothing is
 * sent until it's iterated, except in a batch, where the request joins the batch at once; each
 * iteration sends it again. */
export class Tm1Stream<T> implements AsyncIterable<T> {
  /** A stream that gets a new iterator from `source` each time it's iterated. */
  constructor(private readonly source: () => AsyncIterator<T>) {}

  /** A stream of items already at hand. */
  static of<T>(...items: T[]): Tm1Stream<T> {
    return new Tm1Stream(async function* () {
      yield* items;
    });
  }

  /** A stream from an async generator function. */
  static from<T>(generator: () => AsyncIterable<T>): Tm1Stream<T> {
    return new Tm1Stream(() => generator()[Symbol.asyncIterator]());
  }

  /** Starts the stream: what `for await` calls. */
  [Symbol.asyncIterator](): AsyncIterator<T> {
    return this.source();
  }

  /** Every item, once the last page has arrived. */
  async toArray(): Promise<T[]> {
    const out: T[] = [];
    for await (const item of this) out.push(item);
    return out;
  }

  /** The first item, or undefined; fetches no further. */
  async first(): Promise<T | undefined> {
    for await (const item of this) return item;
    return undefined;
  }

  /** A stream of each item passed through `fn`. */
  map<U>(fn: (item: T) => U): Tm1Stream<U> {
    const self = this;
    return Tm1Stream.from(async function* () {
      for await (const item of self) yield fn(item);
    });
  }

  /** A stream of the items `predicate` accepts. */
  filter(predicate: (item: T) => boolean): Tm1Stream<T> {
    const self = this;
    return Tm1Stream.from(async function* () {
      for await (const item of self) if (predicate(item)) yield item;
    });
  }
}
