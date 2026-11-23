// SPDX-License-Identifier: Apache-2.0

import { Filter, ODataProperty, ODataQuery } from "@skipcheck/tm1-client";

// Examples for ODataQuery, quoted in its documentation.

export function build(): string {
  // @start region = build
  const query = ODataQuery.none()
    .select("Name", "Type")
    .expand("Hierarchies", (h) => h.select("Name").top(5))
    .filter(Filter.startsWith(ODataProperty.of("Name"), "Q"))
    .toQueryString();
  // $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q'), each value encoded
  // @end
  return query;
}
