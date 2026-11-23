// SPDX-License-Identifier: Apache-2.0

// Compiled, never run: an application's code against the installed packages' declarations. It
// fails to compile when a package's "types" or "exports" point at a file that wasn't packed.
import { Cube, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";
import type { Cube as CubeModel } from "@skipcheck/tm1-client/model";
import { Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";

export async function cubeNames(tm1: Tm1Client): Promise<string[]> {
  const names: string[] = [];
  for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
    const typed: CubeModel = cube;
    if (typed.Name) names.push(typed.Name);
  }
  return names;
}

export const containers: unknown[] = [Tm1ServerContainer, Tm1Source];
