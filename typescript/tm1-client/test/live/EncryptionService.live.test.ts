// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import { Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import {
  EncryptionFileType,
  LocalKeyFileProviderSettingsPatch,
  Tm1BadRequestException,
  Tm1Client,
  type Tm1Operation,
  Tm1UnsupportedOperationException,
} from "../../src/index.js";
import { Tm1Operations } from "../../src/generated/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { isAtLeast, lacks, MAKES_OWN_KEY_FROM } from "./server-assumptions.js";

const LOCAL = LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/tm1-client.key").build();

/** EncryptionService against a real TM1: the Java client's `EncryptionServiceTm1Test`. Before TM1
 * 11.8.040 the image's missing GSKit command line stops the local key file provider from making a
 * key, so the settings check as invalid and every change is refused: the test pins how TM1 says so.
 * From 11.8.040 the server makes the key itself and the settings check as valid, so configuring
 * would really convert the server and stop it: the test doesn't try that. As in Java, it starts a TM1 of its own rather than
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

  /** TM1's refusal, with its bare code name in the message, where the server defines the operation;
   * the client's own refusal, sending nothing, where it doesn't. */
  async function refused(operation: Tm1Operation, call: () => Promise<unknown>, code: string): Promise<void> {
    const e = await call().then(
      () => undefined,
      (x: unknown) => x,
    );
    if (await lacks(tm1, operation)) {
      expect(e, operation.endpoint()).toBeInstanceOf(Tm1UnsupportedOperationException);
      return;
    }
    expect(e, operation.endpoint()).toBeInstanceOf(Tm1BadRequestException);
    expect((e as Error).message).toContain(code);
  }

  /** Whether the server's settings are expected to check as valid. */
  async function makesItsOwnKey(): Promise<boolean> {
    return isAtLeast(tm1, MAKES_OWN_KEY_FROM);
  }

  tm1Test("validates settings without changing anything", async (context) => {
    const why = await lacks(tm1, Tm1Operations.ValidateEncryptionAtRestSettings);
    if (why) return context.skip(why);
    const report = await tm1.encryption.validate(LOCAL);
    if (await makesItsOwnKey()) {
      expect(report.IsValid).toBe(true);
      expect(report.Issues ?? []).toEqual([]);
    } else {
      expect(report.IsValid).toBe(false);
      expect(report.Issues).toHaveLength(1);
      expect(String(report.Issues![0])).toContain("Could not generate a new key using the local GSKit installation");
    }
  });

  tm1Test("refuses changes with bare code names", async () => {
    // Configuring with settings the server takes would convert it and stop it, so only where they
    // check as invalid, whatever the version
    const takesThem = !(await lacks(tm1, Tm1Operations.ValidateEncryptionAtRestSettings)) && (await tm1.encryption.validate(LOCAL)).IsValid === true;
    if (!takesThem) await refused(Tm1Operations.ConfigureEncryptionAtRest, () => tm1.encryption.configure(LOCAL), "DataModelConversion");
    await refused(Tm1Operations.DisableEncryptionAtRest, () => tm1.encryption.disable(), "DataModelConversion");
    await refused(Tm1Operations.RotateDataEncryptionKey, () => tm1.encryption.rotateDataEncryptionKey(), "DataEncryptionKeyRotationFailed");
    await refused(Tm1Operations.RotateDataModelKey, () => tm1.encryption.rotateDataModelKey(), "RotateDataModelKey");
    await refused(
      Tm1Operations.EncryptDataFile,
      () => tm1.encryption.encryptFile("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object),
      "DataFileConversion",
    );
    await refused(
      Tm1Operations.DecryptDataFile,
      () => tm1.encryption.decryptFile("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.Object),
      "DataFileConversion",
    );
  });
});
