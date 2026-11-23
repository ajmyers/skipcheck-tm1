// SPDX-License-Identifier: Apache-2.0

import { LogLevel, ODataQuery, Server, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for ServerService, quoted in its documentation.

export async function version(tm1: Tm1Client): Promise<string> {
  // @start region = version
  const server = await tm1.server.server(ODataQuery.none().select(Server.Fields.Name, Server.Fields.ProductVersion));
  return `${server.Name} runs TM1 ${server.ProductVersion}`;
  // @end
}

export async function dataDirectory(tm1: Tm1Client): Promise<string | undefined> {
  // @start region = data-directory
  // What the server runs with now, which can differ from tm1s.cfg until it restarts
  const settings = await tm1.server.activeConfiguration();
  return settings.Administration?.DataBaseDirectory ?? undefined;
  // @end
}

export function loggerLevel(tm1: Tm1Client): Promise<void> {
  // @start region = logger-level
  // Takes effect at once, without a restart
  return tm1.server.setLoggerLevel("TM1.Process", LogLevel.Debug);
  // @end
}
