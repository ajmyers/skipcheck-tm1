// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { FakeTm1 } from "../support/fake-tm1.js";

/** OidcKeyService against a fake TM1 (the .NET client's OidcKeyServiceTests; the Java client tests
 * OidcKeyService only against TM1). */
describe("OidcKeyService", () => {
  it("lists and removes keys", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection("k1", "k2");
    const client = tm1.client();
    expect(await client.oidcKeys.keys().toArray()).toEqual(["k1", "k2"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET GetOIDCKeys()");

    tm1.respond = () => null;
    await client.oidcKeys.remove(["k1", "k2"]);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "RemoveOIDCKeyFromCache", '{"KeyId":["k1","k2"]}']);
    await client.oidcKeys.remove([]);
    expect(tm1.last.text).toBe('{"KeyId":[]}');
  });
});
