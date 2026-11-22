import { Hierarchy, Level, Member } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Levels: `/Dimensions('d')/Hierarchies('h')/Levels`, keyed by number, and their members. Read
 * only: TM1 derives levels from the hierarchy's structure.
 *
 * List a hierarchy's levels, with how many members each has:
 * ```ts
 * tm1.levels.list("Region", "Region", ODataQuery.none().select(Level.Fields.Number, Level.Fields.Cardinality))
 * ```
 * Read a level's members. Levels are numbered from the top, so level 0 holds the roots:
 * ```ts
 * tm1.levels.members("Region", "Region", 0, ODataQuery.none().select(Member.Fields.Name))
 * ```
 */
export class LevelService extends Tm1Service {
  static readonly endpoints = {
    list: ["Navigation Hierarchy/Levels"],
    get: ["Navigation Hierarchy/Levels"],
    members: ["Navigation Level/Members"],
    hierarchy: ["Navigation Level/Hierarchy"],
  } as const satisfies Tm1Endpoints<LevelService>;

  /** `GET …/Hierarchies('h')/Levels`: the hierarchy's levels, streamed. */
  list(dimension: string, hierarchy: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Level> {
    return this.session.stream(Tm1Request.list(levelsPath(dimension, hierarchy), query, Level), options);
  }

  /** `GET …/Levels(n)`: the level numbered `number`, from 0, or a `Tm1NotFoundException` if there's none. */
  get(dimension: string, hierarchy: string, number: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Level> {
    return this.session.execute(Tm1Request.get(levelPath(dimension, hierarchy, number), query, Level), options);
  }

  /** `GET …/Levels(n)/Members`: the level's members, streamed. */
  members(dimension: string, hierarchy: string, number: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(levelPath(dimension, hierarchy, number).nav("Members"), query, Member), options);
  }

  /** `GET …/Levels(n)/Hierarchy`: the level's hierarchy. */
  hierarchy(dimension: string, hierarchy: string, number: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Hierarchy> {
    return this.session.execute(Tm1Request.get(levelPath(dimension, hierarchy, number).nav("Hierarchy"), query, Hierarchy), options);
  }
}

function levelsPath(dimension: string, hierarchy: string): ODataPath {
  return ODataPath.of("Dimensions").key(required(dimension, "dimension")).nav("Hierarchies").key(required(hierarchy, "hierarchy")).nav("Levels");
}

function levelPath(dimension: string, hierarchy: string, number: number): ODataPath {
  // Java's int: a level is keyed by a whole number
  if (!Number.isInteger(number)) throw new RangeError("A level's number is a whole number: " + number);
  return levelsPath(dimension, hierarchy).key(number);
}
