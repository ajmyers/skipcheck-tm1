// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import { ODataQuery, Tm1NotFoundException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** OidcKeyService and SqlDataSourceService against a real TM1: the Java client's
 * `OidcKeyAndSqlDataSourceTm1Test`. The test server checks no OIDC tokens, so the cache is empty, and
 * its host has no ODBC sources; the tests pin how TM1 says so. */
describe("OidcKeyService and SqlDataSourceService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("reads and clears the OIDC key cache", async () => {
    expect(await tm1.oidcKeys.keys().toArray()).toEqual([]);
    await tm1.oidcKeys.remove([]);
    // TM1 answers 400 ObjectNotFound, read as not found
    const e = await tm1.oidcKeys.remove(["not-cached"]).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Tm1NotFoundException);
    expect((e as Error).message).toContain("ObjectNotFound");
  });

  tm1Test("lists the host's ODBC sources", async () => {
    expect(await tm1.sqlDataSources.list(ODataQuery.none()).toArray()).toEqual([]);
    await expect(tm1.sqlDataSources.get("none", ODataQuery.none())).rejects.toBeInstanceOf(Tm1NotFoundException);
  });
});
