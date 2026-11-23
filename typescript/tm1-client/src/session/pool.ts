// SPDX-License-Identifier: Apache-2.0

import { Tm1SessionClosedException } from "../errors/index.js";
import { Tm1Identity } from "./identity.js";
import type { Tm1Session, Tm1SessionBuilder } from "./session.js";

/** The pool's clock and timers, in milliseconds (Java's Reactor `Scheduler`): {@link Tm1Clock.system}
 * by default; tests pass one they move by hand. */
export interface Tm1Clock {
  /** The time now, in milliseconds. */
  now(): number;
  /** Runs `callback` after `ms`; the handle cancels it. */
  setTimeout(callback: () => void, ms: number): unknown;
  /** Cancels a timer {@link setTimeout} started. */
  clearTimeout(handle: unknown): void;
}

/** The clocks at hand: {@link Tm1Clock.system}. */
export const Tm1Clock = {
  /** The real clock. Its timers don't keep a Node process alive (as Java's daemon threads don't). */
  system: {
    now: () => Date.now(),
    setTimeout(callback: () => void, ms: number): unknown {
      const timer = globalThis.setTimeout(callback, ms) as unknown as { unref?: () => void };
      timer.unref?.();
      return timer;
    },
    clearTimeout(handle: unknown): void {
      globalThis.clearTimeout(handle as Parameters<typeof globalThis.clearTimeout>[0]);
    },
  } satisfies Tm1Clock as Tm1Clock,
} as const;

type Reason = "idle" | "age" | "size";

interface Entry {
  readonly identity: Tm1Identity;
  readonly session: Tm1Session;
  readonly createdAt: number;
  lastUsed: number;
}

/** Sessions on one TM1 server, one per {@link Tm1Identity}, for applications that act on behalf of
 * many users, as the Java client's `Tm1SessionPool`. In TM1 identity is the session, so the number
 * of sessions grows with the number of users; the pool keeps that bounded.
 * ```ts
 * const pool = Tm1SessionPool.builder(Tm1Session.builder().baseUrl("http://tm1:8010/api/v1/").basicAuth("service", secret))
 *   .idleTimeout(10 * 60_000)
 *   .build();
 * await pool.session(Tm1Identity.impersonating(user)).execute(request); // each time it's needed
 * ```
 * - **Get a session for each piece of work** and don't keep it: the pool may evict it. Getting one
 *   sends nothing; the session logs in on its first request.
 * - **Eviction** happens when a session has been idle for `idleTimeout`, is older than `maxAge`, or
 *   the pool is over `maxSize` (least recently used first). It's checked on each {@link session}
 *   call and every `sweepInterval`.
 * - **An evicted session is closed after `closeGrace`**, so requests already running on it can
 *   finish. The next request for that identity gets a new session.
 * - **{@link close}** closes every session at once, and the pool refuses new ones.
 * - **Nothing falls back:** a refused identity fails, and the pool never uses another identity in
 *   its place.
 *
 * {@link stats} reports what the pool holds and has done, for metrics. */
export class Tm1SessionPool implements AsyncDisposable {
  /** Sessions in least-recently-used order, by identity key. */
  private readonly entries = new Map<string, Entry>();
  /** Evicted sessions waiting out their grace period: the pending close's timer, or the close once
   * it has started. */
  private readonly closing = new Map<Tm1Session, { timer: unknown; started?: Promise<void> }>();
  private sweepTimer: unknown;
  private closed = false;
  private closeAll: Promise<void> | undefined;
  private created = 0;
  private evictedIdle = 0;
  private evictedAge = 0;
  private evictedSize = 0;
  private closedSessions = 0;

  /** @internal Made by {@link Tm1SessionPoolBuilder.build}. */
  constructor(
    private readonly sessions: Tm1SessionBuilder,
    private readonly idleTimeout: number,
    private readonly maxAge: number,
    private readonly maxSize: number,
    private readonly closeGrace: number,
    private readonly sweepInterval: number,
    private readonly clock: Tm1Clock,
  ) {
    // Sessions built by the pool remember refused logins on the pool's clock
    this.sessions.clock(() => clock.now());
    this.scheduleSweep();
  }

  /** A builder for a pool of sessions with these settings: server, credentials, timeouts and the
   * rest. Each identity's session is built from a copy of them.
   * @param sessions the settings every session starts from; the pool keeps a copy */
  static builder(sessions: Tm1SessionBuilder): Tm1SessionPoolBuilder {
    if (!sessions || typeof sessions.copy !== "function") throw new TypeError("sessions is a Tm1SessionBuilder");
    return new Tm1SessionPoolBuilder(sessions.copy());
  }

  /** The session for `identity` (the pool's own credentials by default): the pool's, or a new one if
   * it has none (or the one it had has expired). Sends nothing: a new session logs in on its first
   * request.
   * @throws Tm1SessionClosedException if the pool is closed */
  session(identity: Tm1Identity = Tm1Identity.serviceAccount()): Tm1Session {
    if (!(identity instanceof Tm1Identity)) throw new TypeError("identity is a Tm1Identity");
    const now = this.clock.now();
    if (this.closed) throw new Tm1SessionClosedException(this.serviceRoot());
    const key = identity.key();
    let entry = this.entries.get(key);
    if (entry) {
      const reason = this.expiry(entry, now);
      if (reason) {
        this.evict(entry, reason);
        entry = undefined;
      }
    }
    if (!entry) {
      entry = { identity, session: identity.applyTo(this.sessions.copy()).build(), createdAt: now, lastUsed: now };
      this.entries.set(key, entry);
      this.created++;
      this.evictOverSize(key);
    } else {
      // Most recently used last
      this.entries.delete(key);
      this.entries.set(key, entry);
    }
    entry.lastUsed = now;
    return entry.session;
  }

  /** Evicts every session that has been idle too long or is too old. Runs every `sweepInterval` on
   * its own; call it to evict now. */
  evictExpired(): void {
    const now = this.clock.now();
    for (const entry of [...this.entries.values()]) {
      const reason = this.expiry(entry, now);
      if (reason) this.evict(entry, reason);
    }
  }

  /** What the pool holds and has done so far, for metrics. */
  stats(): Tm1SessionPool.Stats {
    return {
      held: this.entries.size,
      closing: this.closing.size,
      created: this.created,
      evictedIdle: this.evictedIdle,
      evictedAge: this.evictedAge,
      evictedSize: this.evictedSize,
      closed: this.closedSessions,
    };
  }

  /** Closes every session, including evicted ones still in their grace period, and refuses new
   * ones. Every call returns the same promise; a session that fails to close doesn't fail it. */
  close(): Promise<void> {
    if (!this.closeAll) {
      this.closed = true;
      if (this.sweepTimer !== undefined) this.clock.clearTimeout(this.sweepTimer);
      this.sweepTimer = undefined;
      const all = [...this.entries.values()].map((e) => e.session);
      this.entries.clear();
      // An evicted session whose close has started is counted by that close
      const started: Promise<void>[] = [];
      for (const [session, pending] of this.closing) {
        if (pending.started) {
          started.push(pending.started);
        } else {
          this.clock.clearTimeout(pending.timer);
          all.push(session);
        }
      }
      this.closing.clear();
      this.closeAll = Promise.all([
        ...started,
        ...all.map((s) =>
          s.close().then(
            () => {
              this.closedSessions++;
            },
            () => undefined,
          ),
        ),
      ]).then(() => undefined);
    }
    return this.closeAll;
  }

  /** Closes the pool, for `await using`: the same as {@link close}. */
  [Symbol.asyncDispose](): Promise<void> {
    return this.close();
  }

  /** The pool, its service root and how many sessions it holds, for logs. */
  toString(): string {
    return `Tm1SessionPool[${this.serviceRoot()}, ${this.entries.size} sessions]`;
  }

  private serviceRoot(): string {
    return this.sessions.baseUrlOrUnset();
  }

  /** Why `entry` has expired at `now`, or undefined if it hasn't. */
  private expiry(entry: Entry, now: number): Reason | undefined {
    if (now - entry.createdAt >= this.maxAge) return "age";
    if (now - entry.lastUsed >= this.idleTimeout) return "idle";
    return undefined;
  }

  /** Evicts least recently used sessions until the pool is within its size, never `keep`. */
  private evictOverSize(keep: string): void {
    while (this.entries.size > this.maxSize) {
      const [key, eldest] = this.entries.entries().next().value as [string, Entry];
      if (key === keep) return;
      this.evict(eldest, "size");
    }
  }

  /** Removes a session and closes it after the grace period. */
  private evict(entry: Entry, reason: Reason): void {
    this.entries.delete(entry.identity.key());
    if (reason === "idle") this.evictedIdle++;
    else if (reason === "age") this.evictedAge++;
    else this.evictedSize++;
    const session = entry.session;
    const pending: { timer: unknown; started?: Promise<void> } = { timer: undefined };
    const close = (): void => {
      // Detached on purpose: a failure to close is the session's own, and isn't the caller's
      pending.started = session.close().then(
        () => {
          this.closedSessions++;
          this.closing.delete(session);
        },
        () => {
          this.closing.delete(session);
        },
      );
    };
    this.closing.set(session, pending);
    if (this.closeGrace === 0) close();
    else pending.timer = this.clock.setTimeout(close, this.closeGrace);
  }

  private scheduleSweep(): void {
    this.sweepTimer = this.clock.setTimeout(() => {
      if (this.closed) return;
      try {
        this.evictExpired();
      } catch {
        // The sweep goes on
      }
      this.scheduleSweep();
    }, this.sweepInterval);
  }
}

/** The types nested in {@link Tm1SessionPool}, as in Java: its statistics and its builder. */
export namespace Tm1SessionPool {
  /** What a pool holds and has done (Java's `Tm1SessionPool.Stats`). */
  export interface Stats {
    /** Sessions in the pool now. */
    readonly held: number;
    /** Evicted sessions in their grace period, not yet closed. */
    readonly closing: number;
    /** Sessions created so far. */
    readonly created: number;
    /** Sessions evicted for being idle. */
    readonly evictedIdle: number;
    /** Sessions evicted for their age. */
    readonly evictedAge: number;
    /** Sessions evicted to keep the pool within its size. */
    readonly evictedSize: number;
    /** Sessions closed on TM1 so far. */
    readonly closed: number;
  }
}

function positive(value: number, name: string): number {
  if (typeof value !== "number" || !(value > 0)) throw new RangeError(`${name} must be positive: ${value}`);
  return value;
}

/** Builds a {@link Tm1SessionPool}, as the Java client's `Tm1SessionPool.Builder`. Durations are in
 * milliseconds. */
export class Tm1SessionPoolBuilder {
  private idleTimeoutMs = 10 * 60_000;
  private maxAgeMs = 8 * 60 * 60_000;
  private maxSizeValue = 100;
  private closeGraceMs = 30_000;
  private sweepIntervalMs = 60_000;
  private clockValue: Tm1Clock = Tm1Clock.system;

  /** @internal Made by {@link Tm1SessionPool.builder}. */
  constructor(private readonly sessions: Tm1SessionBuilder) {}

  /** How long a session may go unused before it's evicted. Default 10 minutes. Keep it below TM1's
   * own session timeout, so the pool closes sessions before TM1 drops them. */
  idleTimeout(ms: number): this {
    this.idleTimeoutMs = positive(ms, "idleTimeout");
    return this;
  }

  /** How long a session may live, used or not, before it's evicted. Default 8 hours. A session also
   * picks up security changes, such as new rights, only when it's new. */
  maxAge(ms: number): this {
    this.maxAgeMs = positive(ms, "maxAge");
    return this;
  }

  /** The most sessions the pool holds; beyond it, the least recently used is evicted. Default 100.
   * Evicted sessions in their grace period don't count. */
  maxSize(maxSize: number): this {
    if (!Number.isInteger(maxSize) || maxSize < 1) throw new RangeError("maxSize must be at least 1: " + maxSize);
    this.maxSizeValue = maxSize;
    return this;
  }

  /** How long an evicted session stays open before it's closed, so requests already running on it
   * can finish. Default 30 s; zero closes at once. */
  closeGrace(ms: number): this {
    if (typeof ms !== "number" || !(ms >= 0)) throw new RangeError("closeGrace must not be negative: " + ms);
    this.closeGraceMs = ms;
    return this;
  }

  /** How often the pool looks for expired sessions on its own, besides on each
   * {@link Tm1SessionPool.session} call. Default 1 minute. */
  sweepInterval(ms: number): this {
    this.sweepIntervalMs = positive(ms, "sweepInterval");
    return this;
  }

  /** Where the pool's timers run, and its clock (Java's `scheduler`): {@link Tm1Clock.system}
   * unless set; tests pass one they move by hand. */
  clock(clock: Tm1Clock): this {
    if (!clock || typeof clock.now !== "function" || typeof clock.setTimeout !== "function" || typeof clock.clearTimeout !== "function") {
      throw new TypeError("clock has now(), setTimeout() and clearTimeout()");
    }
    this.clockValue = clock;
    return this;
  }

  /** The pool. It starts its sweep at once, and opens sessions only when asked. */
  build(): Tm1SessionPool {
    return new Tm1SessionPool(this.sessions, this.idleTimeoutMs, this.maxAgeMs, this.maxSizeValue, this.closeGraceMs, this.sweepIntervalMs, this.clockValue);
  }
}
