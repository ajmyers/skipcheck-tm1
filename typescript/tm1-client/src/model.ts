// SPDX-License-Identifier: Apache-2.0

import type { ODataProperty } from "./odata/index.js";

/** What reads a type of the model: the generated constant of the same name as its interface
 * (`Cube` for `interface Cube`). Requests take it to read TM1's answer: enums TM1 adds later read as
 * their fallback, a hierarchy's root as the subtype `@odata.type` names, durations in ISO 8601. */
export interface Tm1Model<T> {
  /** The type's name in the model, such as `Cube`. */
  readonly $type: string;
  /** Its name in `$metadata`, such as `ibm.tm1.api.v1.Cube`. */
  readonly qualifiedName: string;
  /** @internal Carries `T` for inference; never set. */
  readonly __model?: T;
}

/** @internal What the generated model constants are made with. */
export function model<T, F extends Record<string, ODataProperty>>(name: string, qualifiedName: string, fields: F): Tm1Model<T> & { readonly Fields: F } {
  return Object.freeze({ $type: name, qualifiedName, Fields: Object.freeze(fields) });
}

/** The answer as JSON, read as is. */
export const Json: Tm1Model<unknown> = Object.freeze({ $type: "", qualifiedName: "" });

/** An answer that's a number: `{"value": 12.5}` reads as `12.5`. */
export const Primitive = {
  number: Object.freeze({ $type: "$number", qualifiedName: "Edm.Double" }) as Tm1Model<number>,
  string: Object.freeze({ $type: "$string", qualifiedName: "Edm.String" }) as Tm1Model<string>,
  boolean: Object.freeze({ $type: "$boolean", qualifiedName: "Edm.Boolean" }) as Tm1Model<boolean>,
} as const;
