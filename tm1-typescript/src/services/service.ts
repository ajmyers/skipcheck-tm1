import type { Tm1Session } from "../session/session.js";

/** The endpoints of the REST API each method implements, as coverage-gate IDs (`EntitySet Cubes`,
 * `Action Cube/Reserve`): the Java client's `@Tm1Endpoint`, as a table on each service class
 * (`CubeService.endpoints`). The coverage gate counts them. */
export type Tm1Endpoints<S> = { readonly [K in keyof S]?: readonly string[] };

/** What every service is: methods over one session. A client over a view of the session (a
 * sandbox, a batch) makes services over that view. */
export abstract class Tm1Service {
  constructor(
    /** The session the requests go through. */
    protected readonly session: Tm1Session,
  ) {
    if (!session) throw new TypeError("session is required");
  }
}
