// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { describe, expect } from "vitest";
import { Json, ODataPath, ODataQuery, Tm1Exception, Tm1NotFoundException, Tm1Request } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { failure, createProcess, execute } from "./raw-tm1.js";

/** The Java client's `Tm1BatchTm1Test`. */
describe("Tm1 batches on TM1" + liveReason, () => {
  tm1Test(
    "runs reads, writes and failures together",
    async () => {
      const tm1 = await Live.client();
      const dimension = "batch" + randomUUID().replace(/-/g, "").slice(0, 8);
      const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
      await tm1.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: dimension, Hierarchies: [{ Name: dimension }] }));
      await tm1.session.execute(createProcess(process, "x = 1;\n"));
      try {
        const batch = tm1.batch();
        const queued = batch.client.session;
        const elements = ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements");
        const cube = queued.execute(Tm1Request.get<{ Name: string }>(ODataPath.of("Cubes").key("}ClientGroups"), ODataQuery.none().select("Name")));
        const missing = queued.execute(Tm1Request.get(ODataPath.of("Cubes").key("not-a-cube")));
        const north = queued.execute(Tm1Request.post(elements, { Name: "North" }, Json) as Tm1Request<{ Name: string }>);
        const twice = queued.execute(Tm1Request.post(elements, { Name: "North" }, Json));
        const listed = queued
          .stream(Tm1Request.list<{ Name: string }>(elements, null))
          .map((e) => e.Name)
          .toArray();
        const run = queued.execute(execute(process));
        expect(batch.size()).toBe(6);
        await batch.run();
        expect((await cube).Name).toBe("}ClientGroups");
        expect(await failure(missing)).toBeInstanceOf(Tm1NotFoundException);
        expect((await north).Name).toBe("North");
        // TM1 carries on past it, in order
        expect(await failure(twice)).toBeInstanceOf(Tm1Exception);
        // Sees the element made before it
        expect(await listed).toEqual(["North"]);
        expect((await run).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
      } finally {
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(dimension)));
        await tm1.close();
      }
    },
    60_000,
  );

  tm1Test(
    "runs requests in order",
    async () => {
      const tm1 = await Live.client();
      const dimension = "batch" + randomUUID().replace(/-/g, "").slice(0, 8);
      await tm1.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: dimension, Hierarchies: [{ Name: dimension }] }));
      try {
        const batch = tm1.batch();
        const queued = batch.client.session;
        const elements = ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements");
        const before: Promise<unknown>[] = [];
        const after: Promise<{ Name: string }>[] = [];
        const created: Promise<unknown>[] = [];
        // Ten times over, so that requests run side by side would show
        for (let i = 0; i < 10; i++) {
          before.push(queued.execute(Tm1Request.get(elements.key("e" + i))));
          created.push(queued.execute(Tm1Request.post(elements, { Name: "e" + i }, Json)));
          after.push(queued.execute(Tm1Request.get<{ Name: string }>(elements.key("e" + i))));
        }
        await batch.run();
        await Promise.all(created);
        for (let i = 0; i < 10; i++) {
          // Runs before the element is made
          expect(await failure(before[i]!)).toBeInstanceOf(Tm1NotFoundException);
          // Sees the element an earlier request made
          expect((await after[i]!).Name).toBe("e" + i);
        }
      } finally {
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(dimension)));
        await tm1.close();
      }
    },
    60_000,
  );

  tm1Test(
    "takes a thousand requests",
    async () => {
      const tm1 = await Live.client();
      try {
        const batch = tm1.batch();
        const reads = Array.from({ length: 1000 }, () =>
          batch.client.session.execute(Tm1Request.get(ODataPath.of("Cubes").key("}ClientGroups"), ODataQuery.none().select("Name"))),
        );
        await batch.run(1000);
        expect(await Promise.all(reads)).toHaveLength(1000);
      } finally {
        await tm1.close();
      }
    },
    60_000,
  );
});
