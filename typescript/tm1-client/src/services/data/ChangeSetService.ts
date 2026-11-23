import { Tm1Operations } from "../../generated/index.js";
import { Json } from "../../model.js";
import { ODataPath } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions, Tm1Session } from "../../session/session.js";
import { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** What {@link ChangeSetService.within}'s work gives: items to stream (Java's `Flux`), or a
 * promise of one (a `Mono`; `undefined`, as from `Promise<void>`, is none). */
export type Tm1Work<R> = AsyncIterable<R> | Iterable<R> | PromiseLike<R | undefined | void>;

/** Change sets: groups of cell writes that can be undone together.
 *
 * A write joins a change set through TM1's `!ChangeSet` query option, which a session view from
 * `Tm1Session.withChangeSet` adds to every request (`$metadata` doesn't say how; TM1 11.8 does it
 * this way). {@link within} does it all: begins a change set, gives the work a session that records
 * into it, and ends it however the work finishes.
 *
 * Make some writes in a change set, keeping its ID, and undo them later:
 * ```ts
 * let id = "";
 * await tm1.changeSets.within(async (session) => {
 *   id = session.changeSet()!;
 *   await Tm1Client.of(session).cells.update("Sales", { Region: "North", Measure: "Amount" }, 42);
 * }).toArray();
 * await tm1.changeSets.undo(id);
 * ```
 */
export class ChangeSetService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    begin: ["ActionImport BeginChangeSet"],
    end: ["ActionImport EndChangeSet"],
    undo: ["ActionImport UndoChangeSet"],
    within: ["ActionImport BeginChangeSet", "ActionImport EndChangeSet"],
  } as const satisfies Tm1Endpoints<ChangeSetService>;

  /** `BeginChangeSet`: starts a change set, and gives its ID, for `Tm1Session.withChangeSet`. Needs
   * schema 10.2.2.6. */
  async begin(options?: Tm1CallOptions): Promise<string> {
    // A primitive result comes wrapped, as {"value": "3"}
    const value = valueOf(await this.session.execute(request(Tm1Operations.BeginChangeSet, {}), options));
    return value == null ? "" : String(value);
  }

  /** `EndChangeSet`: stops recording into a change set; it can still be undone. Gives TM1's return
   * code. Needs schema 10.2.2.6. */
  async end(id: string, options?: Tm1CallOptions): Promise<number> {
    const value = Number(valueOf(await this.session.execute(request(Tm1Operations.EndChangeSet, { ChangeSetID: required(id, "id") }), options)));
    return Number.isFinite(value) ? value : 0;
  }

  /** `UndoChangeSet`: reverts every write recorded in a change set. Needs schema 10.2.2.6. */
  undo(id: string, options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.UndoChangeSet;
    return this.session.execute(
      Tm1Request.post(ODataPath.of(operation.importName!), { ChangeSetID: required(id, "id") }).requires(operation.capability()),
      options,
    );
  }

  /** Runs `work` in a new change set: begins it, gives the work a view of the session that records
   * into it (`session.changeSet()` has the ID, for a later {@link undo}), and ends it when the work
   * completes, fails or is abandoned. Streamed, as Java's `Flux`: nothing runs until it's iterated
   * (`await …toArray()`), and it gives what the work gives, item by item. */
  within<R>(work: (session: Tm1Session) => Tm1Work<R>, options?: Tm1CallOptions): Tm1Stream<R> {
    required(work, "work");
    const self = this;
    return Tm1Stream.from(async function* () {
      const id = await self.begin(options);
      try {
        yield* items(work(self.session.withChangeSet(id)));
      } finally {
        // However the work ended: not under a signal that may be what ended it
        await self.end(id);
      }
    });
  }
}

/** @internal The items of a work's result: a stream's, or a promise's value, if any. */
export async function* items<R>(result: Tm1Work<R>): AsyncGenerator<R> {
  const object = result !== null && (typeof result === "object" || typeof result === "function");
  if (object && typeof (result as PromiseLike<R>).then === "function") {
    const value = await (result as PromiseLike<R | undefined | void>);
    if (value !== undefined) yield value as R;
    return;
  }
  if (object && (Symbol.asyncIterator in result || Symbol.iterator in result)) {
    yield* result as AsyncIterable<R> | Iterable<R>;
    return;
  }
  throw new TypeError("the work gives a promise or an iterable");
}

function valueOf(result: unknown): unknown {
  return result !== null && typeof result === "object" && "value" in result ? (result as { value: unknown }).value : result;
}

function request(operation: Tm1Operation, body: Record<string, unknown>): Tm1Request<unknown> {
  return Tm1Request.post(ODataPath.of(operation.importName!), body, Json).requires(operation.capability());
}
