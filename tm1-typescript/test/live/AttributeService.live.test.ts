import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  AttributeDefinitionPatch,
  AttributeOwner,
  AttributeSet,
  AttributesPatch,
  AttributeType,
  ChoreExecutionMode,
  ChorePatch,
  ChoreTaskPatch,
  CubePatch,
  DimensionPatch,
  ElementPatch,
  ElementType,
  HierarchyPatch,
  LocalizedAttributesPatch,
  MDXViewPatch,
  NativeViewPatch,
  ODataPath,
  ProcessPatch,
  SubsetPatch,
  SubsetScope,
  Tm1BadRequestException,
  Tm1Duration,
  Tm1Request,
  ViewAxisSelectionPatch,
  ViewScope,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

// Dimensions, subsets, views, processes and chores through the session until their services are ported
const DIMENSIONS = ODataPath.of("Dimensions");

function createDimension(tm1: Tm1Client, name: string, elements: string[]): Promise<unknown> {
  const hierarchy = HierarchyPatch.builder()
    .name(name)
    .elements(elements.map((e) => ElementPatch.builder().name(e).type(ElementType.Numeric).build()))
    .build();
  return tm1.session.execute(Tm1Request.post(DIMENSIONS, DimensionPatch.builder().name(name).hierarchies([hierarchy]).build()));
}

function createSubset(tm1: Tm1Client, dimension: string, subset: SubsetPatch): Promise<unknown> {
  const path = DIMENSIONS.key(dimension).nav("Hierarchies").key(dimension).nav(SubsetScope.navigation(SubsetScope.Public));
  return tm1.session.execute(Tm1Request.post(path, subset));
}

function deleteDimension(tm1: Tm1Client, name: string): Promise<void> {
  return tm1.session.execute(Tm1Request.delete(DIMENSIONS.key(name)));
}

const shortId = (): string => randomUUID().slice(0, 4);

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
    await createSubset(tm1, d, SubsetPatch.builder().name("S").expression(`{[${d}].[${d}].[North]}`).build());
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
    const created = await tm1.attributes.create(set, AttributeDefinitionPatch.builder().name(name).type(AttributeType.String).build());
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
    await tm1.attributes.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.String).build());
    const localized = tm1.localizedAttributes;
    const value = async (o: AttributeOwner): Promise<unknown> => (await localized.get(o, "fr")).Attributes?.[label];
    try {
      await localized.create(owner, LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Nord").build()).build());
      expect((await localized.list(reader).toArray()).map((t) => t.LocaleID)).toContain("fr");
      expect(await value(reader)).toBe("Nord");

      const changed = LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Le Nord").build()).build();
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
      tm1.localizedAttributes.create(AttributeOwner.element(d, d, "North"), LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().caption("Nord").build()).build()),
      "Caption isn't an attribute until it's defined",
    ).rejects.toBeInstanceOf(Tm1BadRequestException);
    await expect(tm1.localizedAttributes.create(AttributeOwner.member(d, d, "North"), LocalizedAttributesPatch.builder().localeID("fr").build()), "POST not supported on a member's").rejects.toBeInstanceOf(Tm1BadRequestException);
  });
});

/** Attributes and translations of cubes and views: the Java client's `CubeAttributeTm1Test`. */
describe("Cube and view attributes on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const dimensions = [cube + "_a", cube + "_b"];

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    for (const d of dimensions) await createDimension(tm1, d, ["X"]);
    await tm1.cubes.create(CubePatch.builder().name(cube).bindDimensions(...dimensions.map((d) => DIMENSIONS.key(d))).build());
    const views = ODataPath.of("Cubes").key(cube).nav(ViewScope.navigation(ViewScope.Public));
    const [a, b] = dimensions as [string, string];
    await tm1.session.execute(Tm1Request.post(views, MDXViewPatch.builder().name("V").mdx(`SELECT {[${a}].[${a}].Members} ON 0 FROM [${cube}]`).build()));
    for (const d of dimensions) await createSubset(tm1, d, SubsetPatch.builder().name("All").expression(`{TM1SUBSETALL([${d}].[${d}])}`).build());
    await tm1.session.execute(Tm1Request.post(views, NativeViewPatch.builder().name("N").columns([axis(a)]).rows([axis(b)]).build()));
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
      await tm1.localizedAttributes.create(owner, LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Ventes").build()).build());
      const fr = await tm1.localizedAttributes.get(owner, "fr");
      expect(fr.Attributes?.[label]).toBe("Ventes");
    } finally {
      await tm1.attributes.delete(set, label);
    }
  });
});

/** Attributes and translations of processes and chores: the Java client's `ProcessAttributeTm1Test`. */
describe("Process and chore attributes on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const process = Live.name();
  const chore = Live.name();
  const processes = ODataPath.of("Processes");
  const chores = ODataPath.of("Chores");

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await tm1.session.execute(Tm1Request.post(processes, ProcessPatch.builder().name(process).prologProcedure("nX = 1;\n").build()));
    const definition = ChorePatch.builder()
      .name(chore)
      .startTime("2026-01-01T02:00:00Z")
      .dstSensitive(false)
      .active(false)
      .executionMode(ChoreExecutionMode.SingleCommit)
      .frequency(Tm1Duration.ofMillis(24 * 60 * 60 * 1000))
      .tasks([ChoreTaskPatch.builder().bindProcess(processes.key(process)).build()])
      .build();
    await tm1.session.execute(Tm1Request.post(chores, definition));
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.session.execute(Tm1Request.delete(chores.key(chore)));
    await tm1.session.execute(Tm1Request.delete(processes.key(process)));
    await tm1.close();
  });

  tm1Test.each(["process", "chore"])("defines and translates attributes: %s", async (kind) => {
    const set = kind === "process" ? AttributeSet.processes() : AttributeSet.chores();
    const owner = kind === "process" ? AttributeOwner.process(process) : AttributeOwner.chore(chore);
    const label = "Label " + shortId();
    await tm1.attributes.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.String).build());
    try {
      expect((await tm1.attributes.list(set).toArray()).map((a) => a.Name)).toContain(label);
      await tm1.localizedAttributes.create(owner, LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Charger").build()).build());
      expect((await tm1.localizedAttributes.get(owner, "fr")).Attributes?.[label]).toBe("Charger");
    } finally {
      await tm1.attributes.delete(set, label);
    }
  });
});
