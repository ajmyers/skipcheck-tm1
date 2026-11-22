import { Group, User } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const GROUPS = ODataPath.of("Groups");

/** Security groups: `/Groups` and their users. A user's membership is set from the user's side,
 * with `UserService.setGroups`.
 *
 * List a group's members:
 * ```ts
 * const members = (await tm1.groups.users("Finance", ODataQuery.none().select(User.Fields.Name)).toArray()).map((u) => u.Name);
 * ```
 * Create a group and add a user to it, from the user's side:
 * ```ts
 * await tm1.groups.create("Planners");
 * const groups = (await tm1.users.groups("jsmith").toArray()).map((g) => g.Name!);
 * await tm1.users.setGroups("jsmith", [...groups, "Planners"]);
 * ```
 */
export class GroupService extends Tm1Service {
  static readonly endpoints = {
    list: ["EntitySet Groups"],
    get: ["EntitySet Groups"],
    create: ["EntitySet Groups"],
    delete: ["EntitySet Groups"],
    users: ["Navigation Group/Users"],
  } as const satisfies Tm1Endpoints<GroupService>;

  /** `GET Groups`, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Group> {
    return this.session.stream(Tm1Request.list(GROUPS, query, Group), options);
  }

  /** `GET Groups('name')`: the group, or a `Tm1NotFoundException` if there's none. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Group> {
    return this.session.execute(Tm1Request.get(groupPath(name), query, Group), options);
  }

  /** `POST Groups`: creates an empty group; returns it as created. */
  create(name: string, options?: Tm1CallOptions): Promise<Group> {
    return this.session.execute(Tm1Request.post(GROUPS, { Name: required(name, "name") }, Group), options);
  }

  /** `DELETE Groups('name')`: deletes the group; its users stay, without it. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(groupPath(name)), options);
  }

  /** `GET Groups('name')/Users`, streamed. */
  users(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<User> {
    return this.session.stream(Tm1Request.list(groupPath(name).nav("Users"), query, User), options);
  }
}

function groupPath(name: string): ODataPath {
  return GROUPS.key(required(name, "name"));
}
