// SPDX-License-Identifier: Apache-2.0

import { Cube, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";

// The quick start's example on tm1-client's landing page. The live tests run hello() against a new
// tm1-server and record what it prints (HelloWorld.live.test.ts), which the page shows under the code.

// @start region = hello-world
export async function main(): Promise<void> {
  const tm1 = Tm1Client.builder()
    .baseUrl("http://localhost:8010/api/v1/")
    .basicAuth("admin", "") // tm1-server's default login
    .build();
  try {
    await hello(tm1);
  } finally {
    await tm1.close();
  }
}

export async function hello(tm1: Tm1Client): Promise<void> {
  console.log(`Hello from TM1 ${await tm1.session.version()}`);
  for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
    console.log(`  ${cube.Name}`);
  }
}
// @end
