/** A map argument: a `Map` (which keeps its order whatever the keys) or a plain object (which
 * puts integer-like keys such as `"2024"` first, as JavaScript objects do). Where order matters,
 * such as a tuple's dimensions, pass a `Map` if a key may look like an integer. */
export type Tm1Map<V> = ReadonlyMap<string, V> | Readonly<Record<string, V>>;

/** @internal */
export function entriesOf<V>(map: Tm1Map<V> | null | undefined): [string, V][] {
  if (map == null) return [];
  if (map instanceof Map) return [...map.entries()];
  return Object.entries(map as Record<string, V>);
}

/** @internal */
export function required<T>(value: T | null | undefined, name: string): T {
  if (value === null || value === undefined) throw new TypeError(`${name} is required`);
  return value;
}

/** @internal A promise that settles after `ms`, or rejects when `signal` aborts. */
export function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = (): void => {
      clearTimeout(timer);
      reject(signal!.reason);
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}
