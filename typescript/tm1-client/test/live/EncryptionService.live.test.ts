import { afterAll, beforeAll, describe, expect } from "vitest";
import { EncryptionFileType, LocalKeyFileProviderSettingsPatch, Tm1BadRequestException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const LOCAL = LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/tm1-client.key").build();

/** EncryptionService against a real TM1: the Java client's `EncryptionServiceTm1Test`. The image
 * has no GSKit command line, so the local key file provider can't make a key, and nothing here gets
 * encrypted: the test pins how TM1 says so. (Java starts a TM1 of its own for this; nothing here
 * changes the server, so, as in the .NET client, it shares the one the other live tests use.) */
describe("EncryptionService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  async function refused(call: () => Promise<unknown>, code: string): Promise<void> {
    const e = await call().then(
      () => undefined,
      (x: unknown) => x,
    );
    expect(e).toBeInstanceOf(Tm1BadRequestException);
    expect((e as Error).message).toContain(code);
  }

  tm1Test("validates settings without changing anything", async () => {
    const report = await tm1.encryption.validate(LOCAL);
    expect(report.IsValid).toBe(false);
    expect(report.Issues).toHaveLength(1);
    expect(String(report.Issues![0])).toContain("Could not generate a new key using the local GSKit installation");
  });

  tm1Test("refuses changes with bare code names", async () => {
    await refused(() => tm1.encryption.configure(LOCAL), "DataModelConversion");
    await refused(() => tm1.encryption.disable(), "DataModelConversion");
    await refused(() => tm1.encryption.rotateDataEncryptionKey(), "DataEncryptionKeyRotationFailed");
    await refused(() => tm1.encryption.rotateDataModelKey(), "RotateDataModelKey");
    await refused(() => tm1.encryption.encryptFile("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object), "DataFileConversion");
    await refused(() => tm1.encryption.decryptFile("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.Object), "DataFileConversion");
  });
});
