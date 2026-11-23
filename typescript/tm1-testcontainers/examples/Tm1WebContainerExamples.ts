// SPDX-License-Identifier: Apache-2.0

import { Tm1Seed, Tm1ServerContainer, Tm1WebContainer, type Tm1Source, type Tm1WebSource } from "@skipcheck/tm1-testcontainers";

// Examples for Tm1WebContainer, quoted in its documentation. Test code, never run here.

export async function start(tm1Source: Tm1Source, webSource: Tm1WebSource): Promise<string> {
  // @start region = start
  await using tm1 = new Tm1ServerContainer(tm1Source).withSeed(Tm1Seed.SDATA);
  await using web = new Tm1WebContainer(webSource).withTm1Server(tm1);
  await web.start(); // starts tm1 first
  return web.getUrl(); // http://localhost:54321/tm1web/
  // @end
}
