// SPDX-License-Identifier: Apache-2.0

import { ODataPath } from "@skipcheck/tm1-client";

// Examples for ODataPath, quoted in its documentation.

export function build(): ODataPath[] {
  // @start region = build
  const element = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements").key("O'Neil");
  // Dimensions('Region')/Hierarchies('Region')/Elements('O''Neil')
  const reserve = ODataPath.of("Cubes").key("Sales").action("tm1.Reserve");
  // Cubes('Sales')/tm1.Reserve
  // @end
  return [element, reserve];
}
