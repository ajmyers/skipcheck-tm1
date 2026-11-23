import { CellsetAxis, Tm1Operations } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const MEMBER_SETS = ODataPath.of("MemberSets");

/** Member sets: the results of MDX set expressions, as a single axis of tuples, kept on the server in
 * `/MemberSets` and keyed by an ordinal TM1 assigns (1, 2, … in the session).
 *
 * Like a cellset, a member set stays until it's deleted or the session ends. TM1 (11.8) lists none,
 * though each reads by its ordinal: keep the ordinals {@link execute} returns, and delete what you're
 * done with.
 *
 * Evaluate a set expression and read its members' names, deleting the set after:
 * ```ts
 * const set = await tm1.memberSets.execute("{TM1SUBSETALL([Region].[Region])}",
 *     ODataQuery.none().expand("Tuples", (t) => t.expand("Members", (m) => m.select("Name"))));
 * try {
 *   const names = (set.Tuples ?? []).flatMap((t) => (t.Members ?? []).map((m) => m.Name));
 * } finally {
 *   await tm1.memberSets.delete(set.Ordinal!);
 * }
 * ```
 * Keep a set on the server to read later, by the ordinal TM1 gives it:
 * ```ts
 * const ordinal = (await tm1.memberSets.execute("{[Region].[Region].Members}")).Ordinal!;
 * const later = await tm1.memberSets.get(ordinal, ODataQuery.none().expand("Tuples", (t) => t.expand("Members")));
 * ```
 */
export class MemberSetService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    execute: ["ActionImport ExecuteMDXSetExpression"],
    list: ["EntitySet MemberSets"],
    get: ["EntitySet MemberSets"],
    delete: ["EntitySet MemberSets"],
  } as const satisfies Tm1Endpoints<MemberSetService>;

  /** `ExecuteMDXSetExpression`: evaluates an MDX set expression, such as
   * `{TM1SUBSETALL([Region].[Region])}`, keeping the result as a member set. Expand
   * `Tuples($expand=Members($select=Name))` to get its members in the same round trip. Needs schema
   * 11.1.0. Returns the set, with the `Ordinal` that keys it in `MemberSets`. */
  execute(mdx: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<CellsetAxis> {
    const operation = Tm1Operations.ExecuteMdxSetExpression;
    const request = Tm1Request.post(ODataPath.of(required(operation.importName, "importName")), { MDX: required(mdx, "mdx") }, CellsetAxis)
      .withQuery(query)
      .requires(operation.capability());
    return this.session.execute(request, options);
  }

  /** `GET MemberSets`, streamed. TM1 (11.8) lists none, though each reads by its ordinal with {@link get}. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<CellsetAxis> {
    return this.session.stream(Tm1Request.list(MEMBER_SETS, query, CellsetAxis), options);
  }

  /** `GET MemberSets(n)`: the set with the ordinal {@link execute} gave it, or a
   * `Tm1NotFoundException` if it's gone. */
  get(ordinal: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<CellsetAxis> {
    return this.session.execute(Tm1Request.get(memberSetPath(ordinal), query, CellsetAxis), options);
  }

  /** `DELETE MemberSets(n)`: frees the set on the server. */
  delete(ordinal: number, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(memberSetPath(ordinal)), options);
  }
}

function memberSetPath(ordinal: number): ODataPath {
  if (!Number.isInteger(ordinal)) throw new RangeError("A member set's ordinal is an integer: " + String(ordinal));
  return MEMBER_SETS.key(ordinal);
}
