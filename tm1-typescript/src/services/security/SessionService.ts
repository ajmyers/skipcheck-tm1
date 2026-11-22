import { Session, Thread, Tm1Operations, User } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const SESSIONS = ODataPath.of("Sessions");

/** Server sessions: every session logged in to the server (`/Sessions`), this one's
 * (`ActiveSession`), their users and threads, and closing another session.
 *
 * To close this client's own session, close the `Tm1Session` or `Tm1Client`: it sends
 * `ActiveSession/tm1.Close`, and knows not to log in again. A closed session stays listed in
 * `Sessions` for a few seconds (5 to 9 in TM1 11.8).
 *
 * List who's logged in, expanding each session's user:
 * ```ts
 * for await (const s of tm1.sessions.list(ODataQuery.none().expand("User"))) console.log(s.ID, s.User?.Name);
 * ```
 * Close other clients' sessions by their label, sparing this one:
 * ```ts
 * const mine = (await tm1.sessions.activeSession()).ID;
 * for await (const s of tm1.sessions.list(ODataQuery.none().filter(Session.Fields.Context.eq("loader")))) {
 *   if (s.ID !== mine) await tm1.sessions.close(s.ID!);
 * }
 * ```
 */
export class SessionService extends Tm1Service {
  static readonly endpoints = {
    list: ["EntitySet Sessions"],
    get: ["EntitySet Sessions"],
    activeSession: ["Singleton ActiveSession"],
    user: ["Navigation Session/User"],
    threads: ["Navigation Session/Threads"],
    close: ["Action Session/Close"],
  } as const satisfies Tm1Endpoints<SessionService>;

  /** `GET Sessions`, streamed: every session on the server, for an admin. Expand `User` or
   * `Threads` with the query. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Session> {
    return this.session.stream(Tm1Request.list(SESSIONS, query, Session), options);
  }

  /** `GET Sessions(id)`: the session, or a `Tm1NotFoundException` if there's none. */
  get(id: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Session> {
    return this.session.execute(Tm1Request.get(sessionPath(id), query, Session), options);
  }

  /** `GET ActiveSession`: this client's own session on the server. */
  activeSession(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Session> {
    return this.session.execute(Tm1Request.get(ODataPath.of("ActiveSession"), query, Session), options);
  }

  /** `GET Sessions(id)/User`: the session's user. */
  user(id: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<User> {
    return this.session.execute(Tm1Request.get(sessionPath(id).nav("User"), query, User), options);
  }

  /** `GET Sessions(id)/Threads`, streamed: what the session is running now. */
  threads(id: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Thread> {
    return this.session.stream(Tm1Request.list(sessionPath(id).nav("Threads"), query, Thread), options);
  }

  /** `tm1.Close`: ends another session; its client must log in again. Needs schema 10.2.2.5. */
  close(id: number, options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.SessionClose;
    return this.session.execute(Tm1Request.post(sessionPath(id).action(operation.name), {}).requires(operation.capability()), options);
  }
}

function sessionPath(id: number): ODataPath {
  return SESSIONS.key(required(id, "id"));
}
