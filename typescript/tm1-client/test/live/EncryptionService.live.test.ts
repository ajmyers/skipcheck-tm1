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
import { type Install, installOf, lacks } from "./server-assumptions.js";

const LOCAL = LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/tm1-client.key").build();

/** EncryptionService against a real TM1: the Java client's `EncryptionServiceTm1Test`. Whether local
 * key file settings check as valid depends on the install, not the version (#439): TM1 makes the key
 * in its install's `bin64/ssl/ibmtm1.kdb`, so on a writable install (a bundle's, a volume's) they
 * check as valid, and configuring would really convert the server and stop it, which the test
 * doesn't try. On a read-only one (an installed folder, `TM1_HOME_DIR`) TM1 can't make the key, the
 * settings check as invalid and every change is refused: the test pins how TM1 says so. So on a
 * writable source the run does change the install: a volume of your own (`TM1_INSTALL_VOLUME`), or
 * the bundle cache, keeps the key TM1 made in its `ibmtm1.kdb`. As in Java,
 * it starts a TM1 of its own rather than try encryption on the server the other live tests share;
 * with only a server at `TM1_URL` (no TM1 source), it uses that one, whose install isn't known, so
 * the settings aren't checked there: that would write a key into its install. */
describe("EncryptionService on TM1" + liveReason, () => {
  let server: Tm1ServerContainer | undefined;
  let tm1: Tm1Client;
  let install: Install | undefined;

  beforeAll(async () => {
    if (!Live.configured) return;
    const source = Tm1Source.discover();
    if (!source) {
      tm1 = await Live.client();
      return;
    }
    install = installOf(source);
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

  tm1Test("validates local key file settings", async (context) => {
    const why = await lacks(tm1, Tm1Operations.ValidateEncryptionAtRestSettings);
    if (why) return context.skip(why);
    if (install === undefined) return context.skip("a server the suite didn't start: validating would write a key into its install");
    const report = await tm1.encryption.validate(LOCAL);
    if (install === "writable") {
      expect(report.IsValid).toBe(true);
      expect(report.Issues ?? []).toEqual([]);
    } else {
      expect(report.IsValid).toBe(false);
      expect(report.Issues).toHaveLength(1);
      expect(String(report.Issues![0])).toContain("Could not generate a new key using the local GSKit installation");
    }
  });

  tm1Test("refuses changes with bare code names", async () => {
    // Configuring with settings the server takes would convert it and stop it, so only where that
    // server says they're invalid, whatever its install; and not at all on a server the suite didn't
    // start, where validating would write a key into its install
    const takesThem =
      install === undefined || (!(await lacks(tm1, Tm1Operations.ValidateEncryptionAtRestSettings)) && (await tm1.encryption.validate(LOCAL)).IsValid === true);
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
