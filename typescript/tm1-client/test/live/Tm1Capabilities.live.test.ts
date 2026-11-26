// SPDX-License-Identifier: Apache-2.0

import { describe, expect } from "vitest";
import {
  ODataPath,
  Tm1Authenticator,
  Tm1Capabilities,
  Tm1Capability,
  Tm1Identity,
  Tm1Request,
  type Tm1Session,
  Tm1SessionPool,
  Tm1UnsupportedOperationException,
  Tm1Version,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { ownSnapshot, schemaVersionIn } from "./server-assumptions.js";
import { sessions, SERVER, failure } from "./raw-tm1.js";

/** The Java client's `Tm1CapabilitiesTm1Test`. */
const metadata = (s: Tm1Session): Promise<string> => s.execute(Tm1Request.text(ODataPath.metadata()).withHeader("Accept", "application/xml"));

describe("Tm1 capabilities on TM1" + liveReason, () => {
  tm1Test("reads the version and the capabilities the snapshot describes", async (context) => {
    const s = (await sessions()).build();
    try {
      const reported = (await s.execute(SERVER)).ProductVersion;
      expect((await s.version()).equals(Tm1Version.parse(reported))).toBe(true);
      const capabilities = await s.capabilities();
      // The schema its $metadata says, read from the text, not by the client
      expect(capabilities.schemaVersion).toBe(schemaVersionIn(await metadata(s)));
      const xml = ownSnapshot(capabilities);
      if (xml === undefined) return context.skip("No metadata snapshot is committed for schema " + capabilities.schemaVersion);
      const container = xml.slice(xml.indexOf("<EntityContainer"), xml.indexOf("</EntityContainer>"));
      for (const [, kind, n] of container.matchAll(/<(EntitySet|Singleton|ActionImport) Name="([^"]+)"/g)) {
        const capability =
          kind === "EntitySet" ? Tm1Capability.entitySet(n!) : kind === "Singleton" ? Tm1Capability.singleton(n!) : Tm1Capability.unboundOperation(n!);
        expect(capabilities.supports(capability), `${kind} ${n}`).toBe(true);
      }
      const reserve = Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve");
      expect(capabilities.supports(reserve), "Reserve, from schema 11.8.34").toBe(Tm1Capabilities.parse(xml).supports(reserve));
    } finally {
      await s.close();
    }
  });

  tm1Test("refuses a request the server doesn't define", async () => {
    const s = (await sessions()).build();
    try {
      const version = await s.version();
      const missing = Tm1Request.get(ODataPath.of("Bananas")).requires(Tm1Capability.entitySet("Bananas").since("99.0"));
      const e = await failure(s.execute(missing));
      expect(e).toBeInstanceOf(Tm1UnsupportedOperationException);
      const message = (e as Error).message;
      expect(message).toContain("added in schema 99.0");
      expect(message).toContain("reports schema " + schemaVersionIn(await metadata(s)));
      expect(message).toContain("product version " + version.toString());
    } finally {
      await s.close();
    }
  });

  tm1Test("shares one fetch between a pool's sessions", async () => {
    const t = await Live.target();
    const pool = Tm1SessionPool.builder(await sessions()).build();
    try {
      const first = await pool.session().capabilities();
      const second = await pool.session(Tm1Identity.withCredentials("again", Tm1Authenticator.basic(t.user, t.password))).capabilities();
      expect(second).toBe(first);
    } finally {
      await pool.close();
    }
  });
});
