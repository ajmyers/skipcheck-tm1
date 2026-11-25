// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ChoreCreate, CubeCreate, ODataPath } from "../../src/index.js";
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

  it("takes a binding's path as written, encoded", () => {
    // A key's %2F is a slash in the key, not between segments
    const built = build(CubeCreate, { Name: "c", "Dimensions@odata.bind": ["Dimensions('a%2Fb')"] });
    expect(JSON.parse(JSON.stringify(built))["Dimensions@odata.bind"]).toEqual(["Dimensions('a%2Fb')"]);
    expect(() => build(CubeCreate, { Name: "c", "Dimensions@odata.bind": "Dimensions('d')" })).toThrow("binds a list of paths");
  });

  it("refuses a declared key no setter writes on an open type", () => {
    // NativeView is open, but declares Name, which its patch doesn't offer
    expect(() => build(Patches.NativeViewPatch, { "@odata.type": "#ibm.tm1.api.v1.NativeView", Name: "renamed" })).toThrow(
      "NativeViewPatch has no setter for Name",
    );
  });

  it("refuses a value that doesn't read back as written", () => {
    expect(() => build(ChoreCreate, { Name: "c", ExecutionMode: "Nope" })).toThrow("doesn't read back as written");
    expect(() => build(CubeCreate, { "@odata.type": "#ibm.tm1.api.v1.Cube", Name: "c" })).toThrow("doesn't send @odata.type");
  });

  it("keeps a date-time's offset", () => {
    const built = build(ChoreCreate, { Name: "c", StartTime: "2026-03-04T05:06:07.5+02:00" });
    expect(JSON.parse(JSON.stringify(built)).StartTime).toBe("2026-03-04T05:06:07.5+02:00");
  });
});
