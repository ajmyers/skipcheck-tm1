// SPDX-License-Identifier: Apache-2.0

import { Cube, ODataQuery, Tm1Authenticator, Tm1Client } from "@skipcheck/tm1-client";
import { CognosContainer, PlanningAnalyticsEnvironment, Tm1Seed } from "@skipcheck/tm1-testcontainers";

// Examples for PlanningAnalyticsEnvironment, quoted in its documentation.

export async function servers(): Promise<Map<string, string[]>> {
  // @start region = servers
  // Two servers on one Admin Server, and TM1 Web listing both
  await using env = PlanningAnalyticsEnvironment.builder()
    .tm1("planning", (tm1) => tm1.withSeed(Tm1Seed.SDATA))
    .tm1("reporting")
    .tm1Web()
    .build();
  await env.start(); // TM1 Web at env.tm1Web().getUrl(), log on with getAdminHost()
  const cubes = new Map<string, string[]>();
  for (const [name, server] of env.tm1Servers()) {
    await using tm1 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
    const found = await tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name)).toArray();
    cubes.set(
      name,
      found.map((cube) => cube.Name!),
    );
  }
  return cubes; // planning: SData's cubes; reporting: none
  // @end
}

export async function camSecurity(): Promise<string[]> {
  // @start region = cam-security
  // Every server in CAM security, with Cognos's test users as its users
  await using env = PlanningAnalyticsEnvironment.builder()
    .tm1("planning")
    .cognos((cognos) => cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
    .build();
  await env.start();
  await using viewer = Tm1Client.builder()
    .baseUrl(env.tm1("planning").getRestUrl())
    .authenticator(Tm1Authenticator.camNamespace("viewer", "viewerpw", CognosContainer.NAMESPACE))
    .build();
  // The cubes the viewer's groups may see
  const cubes = await viewer.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name)).toArray();
  return cubes.map((cube) => cube.Name!);
  // @end
}

export async function fromConfig(): Promise<string> {
  // @start region = file
  // The environment pa-setup runs from the same file, with random host ports
  await using env = PlanningAnalyticsEnvironment.fromConfig("pa-environment.yaml").build();
  await env.start();
  return env.tm1("planning").getRestUrl();
  // @end
}
