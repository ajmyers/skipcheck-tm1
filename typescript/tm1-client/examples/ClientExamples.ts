// SPDX-License-Identifier: Apache-2.0

import { Cube, ODataQuery, Tm1Client, type ProcessExecuteResult } from "@skipcheck/tm1-client";

// Examples for Tm1Client, quoted in its documentation.

export async function connect(): Promise<void> {
  // @start region = connect
  const tm1 = Tm1Client.builder().baseUrl("http://localhost:8010/api/v1/").basicAuth("admin", "apple").build();
  try {
    for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
      console.log(cube.Name);
    }
  } finally {
    await tm1.close(); // once the list is done, not alongside it
  }
  // @end
}

export function async(tm1: Tm1Client, signal: AbortSignal): Promise<ProcessExecuteResult> {
  // @start region = async
  // TM1 runs it in the background and the client polls for the result every 2 seconds, so
  // no request stays open. Aborting the signal stops the process on TM1.
  return tm1.withAsync(2000).processes.executeWithReturn("Rebuild Forecast", {}, null, { signal });
  // @end
}
