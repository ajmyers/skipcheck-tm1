import { afterEach, beforeEach, describe, expect } from "vitest";
import {
  Chore,
  ChorePatch,
  ChoreTask,
  ChoreTaskParameterPatch,
  ChoreTaskPatch,
  ODataPath,
  ODataQuery,
  ProcessParameterPatch,
  ProcessPatch,
  Tm1Duration,
  Tm1Exception,
  Tm1PreconditionFailedException,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** ChoreService against a real TM1, with a process that creates the dimension its pName parameter
 * names: the Java client's `ChoreServiceTm1Test`. */
describe("ChoreService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let id: string;
  let process: string;
  let chore: string;

  const parameter = (name: string, value: unknown): ChoreTaskParameterPatch => ChoreTaskParameterPatch.builder().name(name).value(value).build();
  const task = (marker: string): ChoreTaskPatch =>
    ChoreTaskPatch.builder()
      .bindProcess(ODataPath.of("Processes").key(process))
      .parameters([parameter("pName", marker), parameter("pCount", 1)])
      .build();
  const active = async (): Promise<boolean> => (await tm1.chores.get(chore, ODataQuery.none())).Active === true;

  beforeEach(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    id = Live.name().slice("tm1ts_test_".length);
    process = "tm1ts_test_" + id;
    chore = "tm1ts_test_chore_" + id;
    await tm1.processes.create(
      ProcessPatch.builder()
        .name(process)
        .parameters([
          ProcessParameterPatch.builder().name("pName").value("").type("String").build(),
          ProcessParameterPatch.builder().name("pCount").value(0).type("Numeric").build(),
        ])
        .prologProcedure("If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n")
        .build(),
    );
    // Durations as Java's Duration.toString writes them
    await tm1.chores.create(
      ChorePatch.builder()
        .name(chore)
        .startTime("2026-01-01T02:00:00Z")
        .dstSensitive(false)
        .active(false)
        .executionMode("SingleCommit")
        .frequency("PT24H")
        .tasks([task("marker-" + id)])
        .build(),
    );
  });

  afterEach(async () => {
    if (!tm1) return;
    await tm1.chores.delete(chore).catch(() => undefined);
    await tm1.processes.delete(process);
    for (const d of ["marker-" + id, "second-" + id]) await tm1.dimensions.delete(d).catch(() => undefined);
    await tm1.close();
  });

  tm1Test("creates, reads, updates and deletes a chore and its tasks", async () => {
    const read = await tm1.chores.get(
      chore,
      ODataQuery.none().expand(Chore.Fields.Tasks, (t) => t.expand(ChoreTask.Fields.Process)),
    );
    expect(read.Tasks).toHaveLength(1);
    expect(read.Tasks![0]!.Step, "TM1 numbers the steps from 0").toBe(0);
    expect(read.Tasks![0]!.Process?.Name).toBe(process);
    expect((await tm1.chores.taskProcess(chore, 0, ODataQuery.none())).Name).toBe(process);
    expect((await tm1.chores.taskChore(chore, 0, ODataQuery.none())).Name).toBe(chore);

    expect((await tm1.chores.addTask(chore, task("second-" + id))).Step).toBe(1);
    expect((await tm1.chores.tasks(chore, ODataQuery.none()).toArray()).map((t) => t.Step)).toEqual([0, 1]);
    await tm1.chores.updateTask(
      chore,
      1,
      ChoreTaskPatch.builder()
        .parameters([parameter("pName", "third-" + id), parameter("pCount", 2)])
        .build(),
    );
    const tasks = await tm1.chores.tasks(chore, ODataQuery.none()).toArray();
    expect((tasks[1]!.Parameters ?? []).some((p) => p.Value === "third-" + id)).toBe(true);
    await tm1.chores.deleteTask(chore, 0);
    expect(
      (await tm1.chores.tasks(chore, ODataQuery.none()).toArray()).map((t) => t.Step),
      "renumbered from 0",
    ).toEqual([0]);

    const etag = (await tm1.chores.get(chore, ODataQuery.none()))["@odata.etag"];
    expect(etag, "task changes change the chore's ETag").not.toBe(read["@odata.etag"]);
    await tm1.chores.update(chore, ChorePatch.builder().frequency("PT6H").build(), etag);
    expect(Tm1Duration.toMillis((await tm1.chores.get(chore, ODataQuery.none())).Frequency!)).toBe(6 * 3_600_000);
    await expect(tm1.chores.update(chore, ChorePatch.builder().frequency("PT1H").build(), etag)).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
  });

  tm1Test("needs every process parameter in a task", async () => {
    const partial = ChoreTaskPatch.builder()
      .bindProcess(ODataPath.of("Processes").key(process))
      .parameters([parameter("pName", "x")])
      .build();
    await expect(tm1.chores.addTask(chore, partial)).rejects.toBeInstanceOf(Tm1Exception);
  });

  tm1Test("activates by patch, or by the deprecated actions", async () => {
    await tm1.chores.update(chore, ChorePatch.builder().active(true).build());
    expect(await active(), "by PATCH, as IBM recommends").toBe(true);
    await tm1.chores.deactivate(chore);
    expect(await active()).toBe(false);
    await tm1.chores.activate(chore);
    expect(await active()).toBe(true);
    const refused = await tm1.chores.setServerLocalStartTime(chore, "2027-03-04", "05:30").catch((e: unknown) => e);
    expect(refused, "not while it's active").toBeInstanceOf(Tm1Exception);
    expect((refused as Error).message).toContain("active chore");
    await tm1.chores.deactivate(chore);
    await tm1.chores.setServerLocalStartTime(chore, "2027-03-04", "05:30");
    expect((await tm1.chores.get(chore, ODataQuery.none())).StartTime?.slice(0, 10)).toBe("2027-03-04");
  });

  tm1Test("runs the chore now", async () => {
    await tm1.chores.execute(chore);
    const dimension = await tm1.dimensions.get("marker-" + id);
    expect(dimension.Name).toBe("marker-" + id);
  });
});
