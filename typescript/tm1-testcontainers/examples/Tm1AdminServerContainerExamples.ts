// SPDX-License-Identifier: Apache-2.0

import { Tm1AdminServerContainer, Tm1ServerContainer, type Tm1Source } from "@skipcheck/tm1-testcontainers";

// Examples for Tm1AdminServerContainer, quoted in its documentation. Test code, never run here.

export async function severalServers(tm1Source: Tm1Source): Promise<string> {
  // @start region = several-servers
  await using admin = new Tm1AdminServerContainer(tm1Source);
  await using planning = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "planning").withAdminServer(admin);
  await using reporting = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "reporting").withAdminServer(admin);
  await planning.start();
  await reporting.start();
  return admin.getRestUrl() + "Servers"; // lists planning and reporting
  // @end
}
