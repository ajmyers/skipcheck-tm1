import { describe, expect, it } from "vitest";
import { LogLevel, ODataQuery, Server, ServerSettingsPatch } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** ServerService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's ServerServiceTests; the Java client has live tests only). */
describe("ServerService", () => {
  it("reads the server and its configurations", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Planning", ProductVersion: "11.8.03500.6" });
    const server = await client.server.server(ODataQuery.none().select(Server.Fields.Name));
    expect([server.Name, server.ProductVersion]).toEqual(["Planning", "11.8.03500.6"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Server?$select=Name");

    tm1.respond = () => ({ ServerName: "Planning" });
    expect((await client.server.staticConfiguration()).ServerName).toBe("Planning");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET StaticConfiguration");
    expect((await client.server.activeConfiguration(ODataQuery.none().select("ServerName"))).ServerName).toBe("Planning");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET ActiveConfiguration?$select=ServerName");
    expect((await client.server.configuration(ODataQuery.none().select("ServerName"))).ServerName).toBe("Planning");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Configuration?$select=ServerName");

    tm1.respond = () => null;
    await client.server.updateStaticConfiguration(ServerSettingsPatch.builder().serverName("Planning").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "StaticConfiguration", '{"ServerName":"Planning"}']);
  });

  it("reads and sets loggers", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "TM1.Server", Level: "Info" }, { Name: "TM1.New", Level: "Verbose" });
    const loggers = await client.server.loggers(ODataQuery.none().top(2)).toArray();
    expect(loggers.map((l) => [l.Name, l.Level])).toEqual([
      ["TM1.Server", "Info"],
      ["TM1.New", "Unrecognized"],
    ]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Loggers?$top=2");

    tm1.respond = () => ({ Name: "TM1.Server", Level: "Debug" });
    expect((await client.server.logger("TM1.Server", ODataQuery.none().select("Level"))).Level).toBe(LogLevel.Debug);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Loggers('TM1.Server')?$select=Level");

    tm1.respond = () => null;
    await client.server.setLoggerLevel("TM1.Server", LogLevel.Debug);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Loggers('TM1.Server')", '{"Level":"Debug"}']);
    const count = tm1.requests.length;
    expect(() => client.server.setLoggerLevel("TM1.Server", LogLevel.Unrecognized)).toThrow(RangeError);
    expect(tm1.requests.length).toBe(count);
  });
});
