// SPDX-License-Identifier: Apache-2.0

import { Tm1Capability, type Tm1Session } from "@skipcheck/tm1-client";

// Examples for Tm1Capability and Tm1Capabilities, quoted in their documentation.

export async function supports(session: Tm1Session): Promise<boolean> {
  // @start region = supports
  // Does the server's $metadata define the cube's tm1.Reserve action?
  const reserve = Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve").since("11.8.34");
  return (await session.capabilities()).supports(reserve);
  // @end
}
