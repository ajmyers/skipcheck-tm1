import { Cube, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";

/** The Getting started example on tm1-client's overview page. */
export async function main(): Promise<void> {
  // @start region = hello-world
  const tm1 = Tm1Client.builder()
    .baseUrl("http://localhost:8010/api/v1/")
    .basicAuth("admin", "") // tm1-server's default login
    .build();
  try {
    console.log(`Hello from TM1 ${await tm1.session.version()}`);
    for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
      console.log(`  ${cube.Name}`);
    }
  } finally {
    await tm1.close();
  }
  // @end
}
