// SPDX-License-Identifier: Apache-2.0

import { Element, Filter, ODataLiterals } from "@skipcheck/tm1-client";

// Examples for Filter, quoted in its documentation.

export function build(): Filter[] {
  // @start region = build
  const numericQ = Filter.and(
    Filter.startsWith(Element.Fields.Name, "Q"),
    Element.Fields.Type.eq(ODataLiterals.enumMember("ibm.tm1.api.v1.ElementType", "Numeric")),
  );
  // (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
  const hasRegion = Filter.any("Hierarchies", "h", (h) => h.nav("Name").eq("Region"));
  // Hierarchies/any(h:h/Name eq 'Region')
  // @end
  return [numericQ, hasRegion];
}
