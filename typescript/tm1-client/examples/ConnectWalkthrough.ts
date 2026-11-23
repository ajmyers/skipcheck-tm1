// SPDX-License-Identifier: Apache-2.0

import { Cube, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";

// The walkthrough "Connect and list your cubes" (api-site/content/walkthroughs/tm1-client). The live
// tests run its steps against IBM's SData sample and record what they print (WalkthroughExamples.live.test.ts).

/** Runs the walkthrough's steps in order. */
export async function main(): Promise<void> {
  const tm1 = connect();
  try {
    await printVersion(tm1);
    await listCubes(tm1);
  } finally {
    await close(tm1);
  }
}

export function connect(): Tm1Client {
  // @start region = connect
  // The SData sample's address and login
  const tm1 = Tm1Client.builder().baseUrl("http://localhost:8011/api/v1/").basicAuth("admin", "apple").build();
  // @end
  return tm1;
}

export async function printVersion(tm1: Tm1Client): Promise<void> {
  // @start region = version
  // Each call returns a promise: await waits for its result
  const version = await tm1.session.version();
  console.log(`Connected to TM1 ${version}`);
  // @end
}

export async function listCubes(tm1: Tm1Client): Promise<void> {
  // @start region = list-cubes
  // The cubes you made, without TM1's control cubes; select asks for their names alone
  for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
    console.log(cube.Name);
  }
  // @end
}

export async function close(tm1: Tm1Client): Promise<void> {
  // @start region = close
  // Ends the session on the server
  await tm1.close();
  // @end
}
