import { afterAll, beforeAll, describe, expect } from "vitest";
import { LogLevel, ODataPath, ODataQuery, ServerSettingsPatch, Tm1BadRequestException, Tm1Request, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** ServerService against a real TM1: the server, its configurations and loggers. The Java client's
 * `ServerServiceTm1Test`. */
describe("ServerService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("reads the server and its configurations", async () => {
    const read = await tm1.server.server(ODataQuery.none());
    // The Java test compares with the container's version; here, with the version the session read
    expect(read.ProductVersion).toBe((await tm1.session.version()).toString());
    expect(read["@odata.etag"], "none for the Server").toBeUndefined();
    const fixed = await tm1.server.staticConfiguration(ODataQuery.none());
    const active = await tm1.server.activeConfiguration(ODataQuery.none());
    expect(fixed.ServerName).toBe(read.Name);
    expect(active.ServerName).toBe(read.Name);
    expect((await tm1.server.configuration(ODataQuery.none())).ServerName).toBe(read.Name);

    await tm1.server.updateStaticConfiguration(
      ServerSettingsPatch.builder()
        .serverName(fixed.ServerName ?? null)
        .build(),
    );
    await expect(
      tm1.session.execute(
        Tm1Request.patch(
          ODataPath.of("ActiveConfiguration"),
          ServerSettingsPatch.builder()
            .serverName(active.ServerName ?? null)
            .build(),
        ),
      ),
      "the active configuration is read only",
    ).rejects.toBeInstanceOf(Tm1BadRequestException);
    expect(typeof active.Access?.CAM?.ClientPingCAMPassport, "a duration here, though declared Int32").toBe("string");
  });

  tm1Test("reads and sets logger levels", async () => {
    const loggers = await tm1.server.loggers(ODataQuery.none()).toArray();
    expect(loggers.length).toBeGreaterThan(0);
    // Not the first, TM1's root logger: at DEBUG it floods the message log of the server every
    // test shares, with lines alike to the millisecond
    const first = loggers.find((l) => l.Name === "TM1.Blob")!;
    expect((await tm1.server.logger(first.Name!, ODataQuery.none())).Level).toBe(first.Level);
    const other = first.Level === LogLevel.Debug ? LogLevel.Info : LogLevel.Debug;
    await tm1.server.setLoggerLevel(first.Name!, other);
    try {
      expect((await tm1.server.logger(first.Name!, ODataQuery.none())).Level).toBe(other);
    } finally {
      await tm1.server.setLoggerLevel(first.Name!, first.Level!);
    }
  });
});
