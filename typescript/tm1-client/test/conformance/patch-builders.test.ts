// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { CubeCreate, ODataPath } from "../../src/index.js";
import * as Patches from "../../src/generated/patches.js";
import { build } from "./codec.js";

/** A scenario's `$patch` goes through the generated setters (#354): what a caller can't build, a
 * scenario can't send. As the Java client's PatchBuildersTest. */
describe("PatchBuilders", () => {
  it("builds a write with its setters and binds each path once", () => {
    const built = build(CubeCreate, { Name: "c", Rules: null, "Dimensions@odata.bind": ["Dimensions('%7DClients')"] });
    const made = CubeCreate.builder().name("c").rules(null).bindDimensions(ODataPath.of("Dimensions").key("}Clients")).build();
    expect(JSON.stringify(built)).toBe(JSON.stringify(made));
    expect(JSON.parse(JSON.stringify(built))["Dimensions@odata.bind"]).toEqual(["Dimensions('%7DClients')"]);
  });

  it("refuses a key no setter writes", () => {
    expect(() => build(CubeCreate, { Name: "c", Nope: 1 })).toThrow("CubeCreate has no setter for Nope");
  });

  it("builds a nested value with its own builder and a root's by subtype", () => {
    const built = build(Patches.ServerSettingsPatch, { EncryptionAtRest: { "@odata.type": "#ibm.tm1.api.v1.VaultSettings", KeyRef: "k" } });
    // Only VaultSettingsPatch's builder sends the @odata.type: the root's leaves it out
    expect(JSON.parse(JSON.stringify(built)).EncryptionAtRest).toEqual({ "@odata.type": "#ibm.tm1.api.v1.VaultSettings", KeyRef: "k" });
  });

  it("sets an open type's undeclared property by name", () => {
    const built = build(Patches.EncryptionAtRestProviderSettingsPatch, { KeyRef: "k", Extra: "x" });
    expect(JSON.parse(JSON.stringify(built)).Extra).toBe("x");
  });
});
