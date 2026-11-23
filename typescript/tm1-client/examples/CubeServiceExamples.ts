import { Cube, CubePatch, Filter, ODataPath, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for CubeService, quoted in its documentation.

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // The names of the model cubes starting with "Sales", in name order
  const sales = ODataQuery.none().select(Cube.Fields.Name).filter(Filter.startsWith(Cube.Fields.Name, "Sales")).orderBy(Cube.Fields.Name);
  const cubes = await tm1.cubes.modelCubes(sales).toArray();
  return cubes.map((cube) => cube.Name!);
  // @end
}

export function create(tm1: Tm1Client): Promise<Cube> {
  // @start region = create
  // A cube over two existing dimensions, in this order
  const dimensions = ODataPath.of("Dimensions");
  return tm1.cubes.create(CubePatch.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build());
  // @end
}

export async function updateRules(tm1: Tm1Client): Promise<void> {
  // @start region = update-rules
  // TM1 saves rules even when they don't compile, so check them first
  const rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
  const errors = await tm1.cubes.checkRules("Sales", rules).toArray();
  if (errors.length > 0) throw new Error("The rules don't compile: " + JSON.stringify(errors));
  await tm1.cubes.update("Sales", CubePatch.builder().rules(rules).build());
  // @end
}
