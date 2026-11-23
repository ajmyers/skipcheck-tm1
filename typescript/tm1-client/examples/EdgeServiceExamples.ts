import { EdgePatch, type Edge, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for EdgeService, quoted in its documentation.

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // Every parent-child link of the hierarchy, as "parent > child (weight)"
  const edges = await tm1.edges.list("Region", "Region").toArray();
  return edges.map((edge) => `${edge.ParentName} > ${edge.ComponentName} (${edge.Weight})`);
  // @end
}

export function reweight(tm1: Tm1Client): Promise<void> {
  // @start region = reweight
  // Make Cost count negatively in Margin
  return tm1.edges.update("Account", "Account", "Margin", "Cost", EdgePatch.builder().weight(-1).build());
  // @end
}

export async function move(tm1: Tm1Client): Promise<Edge> {
  // @start region = move
  // Move North from Total to Total Sales: add the new edge, then delete the old
  const edge = await tm1.edges.create("Region", "Region", EdgePatch.builder().parentName("Total Sales").componentName("North").weight(1).build());
  await tm1.edges.delete("Region", "Region", "Total", "North");
  return edge;
  // @end
}
