// SPDX-License-Identifier: Apache-2.0

import { ODataPath, ODataQuery, Tm1Request } from "@skipcheck/tm1-client";

// Examples for Tm1Request, quoted in its documentation.

export function build(): Tm1Request<unknown>[] {
  // @start region = build
  // A cube's name, read as plain JSON
  const cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"));
  // An action with no answer
  const reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), {});
  // @end
  return [cube, reserve];
}
