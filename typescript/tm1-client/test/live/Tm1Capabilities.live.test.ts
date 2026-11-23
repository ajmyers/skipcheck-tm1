import { readFileSync } from "node:fs";
import { describe, expect } from "vitest";
import {
  ODataPath,
  Tm1Authenticator,
  Tm1Capabilities,
  Tm1Capability,
  Tm1Identity,
  Tm1Request,
  Tm1SessionPool,
  Tm1UnsupportedOperationException,
  Tm1Version,
} from "../../src/index.js";
import { Repo } from "../support/repo.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sessions, SERVER, failure } from "./raw-tm1.js";

/** The Java client's `Tm1CapabilitiesTm1Test`. */
describe("Tm1 capabilities on TM1" + liveReason, () => {
  const snapshot = (): string => readFileSync(Repo.snapshot, "utf8");

  tm1Test("reads the version and the capabilities the snapshot describes", async () => {
    const s = (await sessions()).build();
    try {
      const reported = (await s.execute(SERVER)).ProductVersion;
      expect((await s.version()).equals(Tm1Version.parse(reported))).toBe(true);
      const capabilities = await s.capabilities();
      const xml = snapshot();
      expect(capabilities.schemaVersion).toBe(Tm1Capabilities.parse(xml).schemaVersion);
      const container = xml.slice(xml.indexOf("<EntityContainer"), xml.indexOf("</EntityContainer>"));
      for (const [, kind, n] of container.matchAll(/<(EntitySet|Singleton|ActionImport) Name="([^"]+)"/g)) {
        const capability =
          kind === "EntitySet" ? Tm1Capability.entitySet(n!) : kind === "Singleton" ? Tm1Capability.singleton(n!) : Tm1Capability.unboundOperation(n!);
        expect(capabilities.supports(capability), `${kind} ${n}`).toBe(true);
      }
      expect(capabilities.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve"))).toBe(true);
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
      expect(message).toContain("reports schema " + Tm1Capabilities.parse(snapshot()).schemaVersion);
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
