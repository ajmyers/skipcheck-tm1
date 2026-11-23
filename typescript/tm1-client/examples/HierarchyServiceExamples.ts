import { EdgePatch, ElementPatch, ElementType, HierarchyPatch, Member, ODataQuery, type Hierarchy, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for HierarchyService, quoted in its documentation.

export function create(tm1: Tm1Client): Promise<Hierarchy> {
  // @start region = create
  // A second hierarchy of Region, rolling the same leaves up by manager
  const byManager = HierarchyPatch.builder()
    .name("By Manager")
    .elements([
      ElementPatch.builder().name("Smith").type(ElementType.Consolidated).build(),
      ElementPatch.builder().name("North").type(ElementType.Numeric).build(),
      ElementPatch.builder().name("South").type(ElementType.Numeric).build(),
    ])
    .edges([
      EdgePatch.builder().parentName("Smith").componentName("North").weight(1).build(),
      EdgePatch.builder().parentName("Smith").componentName("South").weight(1).build(),
    ])
    .build();
  return tm1.hierarchies.create("Region", byManager);
  // @end
}

export function replaceStructure(tm1: Tm1Client): Promise<void> {
  // @start region = replace
  // Makes the elements and edges exactly these: TM1 deletes every other element of the
  // hierarchy, with its data
  const all = ElementPatch.builder().name("All Versions").type(ElementType.Consolidated).build();
  const actual = ElementPatch.builder().name("Actual").type(ElementType.Numeric).build();
  const budget = ElementPatch.builder().name("Budget").type(ElementType.Numeric).build();
  return tm1.hierarchies.replaceStructure(
    "Version",
    "Version",
    [all, actual, budget],
    [
      EdgePatch.builder().parentName("All Versions").componentName("Actual").weight(1).build(),
      EdgePatch.builder().parentName("All Versions").componentName("Budget").weight(1).build(),
    ],
  );
  // @end
}

export async function roots(tm1: Tm1Client): Promise<string[]> {
  // @start region = roots
  // The top of the hierarchy: the members with no parent, in order
  const members = await tm1.hierarchies.rootMembers("Region", "Region", ODataQuery.none().select(Member.Fields.Name)).toArray();
  return members.map((member) => member.Name!);
  // @end
}
