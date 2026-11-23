import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  AttributeDefinitionPatch,
  AttributeOwner,
  AttributeSet,
  AttributesPatch,
  AttributeType,
  CubePatch,
  LocalizedAttributesPatch,
  MDXViewPatch,
  NativeViewPatch,
  SubsetPatch,
  ViewAxisSelectionPatch,
  ViewScope,
  type Tm1Client,
} from "../../src/index.js";
import { DIMENSIONS, createDimension, createSubset, deleteDimension, shortId } from "./attribute-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** Attributes and translations of cubes and views: the Java client's `CubeAttributeTm1Test`. */
describe("Cube and view attributes on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const dimensions = [cube + "_a", cube + "_b"];

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    for (const d of dimensions) await createDimension(tm1, d, ["X"]);
    await tm1.cubes.create(
      CubePatch.builder()
        .name(cube)
        .bindDimensions(...dimensions.map((d) => DIMENSIONS.key(d)))
        .build(),
    );
    const [a, b] = dimensions as [string, string];
    await tm1.views.create(cube, ViewScope.Public, MDXViewPatch.builder().name("V").mdx(`SELECT {[${a}].[${a}].Members} ON 0 FROM [${cube}]`).build());
    for (const d of dimensions) await createSubset(tm1, d, SubsetPatch.builder().name("All").expression(`{TM1SUBSETALL([${d}].[${d}])}`).build());
    await tm1.views.create(
      cube,
      ViewScope.Public,
      NativeViewPatch.builder()
        .name("N")
        .columns([axis(a)])
        .rows([axis(b)])
        .build(),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube);
    for (const d of dimensions) await deleteDimension(tm1, d);
    await tm1.close();
  });

  const axis = (dimension: string): ViewAxisSelectionPatch =>
    ViewAxisSelectionPatch.builder().bindSubset(DIMENSIONS.key(dimension).nav("Hierarchies").key(dimension).nav("Subsets").key("All")).build();

  tm1Test.each(["cube", "view"])("defines and translates attributes: %s", async (kind) => {
    const set = kind === "cube" ? AttributeSet.cubes() : AttributeSet.views(cube);
    const owner = kind === "cube" ? AttributeOwner.cube(cube) : AttributeOwner.view(cube, ViewScope.Public, "V");
    const label = "Label " + shortId();
    await tm1.attributes.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.String).build());
    try {
      expect((await tm1.attributes.list(set).toArray()).map((a) => a.Name)).toContain(label);
      await tm1.localizedAttributes.create(
        owner,
        LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Ventes").build()).build(),
      );
      const fr = await tm1.localizedAttributes.get(owner, "fr");
      expect(fr.Attributes?.[label]).toBe("Ventes");
    } finally {
      await tm1.attributes.delete(set, label);
    }
  });
});
