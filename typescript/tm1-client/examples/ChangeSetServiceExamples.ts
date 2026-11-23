// SPDX-License-Identifier: Apache-2.0

import { Tm1Client } from "@skipcheck/tm1-client";

// Examples for ChangeSetService, quoted in its documentation.

export async function within(tm1: Tm1Client): Promise<string> {
  // @start region = within
  // Record some writes in a change set, and keep its ID to undo them later
  const cell = { Version: "Forecast", Region: "North", Month: "Jan", Measure: "Revenue" };
  const id = await tm1.changeSets
    .within(async (session) => {
      await Tm1Client.of(session).cells.update("Sales", cell, 1500);
      return session.changeSet();
    })
    .first();
  return id!;
  // @end
}

export function undo(tm1: Tm1Client, changeSet: string): Promise<void> {
  // @start region = undo
  // Revert every write recorded in the change set; writes outside it stay
  return tm1.changeSets.undo(changeSet);
  // @end
}
