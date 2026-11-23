// SPDX-License-Identifier: Apache-2.0

import { PawContainer, Tm1ServerContainer, type PawSource, type Tm1Source } from "@skipcheck/tm1-testcontainers";

// Examples for PawContainer, quoted in its documentation. Test code, never run here.

export async function start(tm1Source: Tm1Source, pawSource: PawSource): Promise<string> {
  // @start region = start
  await using tm1 = new Tm1ServerContainer(tm1Source);
  await using paw = new PawContainer(pawSource).withTm1Server(tm1).acceptLicense();
  await paw.start(); // starts tm1 first
  return paw.getUrl(); // http://localhost:54321/
  // @end
}
