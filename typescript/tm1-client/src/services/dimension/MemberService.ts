// SPDX-License-Identifier: Apache-2.0

import { Element, Hierarchy, Level, Member } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Members: `/Dimensions('d')/Hierarchies('h')/Members`, the MDX view of a hierarchy's elements, and
 * a member's parent, children, element, level and hierarchy. Read only: members change through their
 * elements.
 *
 * Read a member's unique name, to use in MDX:
 * ```ts
 * const unique = (await tm1.members.get("Region", "Region", "West", ODataQuery.none().select(Member.Fields.UniqueName))).UniqueName;
 * ```
 * Read a member's children:
 * ```ts
 * const children = await tm1.members.children("Region", "Region", "Total", ODataQuery.none().select(Member.Fields.Name)).toArray();
 * ```
 * Read a member's parent, which a root doesn't have:
 * ```ts
 * const parent = await tm1.members.parent("Region", "Region", "West"); // undefined for a root
 * ```
 */
export class MemberService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["Navigation Hierarchy/Members"],
    get: ["Navigation Hierarchy/Members"],
    parent: ["Navigation Member/Parent"],
    children: ["Navigation Member/Children"],
    element: ["Navigation Member/Element"],
    level: ["Navigation Member/Level"],
    hierarchy: ["Navigation Member/Hierarchy"],
  } as const satisfies Tm1Endpoints<MemberService>;

  /** `GET …/Hierarchies('h')/Members`, streamed. */
  list(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(membersPath(dimension, hierarchy), query, Member), options);
  }

  /** `GET …/Members('m')`: the member, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Member> {
    return this.session.execute(Tm1Request.get(memberPath(dimension, hierarchy, member), query, Member), options);
  }

  /** `GET …/Members('m')/Parent`: the member's parent (a member with several parents has the first),
   * or undefined for a root, for which TM1 sends no content (Java's empty `Mono`). */
  parent(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Member | undefined> {
    return this.session.execute<Member | undefined>(Tm1Request.get(memberPath(dimension, hierarchy, member).nav("Parent"), query, Member), options);
  }

  /** `GET …/Members('m')/Children`, streamed: the member's children, in order. */
  children(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(memberPath(dimension, hierarchy, member).nav("Children"), query, Member), options);
  }

  /** `GET …/Members('m')/Element`: the element the member stands for. */
  element(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Element> {
    return this.session.execute(Tm1Request.get(memberPath(dimension, hierarchy, member).nav("Element"), query, Element), options);
  }

  /** `GET …/Members('m')/Level`: the member's level. */
  level(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Level> {
    return this.session.execute(Tm1Request.get(memberPath(dimension, hierarchy, member).nav("Level"), query, Level), options);
  }

  /** `GET …/Members('m')/Hierarchy`: the member's hierarchy. */
  hierarchy(dimension: string, hierarchy: string, member: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(memberPath(dimension, hierarchy, member).nav("Hierarchy"), query, Hierarchy), options);
  }
}

function membersPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy")).nav("Members");
}

function memberPath(dimension: string, hierarchy: string, member: string): ODataPath {
  return membersPath(dimension, hierarchy).key(required(member, "member"));
}
