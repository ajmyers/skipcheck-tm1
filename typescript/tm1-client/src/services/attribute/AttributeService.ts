import { AttributeDefinition, type AttributeDefinitionPatch } from "../../generated/index.js";
import type { ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import type { AttributeSet } from "./AttributeSet.js";

const SETS = [
  "EntitySet DimensionAttributes",
  "Navigation Dimension/HierarchyAttributes",
  "Navigation Hierarchy/ElementAttributes",
  "Navigation Hierarchy/SubsetAttributes",
  "EntitySet CubeAttributes",
  "Navigation Cube/ViewAttributes",
  "EntitySet ProcessAttributes",
  "EntitySet ChoreAttributes",
] as const;

/** Attribute definitions: which attributes a kind of object has, and their types, for each
 * {@link AttributeSet}. Creating one adds it to every object of that kind; the values are cells of
 * the matching `}…Attributes` control cube.
 *
 * Add an attribute to every element of a hierarchy:
 * ```ts
 * await tm1.attributes.create(AttributeSet.elements("Region", "Region"), AttributeDefinitionPatch.builder().name("Manager").type(AttributeType.String).build());
 * ```
 * List the attributes a kind of object has, here cubes:
 * ```ts
 * for await (const a of tm1.attributes.list(AttributeSet.cubes())) console.log(a.Name, a.Type);
 * ```
 */
export class AttributeService extends Tm1Service {
  static readonly endpoints = {
    list: SETS,
    get: SETS,
    create: SETS,
    delete: SETS,
  } as const satisfies Tm1Endpoints<AttributeService>;

  /** `GET` the set's attributes, streamed. */
  list(set: AttributeSet, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<AttributeDefinition> {
    return this.session.stream(Tm1Request.list(required(set, "set").path(), query, AttributeDefinition), options);
  }

  /** `GET` one attribute of the set: its definition, or a `Tm1NotFoundException` if there's none. */
  get(set: AttributeSet, name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<AttributeDefinition> {
    return this.session.execute(Tm1Request.get(required(set, "set").path().key(required(name, "name")), query, AttributeDefinition), options);
  }

  /** `POST`: adds an attribute, with its name and type, to every object of the set's kind; returns
   * it as created. */
  create(set: AttributeSet, attribute: AttributeDefinitionPatch, options?: Tm1CallOptions): Promise<AttributeDefinition> {
    return this.session.execute(Tm1Request.post(required(set, "set").path(), required(attribute, "attribute"), AttributeDefinition), options);
  }

  /** `DELETE`: removes an attribute, with its values, from every object of the set's kind. */
  delete(set: AttributeSet, name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(required(set, "set").path().key(required(name, "name"))), options);
  }
}
