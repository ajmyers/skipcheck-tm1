import { afterAll, beforeAll, describe, expect } from "vitest";
import { DimensionPatch, EdgePatch, ElementPatch, ElementType, HierarchyPatch, Member, ODataPath, typeOf, Tm1Request, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** MemberService against a real TM1, on World = Europe (France, Spain) + Asia (Japan): the members
 * half of the Java client's `LevelAndMemberServiceTm1Test` (the levels half is LevelService's). */
describe("MemberService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const d = Live.name();

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    const element = (name: string, type: ElementType): ElementPatch => ElementPatch.builder().name(name).type(type).build();
    const edge = (parent: string, component: string): EdgePatch => EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();
    // Dimensions through the session until DimensionService is ported
    await tm1.session.execute(
      Tm1Request.post(
        ODataPath.of("Dimensions"),
        DimensionPatch.builder()
          .name(d)
          .hierarchies([
            HierarchyPatch.builder()
              .name(d)
              .elements([
                element("World", ElementType.Consolidated),
                element("Europe", ElementType.Consolidated),
                element("Asia", ElementType.Consolidated),
                element("France", ElementType.Numeric),
                element("Spain", ElementType.Numeric),
                element("Japan", ElementType.Numeric),
              ])
              .edges([edge("World", "Europe"), edge("World", "Asia"), edge("Europe", "France"), edge("Europe", "Spain"), edge("Asia", "Japan")])
              .build(),
          ])
          .build(),
      ),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.session.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(d))).catch(() => undefined);
    await tm1.close();
  });

  tm1Test("navigates members", async () => {
    const members = await tm1.members.list(d, d).toArray();
    expect(members).toHaveLength(6);
    for (const m of members) expect(typeOf(Member, m), "a plain member").toBe("Member");
    expect((await tm1.members.get(d, d, "Europe")).UniqueName).toBe(`[${d}].[${d}].[Europe]`);
    expect((await tm1.members.parent(d, d, "France"))?.Name).toBe("Europe");
    expect(await tm1.members.parent(d, d, "World"), "a root has none").toBeUndefined();
    expect((await tm1.members.children(d, d, "Europe").toArray()).map((m) => m.Name)).toEqual(["France", "Spain"]);
    expect((await tm1.members.element(d, d, "Japan")).Type).toBe(ElementType.Numeric);
    expect((await tm1.members.level(d, d, "Japan")).Number).toBe(2);
    expect((await tm1.members.hierarchy(d, d, "Japan")).Name).toBe(d);
  });
});
