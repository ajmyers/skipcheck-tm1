// SPDX-License-Identifier: Apache-2.0

import { Tm1Timeouts } from "@skipcheck/tm1-client";

// Examples for Tm1Timeouts, quoted in its documentation.

export function longRequests(): Tm1Timeouts {
  // @start region = long-requests
  // Let a request go two hours without data, for a long process run synchronously
  const timeouts = Tm1Timeouts.builder()
    .request(2 * 60 * 60 * 1000)
    .build();
  // @end
  return timeouts;
}
