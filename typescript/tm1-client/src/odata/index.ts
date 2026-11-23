// SPDX-License-Identifier: Apache-2.0

import type { ODataPath } from "./path.js";

export { ODataEncoding } from "./encoding.js";
export { Filter, ODataProperty } from "./filter.js";
export { ODataLiterals, type ODataLiteral } from "./literals.js";
export { ODataPath } from "./path.js";
export { ODataQuery } from "./query.js";

/** OData's JSON control information, as the Java client's `ODataJson`. */
export const ODataJson = {
  CONTEXT: "@odata.context",
  ETAG: "@odata.etag",
  TYPE: "@odata.type",
  ID: "@odata.id",
  NEXT_LINK: "@odata.nextLink",
  DELTA_LINK: "@odata.deltaLink",
  COUNT: "@odata.count",
  BIND_SUFFIX: "@odata.bind",

  /** `{"Cube@odata.bind": "Cubes('c')"}` (or a list, for several), for a POST or PATCH body. */
  bind(navigation: string, target: ODataPath | readonly ODataPath[]): Record<string, string | string[]> {
    const key = navigation + "@odata.bind";
    return { [key]: Array.isArray(target) ? target.map((t) => t.toString()) : (target as ODataPath).toString() };
  },
} as const;
