// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  AttributeDefinitionPatch,
  AttributeOwner,
  AttributeSet,
  AttributesPatch,
  AttributeType,
  ChoreExecutionMode,
  ChorePatch,
  ChoreTaskPatch,
  LocalizedAttributesPatch,
  ODataPath,
  ProcessPatch,
  Tm1Duration,
  type Tm1Client,
} from "../../src/index.js";
import { shortId } from "./attribute-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** Attributes and translations of processes and chores: the Java client's `ProcessAttributeTm1Test`. */
describe("Process and chore attributes on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const process = Live.name();
  const chore = Live.name();
  const processes = ODataPath.of("Processes");

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await tm1.processes.create(ProcessPatch.builder().name(process).prologProcedure("nX = 1;\n").build());
    const definition = ChorePatch.builder()
      .name(chore)
      .startTime("2026-01-01T02:00:00Z")
      .dstSensitive(false)
      .active(false)
      .executionMode(ChoreExecutionMode.SingleCommit)
      .frequency(Tm1Duration.ofMillis(24 * 60 * 60 * 1000))
      .tasks([ChoreTaskPatch.builder().bindProcess(processes.key(process)).build()])
      .build();
    await tm1.chores.create(definition);
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.chores.delete(chore);
    await tm1.processes.delete(process);
    await tm1.close();
  });

  tm1Test.each(["process", "chore"])("defines and translates attributes: %s", async (kind) => {
    const set = kind === "process" ? AttributeSet.processes() : AttributeSet.chores();
    const owner = kind === "process" ? AttributeOwner.process(process) : AttributeOwner.chore(chore);
    const label = "Label " + shortId();
    await tm1.attributes.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.String).build());
    try {
      expect((await tm1.attributes.list(set).toArray()).map((a) => a.Name)).toContain(label);
      await tm1.localizedAttributes.create(
        owner,
        LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().property(label, "Charger").build()).build(),
      );
      expect((await tm1.localizedAttributes.get(owner, "fr")).Attributes?.[label]).toBe("Charger");
    } finally {
      await tm1.attributes.delete(set, label);
    }
  });
});
