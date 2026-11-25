// SPDX-License-Identifier: Apache-2.0

import { Group, Session, Tm1Operations, User, type UserCreate, type UserPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const USERS = ODataPath.of("Users");

/** Users: `/Users`, the session's own user, their groups, and disconnecting them.
 *
 * Group membership is set whole, with {@link setGroups}: binding `Groups` in a user PATCH replaces
 * them. TM1 (11.8) has no safe way to leave one group: a `DELETE Users('u')/Groups('g')/$ref`,
 * which OData means as "remove the link", *deletes the group*, for everyone. This service never
 * sends it.
 *
 * Create a user in some groups, binding them by path:
 * ```ts
 * const groups = ODataPath.of("Groups");
 * await tm1.users.create(UserCreate.builder().name("jsmith").password("pw").bindGroups(groups.key("Finance"), groups.key("Planners")).build());
 * ```
 * Take a user out of one group by setting the rest:
 * ```ts
 * const rest = (await tm1.users.groups("jsmith").toArray()).map((g) => g.Name!).filter((g) => g !== "Planners");
 * await tm1.users.setGroups("jsmith", rest);
 * ```
 * Lock a user out: disable them, then close their sessions.
 * ```ts
 * await tm1.users.update("jsmith", UserPatch.builder().enabled(false).build());
 * await tm1.users.disconnect("jsmith");
 * ```
 */
export class UserService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Users"],
    get: ["EntitySet Users"],
    activeUser: ["Singleton ActiveUser"],
    create: ["EntitySet Users"],
    update: ["EntitySet Users"],
    setGroups: ["EntitySet Users", "Navigation User/Groups"],
    delete: ["EntitySet Users"],
    groups: ["Navigation User/Groups"],
    sessions: ["Navigation User/Sessions"],
    disconnect: ["Action User/Disconnect"],
  } as const satisfies Tm1Endpoints<UserService>;

  /** `GET Users`, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<User> {
    return this.session.stream(Tm1Request.list(USERS, query, User), options);
  }

  /** `GET Users('name')`: the user, or a `Tm1NotFoundException` if there's none. User names match
   * ignoring case and spaces. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<User> {
    return this.session.execute(Tm1Request.get(userPath(name), query, User), options);
  }

  /** `GET ActiveUser`: the user this session is for. */
  activeUser(query?: ODataQuery | null, options?: Tm1CallOptions): Promise<User> {
    return this.session.execute(Tm1Request.get(ODataPath.of("ActiveUser"), query, User), options);
  }

  /** `POST Users`: creates a user, in the groups the create binds (`UserCreate.builder().bindGroups(…)`).
   * It needs a name, and a password for Basic security; returns the user as created. */
  create(user: UserCreate, options?: Tm1CallOptions): Promise<User> {
    return this.session.execute(Tm1Request.post(USERS, required(user, "user"), User), options);
  }

  /** `PATCH Users('name')`: changes the properties the patch sets, such as `Password` or `Enabled`.
   * TM1 (11.8) sends no ETag for a user, so there's no `If-Match` form. Its groups are
   * {@link setGroups}': a PATCH with them replaces them, so `UserPatch` has no setter for them. A
   * JavaScript caller's patch with them (no compiler to refuse it) throws a `RangeError` at once. */
  update(name: string, patch: UserPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(userPath(name), withoutGroups(patch)), options);
  }

  /** `PATCH Users('name')` binding `Groups`: makes the user's groups exactly these, leaving any not
   * listed. */
  setGroups(name: string, groups: readonly string[], options?: Tm1CallOptions): Promise<void> {
    const bindings = required(groups, "groups").map((group) => ODataPath.of("Groups").key(required(group, "group")).toString());
    return this.session.execute(Tm1Request.patch(userPath(name), { "Groups@odata.bind": bindings }), options);
  }

  /** `DELETE Users('name')`. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(userPath(name)), options);
  }

  /** `GET Users('name')/Groups`, streamed. */
  groups(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Group> {
    return this.session.stream(Tm1Request.list(userPath(name).nav("Groups"), query, Group), options);
  }

  /** `GET Users('name')/Sessions`, streamed: the user's sessions on the server. */
  sessions(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Session> {
    return this.session.stream(Tm1Request.list(userPath(name).nav("Sessions"), query, Session), options);
  }

  /** `tm1.Disconnect`: closes every session the user has. Needs schema 10.2.2.6. */
  disconnect(name: string, options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.UserDisconnect;
    return this.session.execute(Tm1Request.post(userPath(name).action(operation.name), {}).requires(operation.capability()), options);
  }
}

function withoutGroups(patch: UserPatch): UserPatch {
  if (
    required(patch, "patch")
      .values()
      .some(([key]) => key === "Groups" || key.startsWith("Groups@"))
  ) {
    throw new RangeError("A user PATCH with Groups replaces them: use setGroups");
  }
  return patch;
}

function userPath(name: string): ODataPath {
  return USERS.key(required(name, "name"));
}
