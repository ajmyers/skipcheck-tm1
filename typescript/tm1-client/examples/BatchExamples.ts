// SPDX-License-Identifier: Apache-2.0

import { ElementPatch, ODataQuery, Process, type Cube, type Dimension, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for Tm1Batch, quoted in its documentation.

export async function read(tm1: Tm1Client): Promise<[Cube, Dimension, Process[]]> {
  // @start region = read
  // Each call is queued as it's made, and has its result once the batch has run
  const batch = tm1.batch();
  const sales = batch.client.cubes.get("Sales");
  const region = batch.client.dimensions.get("Region");
  const processes = batch.client.processes.list(ODataQuery.none().select(Process.Fields.Name)).toArray();
  await batch.run();
  return Promise.all([sales, region, processes]);
  // @end
}

export async function many(tm1: Tm1Client, cities: string[]): Promise<string[]> {
  // @start region = many
  // One request per element, 200 to a $batch; one element's failure is its own
  const batch = tm1.batch();
  const results = cities.map((city) =>
    batch.client.elements.create("City", "City", ElementPatch.builder().name(city).build()).then(
      (element) => element.Name ?? city,
      (e: unknown) => `${city} failed: ${String(e)}`,
    ),
  );
  await batch.run(200);
  return Promise.all(results);
  // @end
}
