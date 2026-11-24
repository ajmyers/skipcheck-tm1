// SPDX-License-Identifier: Apache-2.0

import {
  type Chore,
  ChoreCreate,
  ChoreExecutionMode,
  ChorePatch,
  ChoreTaskParameterPatch,
  ChoreTaskPatch,
  ODataPath,
  type Tm1Client,
  Tm1Duration,
} from "@skipcheck/tm1-client";

// Examples for ChoreService, quoted in its documentation.

export function create(tm1: Tm1Client): Promise<Chore> {
  // @start region = create
  // A daily chore at 02:00 UTC, inactive until it's switched on. A task gives every
  // parameter of its process a value.
  const loadActuals = ChoreTaskPatch.builder()
    .bindProcess(ODataPath.of("Processes").key("Load Sales"))
    .parameters([
      ChoreTaskParameterPatch.builder().name("pVersion").value("Actual").build(),
      ChoreTaskParameterPatch.builder().name("pYear").value(2026).build(),
    ])
    .build();
  const nightly = ChoreCreate.builder()
    .name("Nightly Load")
    .startTime("2026-01-01T02:00:00Z")
    .dstSensitive(false)
    .frequency(Tm1Duration.ofMillis(24 * 60 * 60 * 1000))
    .executionMode(ChoreExecutionMode.SingleCommit)
    .active(false)
    .tasks([loadActuals])
    .build();
  return tm1.chores.create(nightly);
  // @end
}

export async function addTask(tm1: Tm1Client): Promise<number | undefined> {
  // @start region = add-task
  // A task goes at the end; TM1 numbers the steps from 0
  const loadForecast = ChoreTaskPatch.builder()
    .bindProcess(ODataPath.of("Processes").key("Load Sales"))
    .parameters([
      ChoreTaskParameterPatch.builder().name("pVersion").value("Forecast").build(),
      ChoreTaskParameterPatch.builder().name("pYear").value(2026).build(),
    ])
    .build();
  const task = await tm1.chores.addTask("Nightly Load", loadForecast);
  return task.Step;
  // @end
}

export function activate(tm1: Tm1Client): Promise<void> {
  // @start region = activate
  // Scheduling a chore is a patch of Active
  return tm1.chores.update("Nightly Load", ChorePatch.builder().active(true).build());
  // @end
}
