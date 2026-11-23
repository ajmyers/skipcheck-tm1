import type { Tm1Client } from "@skipcheck/tm1-client";

// Examples for SqlDataSourceService, quoted in its documentation.

export async function names(tm1: Tm1Client): Promise<string[]> {
  // @start region = names
  // The ODBC sources a process could read from, on TM1's host
  const sources = await tm1.sqlDataSources.list().toArray();
  return sources.map((source) => source.Name!);
  // @end
}
