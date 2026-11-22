import { describe, expect, it } from "vitest";
import { EncryptionFileType, LocalKeyFileProviderSettingsPatch } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const sent = (tm1: FakeTm1): string => `${tm1.last.method} ${tm1.last.target}`;

/** EncryptionService against a fake TM1: each method sends what the Java client's does (the Java
 * client has no unit test of its own for these; this is the .NET client's EncryptionServiceTests,
 * with Java's requests). */
describe("EncryptionService", () => {
  it("validates settings given as a parameter alias", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ IsValid: false, Issues: ["Could not generate a new key using the local GSKit installation"] });
    const settings = LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/a.key").build();
    const report = await tm1.client().encryption.validate(settings);
    expect(report.IsValid).toBe(false);
    expect(report.Issues).toHaveLength(1);
    expect(tm1.last.method).toBe("GET");
    const prefix = "ValidateEncryptionAtRestSettings(Settings=@settings)?@settings=";
    expect(tm1.last.target.startsWith(prefix)).toBe(true);
    const json = JSON.parse(decodeURIComponent(tm1.last.target.slice(prefix.length)));
    expect(json).toEqual({ "@odata.type": "#ibm.tm1.api.v1.LocalKeyFileProviderSettings", KeyRef: "/tmp/a.key" });
  });

  it("calls the actions", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    await client.encryption.configure(LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/a.key").build());
    expect(sent(tm1)).toBe("POST ConfigureEncryptionAtRest");
    expect(tm1.last.json).toEqual({ Settings: { "@odata.type": "#ibm.tm1.api.v1.LocalKeyFileProviderSettings", KeyRef: "/tmp/a.key" } });
    for (const [call, name] of [
      [() => client.encryption.disable(), "DisableEncryptionAtRest"],
      [() => client.encryption.rotateDataEncryptionKey(), "RotateDataEncryptionKey"],
      [() => client.encryption.rotateDataModelKey(), "RotateDataModelKey"],
    ] as const) {
      await call();
      expect([sent(tm1), tm1.last.text]).toEqual([`POST ${name}`, "{}"]);
    }

    await client.encryption.encryptFile("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST EncryptDataFile", '{"SourcePath":"tm1s.cfg","DestinationPath":"/tmp/tm1s.cfg.enc","FileType":"Object"}']);
    await client.encryption.decryptFile("a.log", "/tmp/a.log", EncryptionFileType.TransactionLog);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST DecryptDataFile", '{"SourcePath":"a.log","DestinationPath":"/tmp/a.log","FileType":"TransactionLog"}']);

    await client.encryption.encryptModel(30_000);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST EncryptDataModel", '{"TimeUntilShutdown":"PT30S"}']);
    // As Java's Duration writes it: hours, never days
    await client.encryption.encryptModel(36 * 3_600_000 + 1_500);
    expect(tm1.last.text).toBe('{"TimeUntilShutdown":"PT36H1.5S"}');
    await client.encryption.decryptModel(null);
    expect([sent(tm1), tm1.last.text]).toEqual(["POST DecryptDataModel", "{}"]);
    await client.encryption.decryptModel(90_000);
    expect(tm1.last.text).toBe('{"TimeUntilShutdown":"PT1M30S"}');
  });
});
