// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery, SQLDataSource, Tm1NotFoundException } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** SqlDataSourceService against a fake TM1 (the .NET client's SqlDataSourceServiceTests). */
describe("SqlDataSourceService", () => {
  it("lists and gets", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Warehouse", Description: "SQL Server" });
    const client = tm1.client();
    const sources = await client.sqlDataSources.list(ODataQuery.none().select(SQLDataSource.Fields.Name)).toArray();
    expect(sources.map((s) => [s.Name, s.Description])).toEqual([["Warehouse", "SQL Server"]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET SQLDataSources?$select=Name");

    tm1.respond = () => ({ Name: "Warehouse" });
    expect((await client.sqlDataSources.get("Warehouse")).Name).toBe("Warehouse");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET SQLDataSources('Warehouse')");

    tm1.respond = () => FakeTm1.reply(404, { error: { code: "", message: "not found" } });
    await expect(client.sqlDataSources.get("none", ODataQuery.none().select("Name"))).rejects.toBeInstanceOf(Tm1NotFoundException);
    expect(tm1.last.target).toBe("SQLDataSources('none')?$select=Name");
  });
});
