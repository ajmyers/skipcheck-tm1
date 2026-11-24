// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import {
  Chore,
  ChoreCreate,
  ChorePatch,
  ChoreTaskCreate,
  type ChoreTaskPatch,
  CubeCreate,
  Element,
  ElementCreate,
  type MDXViewCreate,
  NativeViewCreate,
  NativeViewCreateBuilder,
  ODataPath,
  type ProcessPatch,
  readAs,
  ServerSettings,
  ServerSettingsPatch,
  View,
  ViewCreate,
  ViewCreateBuilder,
} from "../src/index.js";

/** The Java client's `WritesTest`: the create types and `builder(read)`, a write made from a read
 * entity holding only what TM1 applies, by the probe (`tm1-probe-<schema>.json`'s writes). */
describe("writes made from what was read", () => {
  it("creates from a read entity what a POST applies", () => {
    const read = readAs(Element, { Name: "North", Type: "Consolidated", Index: 4, Level: 1, Attributes: { Caption: "North" } });
    const create = JSON.parse(JSON.stringify(ElementCreate.builder(read).build())) as Record<string, unknown>;
    // TM1 ignores Index, Level and Attributes in a create: they aren't offered, nor copied
    expect(Object.keys(create).sort()).toEqual(["Name", "Type"]);
    expect(create.Type).toBe("Consolidated");
    // A builder, to change what's copied
    expect(JSON.parse(JSON.stringify(ElementCreate.builder(read).name("South").build())).Name).toBe("South");
  });

  it("patches from a read entity leaving out what a PATCH rejects or appends", () => {
    const read = readAs(Chore, {
      Name: "Nightly",
      Active: true,
      Frequency: "P1DT00H00M00S",
      Attributes: { Caption: "Nightly" },
      Tasks: [{ Step: 0, Parameters: [{ Name: "pValue", Value: "1" }] }],
    });
    // A PATCH refuses Attributes and a rename, and appends Tasks: none is copied
    expect(Object.keys(JSON.parse(JSON.stringify(ChorePatch.builder(read).build()))).sort()).toEqual(["Active", "Frequency"]);
    // A create ignores Attributes too (they're written through the attribute cubes). Tasks is a
    // navigation: none is copied, as a task's Process would have to be bound again
    const create = Object.keys(JSON.parse(JSON.stringify(ChoreCreate.builder(read).build())));
    expect(create).toContain("Frequency");
    expect(create).not.toContain("Attributes");
    expect(create).not.toContain("Tasks");
  });

  it("copies a value whose type has subtypes as its own type", () => {
    const read = readAs(ServerSettings, {
      ServerName: "tm1",
      EncryptionAtRest: { "@odata.type": "#ibm.tm1.api.v1.VaultSettings", KeyRef: "key", ServiceEndpoint: "https://vault" },
    });
    const encryption = JSON.parse(JSON.stringify(ServerSettingsPatch.builder(read).build())).EncryptionAtRest as Record<string, unknown>;
    expect(encryption["@odata.type"]).toBe("#ibm.tm1.api.v1.VaultSettings");
    expect(encryption.KeyRef).toBe("key");
    expect(encryption.ServiceEndpoint).toBe("https://vault");
  });

  it("refuses a create without what TM1 needs", () => {
    expect(() => CubeCreate.builder().name("Sales").build()).toThrow(/Dimensions/);
    const dimensions = ODataPath.of("Dimensions");
    const create = CubeCreate.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build();
    expect(JSON.parse(JSON.stringify(create))["Dimensions@odata.bind"]).toHaveLength(2);
  });

  it("takes one write type for no other", () => {
    // The compiler's check (npm run typecheck): every write type has the same members, so each is
    // told apart by a brand, and an unused @ts-expect-error below fails the typecheck
    const task = ChoreTaskCreate.builder().bindProcess(ODataPath.of("Processes").key("Load")).build();
    const cube = CubeCreate.builder().name("Sales").bindDimensions(ODataPath.of("Dimensions").key("Region")).build();
    const native = NativeViewCreate.builder().name("Default").columns([]).rows([]).build();
    const root = ViewCreate.builder().name("Default").build();
    // @ts-expect-error a create is no patch, of its own type
    const taskPatch: ChoreTaskPatch = task;
    // @ts-expect-error nor of another
    const processPatch: ProcessPatch = cube;
    // @ts-expect-error nor a create of another type
    const cubeCreate: CubeCreate = task;
    // A subtype's create is its root's; the root's isn't the subtype's, nor one subtype's another's
    const view: ViewCreate = native;
    // @ts-expect-error the root's create is no subtype's
    const nativeView: NativeViewCreate = root;
    // @ts-expect-error one subtype's create is no other's
    const mdxView: MDXViewCreate = native;
    // The brand is the compiler's alone: nothing of it is sent, or there at run time
    expect([taskPatch, processPatch, cubeCreate, nativeView, mdxView]).toEqual([task, cube, task, root, native]);
    expect(view).toBeInstanceOf(ViewCreate);
    expect(root).not.toBeInstanceOf(NativeViewCreate);
    expect(Object.keys(cube)).toEqual([]);
    expect(JSON.stringify(task)).toBe(`{"Process@odata.bind":"Processes('Load')"}`);
  });

  it("builds a subtype's write with a builder that is its root's", () => {
    // ViewCreate.builder(read) says it returns a ViewCreateBuilder: it is one, with the root's setters
    const read = readAs(View, { "@odata.type": "#ibm.tm1.api.v1.NativeView", Name: "Default", Columns: [], Rows: [] });
    const builder = ViewCreate.builder(read);
    expect(builder).toBeInstanceOf(ViewCreateBuilder);
    expect(builder).toBeInstanceOf(NativeViewCreateBuilder);
    const create = builder.name("Copy").build();
    expect(create).toBeInstanceOf(NativeViewCreate);
    expect(JSON.parse(JSON.stringify(create))).toEqual({ "@odata.type": "#ibm.tm1.api.v1.NativeView", Name: "Copy", Columns: [], Rows: [] });
    // A builder of the root's own still makes the root's, without @odata.type
    const root = new ViewCreateBuilder().name("Default").build();
    expect(root.constructor).toBe(ViewCreate);
    expect(JSON.stringify(root)).toBe('{"Name":"Default"}');
    // What a subtype's create needs is its own builder's to check
    expect(() => NativeViewCreate.builder().name("Default").build()).toThrow(/Columns/);
  });
});
