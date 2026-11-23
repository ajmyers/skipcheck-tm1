import { afterAll, beforeAll, describe, expect } from "vitest";
import { Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { EncryptionFileType, LocalKeyFileProviderSettingsPatch, Tm1BadRequestException, Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const LOCAL = LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/tm1-client.key").build();

/** EncryptionService against a real TM1: the Java client's `EncryptionServiceTm1Test`. The image
 * has no GSKit command line, so the local key file provider can't make a key, and nothing here gets
 * encrypted: the test pins how TM1 says so. As in Java, it starts a TM1 of its own rather than
 * try encryption on the server the other live tests share; with only a server at `TM1_URL` (no TM1
 * source), it uses that one. */
describe("EncryptionService on TM1" + liveReason, () => {
  let server: Tm1ServerContainer | undefined;
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    const source = Tm1Source.discover();
    if (!source) {
      tm1 = await Live.client();
      return;
    }
    server = new Tm1ServerContainer(source);
    await server.start();
    tm1 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
    await server?.stop();
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
