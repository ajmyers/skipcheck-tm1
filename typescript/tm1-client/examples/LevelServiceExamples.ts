import { Member, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for LevelService, quoted in its documentation.

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // Each level's number and how many members it has, as "1: 2"
  const levels = await tm1.levels.list("Region", "Region").toArray();
  return levels.map((level) => `${level.Number}: ${level.Cardinality}`);
  // @end
}

export async function members(tm1: Tm1Client): Promise<string[]> {
  // @start region = members
  // Levels count from the top: level 0 holds the roots, level 1 their children
  const children = await tm1.levels.members("Region", "Region", 1, ODataQuery.none().select(Member.Fields.Name)).toArray();
  return children.map((member) => member.Name!);
  // @end
}
