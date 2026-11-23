// SPDX-License-Identifier: Apache-2.0

import { Session, Thread, Tm1Operations } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions, Tm1Session } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import type { Tm1Endpoints } from "../service.js";

const THREADS = ODataPath.of("Threads");

/** Server threads: what the server is doing right now (`/Threads`), and cancelling an operation,
 * which is how to stop a running process: cancelling a client's request (aborting its signal)
 * doesn't.
 *
 * See what the server is busy with:
 * ```ts
 * const busy = await tm1.threads.list(ODataQuery.none().filter(Thread.Fields.State.ne("Idle"))).toArray();
 * ```
 * Stop a running process by cancelling its thread, found by its request:
 * ```ts
 * const thread = await tm1.threads.list().filter((t) => t.Function?.includes("Processes('Load')/tm1.ExecuteWithReturn") ?? false).first();
 * if (thread) await tm1.threads.cancel(thread.ID!);
 * ```
 *
 * Unlike the other services, it isn't a `Tm1Service`: Java's `session(id, query)` method would clash
 * with that class's `session` property, so it keeps its session to itself.
 */
export class ThreadService {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Threads"],
    get: ["EntitySet Threads"],
    session: ["Navigation Thread/Session"],
    cancel: ["Action Thread/CancelOperation"],
  } as const satisfies Tm1Endpoints<ThreadService>;

  readonly #session: Tm1Session;

  /** A service sending its requests through `session`. */
  constructor(session: Tm1Session) {
    if (!session) throw new TypeError("session is required");
    this.#session = session;
  }

  /** `GET Threads`, streamed. A process run through REST shows as its request: `Function` is
   * `POST /api/v1/Processes('name')/tm1.ExecuteWithReturn`, and TM1 (11.8) leaves `ObjectName`
   * empty. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Thread> {
    return this.#session.stream(Tm1Request.list(THREADS, query, Thread), options);
  }

  /** `GET Threads(id)`: the thread, or a `Tm1NotFoundException` if it's gone. */
  get(id: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Thread> {
    return this.#session.execute(Tm1Request.get(threadPath(id), query, Thread), options);
  }

  /** `GET Threads(id)/Session`: the thread's session. */
  session(id: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Session> {
    return this.#session.execute(Tm1Request.get(threadPath(id).nav("Session"), query, Session), options);
  }

  /** `tm1.CancelOperation`: asks the thread to stop what it's doing, such as a running process. TM1
   * (11.8) then fails the process's `ExecuteWithReturn` request with 500, `TM1UserException: Cancel`
   * (code 174), rather than reporting it `Aborted`. Needs schema 10.2.2.1. Resolves when the request
   * is made; the thread stops soon after. */
  cancel(id: number, options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.ThreadCancelOperation;
    return this.#session.execute(Tm1Request.post(threadPath(id).action(operation.name), {}).requires(operation.capability()), options);
  }
}

function threadPath(id: number): ODataPath {
  return THREADS.key(required(id, "id"));
}
