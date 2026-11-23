// SPDX-License-Identifier: Apache-2.0

import { LogLevel, ODataQuery, type MessageLogEntry, type Tm1Client, type Tm1Stream } from "@skipcheck/tm1-client";

// Examples for LogService, quoted in its documentation.

export function newest(tm1: Tm1Client): Tm1Stream<MessageLogEntry> {
  // @start region = newest
  // The 50 newest entries, newest first
  return tm1.logs.messageLog(true, ODataQuery.none().top(50));
  // @end
}

export async function follow(tm1: Tm1Client, signal: AbortSignal): Promise<void> {
  // @start region = follow
  // Errors as they're written, polled every 5 s, until the signal is aborted
  for await (const entry of tm1.logs.tailMessageLog(5000, { signal })) {
    if (entry.Level === LogLevel.Error) console.error(`${entry.Logger}: ${entry.Message}`);
  }
  // @end
}

export async function processErrors(tm1: Tm1Client): Promise<string[]> {
  // @start region = process-errors
  // What each error log of one process says
  const contents: string[] = [];
  for await (const file of tm1.logs.errorLogFiles()) {
    if (file.Filename?.endsWith("_Load Sales.log")) contents.push(await tm1.logs.errorLogContent(file.Filename));
  }
  return contents;
  // @end
}

export async function changes(tm1: Tm1Client): Promise<number> {
  // @start region = changes
  // How many cell changes the transaction log holds, leaving out its status lines
  let count = 0;
  for await (const _ of tm1.logs.transactionLog(false, true)) count++;
  return count;
  // @end
}
