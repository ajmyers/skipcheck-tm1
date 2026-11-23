import { Cube, ODataQuery, Tm1Client, Tm1Version } from "@skipcheck/tm1-client";
import { Tm1Seed, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";

// Examples for Tm1ServerContainer, quoted in its documentation.

export function client(server: Tm1ServerContainer): Tm1Client {
  // @start region = client
  // The container's REST URL and login, in TM1's own security
  return Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
  // @end
}

export async function sample(): Promise<string[]> {
  // @start region = sample
  const source = Tm1Source.discover();
  if (!source) throw new Error("Set TM1_HOME_DIR or TM1_BUNDLE_DIR");
  // SData's login is admin / apple, and the server must be this version
  await using server = new Tm1ServerContainer(source).withSeed(Tm1Seed.SDATA).withExpectedVersion(Tm1Version.parse("11.8.03500.6"));
  await server.start();
  // Declared after the server, so it's closed before the container stops
  await using tm1 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
  // SData's cubes, such as SalesCube
  const cubes = await tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name)).toArray();
  return cubes.map((cube) => cube.Name!);
  // @end
}

export async function ownDatabase(source: Tm1Source): Promise<string> {
  // @start region = own-database
  // A fixture model (.cub, .dim, .pro files and so on), copied in before TM1 starts
  await using server = new Tm1ServerContainer(source).withData("test/fixtures/tm1/sales").withCredentials("admin", "apple"); // a login the database has
  await server.start();
  return server.getRestUrl(); // http://localhost:54321/api/v1/
  // @end
}
