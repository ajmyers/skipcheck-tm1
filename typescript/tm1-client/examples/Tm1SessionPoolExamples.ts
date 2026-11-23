// SPDX-License-Identifier: Apache-2.0

import { CubeService, Tm1Identity, Tm1Session, Tm1SessionPool, type Cube, type Tm1Stream } from "@skipcheck/tm1-client";

// Examples for Tm1SessionPool, quoted in its documentation.

export function perUser(secret: string, user: string): Tm1Stream<Cube> {
  // @start region = per-user
  // One pool for the application, built once
  const pool = Tm1SessionPool.builder(Tm1Session.builder().baseUrl("http://tm1:8010/api/v1/").basicAuth("service", secret))
    .idleTimeout(10 * 60_000)
    .build();
  // Each time it's needed: the user's session, which logs in on its first request
  const session = pool.session(Tm1Identity.impersonating(user));
  return new CubeService(session).list();
  // @end
}
