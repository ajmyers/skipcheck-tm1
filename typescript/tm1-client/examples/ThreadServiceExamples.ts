// SPDX-License-Identifier: Apache-2.0

import { Filter, ODataQuery, Thread, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for ThreadService, quoted in its documentation.

export async function busy(tm1: Tm1Client): Promise<string[]> {
  // @start region = busy
  // What the server is doing now: every thread that isn't idle
  const notIdle = ODataQuery.none()
    .select(Thread.Fields.ID, Thread.Fields.Name, Thread.Fields.Function, Thread.Fields.ElapsedTime)
    .filter(Thread.Fields.State.ne("Idle"));
  const threads = await tm1.threads.list(notIdle).toArray();
  return threads.map((thread) => `${thread.Name}: ${thread.Function} for ${thread.ElapsedTime}`);
  // @end
}

export async function cancelProcess(tm1: Tm1Client): Promise<void> {
  // @start region = cancel-process
  // A process run through REST shows as its request, not by its name
  const running = ODataQuery.none().select(Thread.Fields.ID).filter(Filter.contains(Thread.Fields.Function, "Processes('Sales.Load')/tm1.ExecuteWithReturn"));
  for await (const thread of tm1.threads.list(running)) await tm1.threads.cancel(thread.ID!);
  // @end
}
