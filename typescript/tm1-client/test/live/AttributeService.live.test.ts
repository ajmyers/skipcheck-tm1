// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  AttributeDefinitionCreate,
  AttributeOwner,
  AttributeSet,
  AttributesPatch,
  AttributeType,
  LocalizedAttributesCreate,
  SubsetCreate,
  SubsetScope,
  Tm1BadRequestException,
  Tm1Request,
  type Tm1Client,
} from "../../src/index.js";
import { createDimension, createSubset, deleteDimension, shortId } from "./attribute-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** AttributeService and LocalizedAttributeService against a real TM1, on a dimension of North and
 * South with a subset S: the Java client's `AttributeServiceTm1Test`. */
describe("AttributeService and LocalizedAttributeService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let d: string;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!Live.configured) return;
    d = Live.name();
    await createDimension(tm1, d, ["North", "South"]);
    await createSubset(tm1, d, SubsetCreate.builder().name("S").expression(`{[${d}].[${d}].[North]}`).build());
  });

  afterEach(async () => {
    if (tm1) await deleteDimension(tm1, d);
  });

  const definitions = (kind: string): AttributeSet =>
    kind === "dimensions" || kind === "dimension"
      ? AttributeSet.dimensions()
      : kind === "hierarchies" || kind === "hierarchy"
        ? AttributeSet.hierarchies(d)
        : kind === "elements" || kind === "element" || kind === "member"
          ? AttributeSet.elements(d, d)
          : AttributeSet.subsets(d, d);

  tm1Test.each(["dimensions", "hierarchies", "elements", "subsets"])("creates, lists, reads and deletes attribute definitions: %s", async (kind) => {
    const set = definitions(kind);
    const name = "Code " + shortId();
    const created = await tm1.attributes.create(set, AttributeDefinitionCreate.builder().name(name).type(AttributeType.String).build());
    expect(created.Type).toBe(AttributeType.String);
    try {
      expect((await tm1.attributes.list(set).toArray()).map((a) => a.Name)).toContain(name);
      expect((await tm1.attributes.get(set, name)).Name).toBe(name);
    } finally {
      await tm1.attributes.delete(set, name);
    }
    expect((await tm1.attributes.list(set).toArray()).map((a) => a.Name)).not.toContain(name);
  });

  tm1Test.each(["dimension", "hierarchy", "element", "member", "subset"])("translates attribute values: %s", async (kind) => {
    const set = definitions(kind);
    const owner =
      kind === "dimension"
        ? AttributeOwner.dimension(d)
        : kind === "hierarchy"
          ? AttributeOwner.hierarchy(d, d)
          : kind === "element" || kind === "member"
            ? AttributeOwner.element(d, d, "North")
            : AttributeOwner.subset(d, d, SubsetScope.Public, "S");
    // A member's translations are its element's, and read only
    const reader = kind === "member" ? AttributeOwner.member(d, d, "North") : owner;
    const label = "Label " + shortId();
    await tm1.attributes.create(set, AttributeDefinitionCreate.builder().name(label).type(AttributeType.String).build());
    const localized = tm1.localizedAttributes;
    const value = async (o: AttributeOwner): Promise<unknown> => (await localized.get(o, "fr")).Attributes?.[label];
    try {
      await localized.create(
        owner,
        LocalizedAttributesCreate.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Nord").build()).build(),
      );
      expect((await localized.list(reader).toArray()).map((t) => t.LocaleID)).toContain("fr");
      expect(await value(reader)).toBe("Nord");

      const changed = LocalizedAttributesCreate.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Le Nord").build()).build();
      const fr = owner.path().nav("LocalizedAttributes").key("fr");
      await expect(localized.create(owner, changed), "a locale can't be created twice").rejects.toBeInstanceOf(Tm1BadRequestException);
      await expect(tm1.session.execute(Tm1Request.patch(fr, changed)), "nor patched").rejects.toBeInstanceOf(Tm1BadRequestException);
      await expect(tm1.session.execute(Tm1Request.delete(fr)), "nor deleted").rejects.toBeInstanceOf(Tm1BadRequestException);
      expect(await value(reader)).toBe("Nord");
    } finally {
      await tm1.attributes.delete(set, label);
    }
  });

  tm1Test("needs the attribute defined, and refuses writes through a member", async () => {
    await expect(
      tm1.localizedAttributes.create(
        AttributeOwner.element(d, d, "North"),
        LocalizedAttributesCreate.builder().localeID("fr").attributes(AttributesPatch.builder().caption("Nord").build()).build(),
      ),
      "Caption isn't an attribute until it's defined",
    ).rejects.toBeInstanceOf(Tm1BadRequestException);
    await expect(
      tm1.localizedAttributes.create(
        AttributeOwner.member(d, d, "North"),
        LocalizedAttributesCreate.builder().localeID("fr").attributes(AttributesPatch.builder().caption("Nord").build()).build(),
      ),
      "POST not supported on a member's",
    ).rejects.toBeInstanceOf(Tm1BadRequestException);
  });
});
