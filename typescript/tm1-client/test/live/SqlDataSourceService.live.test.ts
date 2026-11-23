import { afterAll, beforeAll, describe, expect } from "vitest";
import { ODataQuery, Tm1NotFoundException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** SqlDataSourceService against a real TM1, whose host has no ODBC sources: the SqlDataSource half
 * of the Java client's `OidcKeyAndSqlDataSourceTm1Test` (the OidcKeyService half comes with the
 * security services). */
describe("SqlDataSourceService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("lists the host's ODBC sources", async () => {
    expect(await tm1.sqlDataSources.list(ODataQuery.none()).toArray()).toEqual([]);
    await expect(tm1.sqlDataSources.get("none", ODataQuery.none())).rejects.toBeInstanceOf(Tm1NotFoundException);
  });
});
