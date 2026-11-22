import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  DimensionPatch,
  Element,
  ElementPatch,
  ElementType,
  HierarchyPatch,
  ODataPath,
  ODataQuery,
  SubsetPatch,
  SubsetScope,
  Tm1PreconditionFailedException,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** SubsetService against a real TM1, on a dimension of A, B, C and D: the Java client's
 * `SubsetServiceTm1Test`. */
describe("SubsetService on TM1" + liveReason, () => {
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
    const numeric = (name: string): ElementPatch => ElementPatch.builder().name(name).type(ElementType.Numeric).build();
    await tm1.dimensions.create(
      DimensionPatch.builder()
        .name(d)
        .hierarchies([HierarchyPatch.builder().name(d).elements([numeric("A"), numeric("B"), numeric("C"), numeric("D")]).build()])
        .build(),
    );
  });

  afterEach(async () => {
    if (!tm1 || !d) return;
    await tm1.dimensions.delete(d).catch(() => undefined);
  });

  const el = (name: string): ODataPath => ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(name);

  const elements = async (scope: SubsetScope, subset: string): Promise<(string | undefined)[]> =>
    (await tm1.subsets.elements(d, d, scope, subset, ODataQuery.none().select(Element.Fields.Name)).toArray()).map((e) => e.Name);

  const names = async (scope: SubsetScope): Promise<(string | undefined)[]> => (await tm1.subsets.list(d, d, scope).toArray()).map((s) => s.Name);

  tm1Test("creates static and dynamic subsets, reads, updates and deletes them", async () => {
    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("Static").bindElements(el("A"), el("B")).build());
    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("Dynamic").expression(`{TM1SUBSETALL([${d}].[${d}])}`).build());
    expect((await names(SubsetScope.Public)).sort()).toEqual(["Dynamic", "Static"]);
    expect(await elements(SubsetScope.Public, "Static")).toEqual(["A", "B"]);
    expect((await elements(SubsetScope.Public, "Dynamic")).sort()).toEqual(["A", "B", "C", "D"]);

    const etag = (await tm1.subsets.get(d, d, SubsetScope.Public, "Dynamic"))["@odata.etag"]!;
    await tm1.subsets.update(d, d, SubsetScope.Public, "Dynamic", SubsetPatch.builder().expression(`{[${d}].[${d}].[C]}`).build(), etag);
    expect(await elements(SubsetScope.Public, "Dynamic")).toEqual(["C"]);
    await expect(tm1.subsets.update(d, d, SubsetScope.Public, "Dynamic", SubsetPatch.builder().expression("{}").build(), etag)).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
    expect((await tm1.subsets.hierarchy(d, d, SubsetScope.Public, "Static")).Name).toBe(d);

    await tm1.subsets.delete(d, d, SubsetScope.Public, "Static");
    expect(await names(SubsetScope.Public)).toEqual(["Dynamic"]);
  });

  tm1Test("appends and clears elements by name, and refuses them in an update", async () => {
    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("S").bindElements(el("A")).build());
    await tm1.subsets.addElements(d, d, SubsetScope.Public, "S", ["C", "B"]);
    expect(await elements(SubsetScope.Public, "S"), "appended, in order").toEqual(["A", "C", "B"]);

    await tm1.subsets.clearElements(d, d, SubsetScope.Public, "S");
    expect(await elements(SubsetScope.Public, "S")).toEqual([]);

    expect(() => tm1.subsets.update(d, d, SubsetScope.Public, "S", SubsetPatch.builder().bindElements(el("D")).build())).toThrow(/addElements/);
  });

  tm1Test("places elements last, before or after others", async () => {
    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("S").bindElements(el("A"), el("B")).build());
    await tm1.subsets.setElement(d, d, SubsetScope.Public, "S", "C");
    expect(await elements(SubsetScope.Public, "S"), "no position puts it last").toEqual(["A", "B", "C"]);
    await tm1.subsets.setElementBefore(d, d, SubsetScope.Public, "S", "D", "B");
    expect(await elements(SubsetScope.Public, "S")).toEqual(["A", "D", "B", "C"]);
    await tm1.subsets.setElementAfter(d, d, SubsetScope.Public, "S", "A", "B");
    expect(await elements(SubsetScope.Public, "S"), "moved, not added again").toEqual(["D", "B", "A", "C"]);
  });

  tm1Test("keeps private subsets apart and saves copies", async () => {
    await tm1.subsets.create(d, d, SubsetScope.Private, SubsetPatch.builder().name("Mine").bindElements(el("D")).build());
    expect(await names(SubsetScope.Private)).toEqual(["Mine"]);
    expect(await tm1.subsets.list(d, d, SubsetScope.Public).toArray()).toEqual([]);

    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("All").expression(`{TM1SUBSETALL([${d}].[${d}])}`).build());
    expect((await tm1.subsets.saveAs(d, d, SubsetScope.Public, "All", "Copy")).Expression?.trim()).toBeTruthy();
    const frozen = await tm1.subsets.saveAs(d, d, SubsetScope.Public, "All", "Frozen", true, true);
    expect(frozen.Expression ?? "", "static").toBe("");
    expect((await elements(SubsetScope.Private, "Frozen")).sort()).toEqual(["A", "B", "C", "D"]);
  });

  tm1Test("creates session subsets under the server's name", async () => {
    const defined = await tm1.subsets.createSessionSubset(d, d, SubsetPatch.builder().name("Wanted").expression(`{[${d}].[${d}].[B]}`).build());
    expect(defined.Name, "TM1 ignores the name it's given").not.toBe("Wanted");
    expect(await elements(SubsetScope.Session, defined.Name!)).toEqual(["B"]);

    await tm1.subsets.create(d, d, SubsetScope.Public, SubsetPatch.builder().name("S").bindElements(el("C"), el("D")).build());
    const copied = await tm1.subsets.createSessionSubsetFrom(d, d, SubsetScope.Public, "S");
    expect(await elements(SubsetScope.Session, copied.Name!)).toEqual(["C", "D"]);
    expect(await tm1.subsets.list(d, d, SubsetScope.Session).toArray(), "TM1 lists no session subsets, though each reads by its name").toEqual([]);
  });
});
