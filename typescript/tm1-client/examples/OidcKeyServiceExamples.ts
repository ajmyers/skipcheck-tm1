import type { Tm1Client } from "@skipcheck/tm1-client";

// Examples for OidcKeyService, quoted in its documentation.

export async function refresh(tm1: Tm1Client): Promise<void> {
  // @start region = refresh
  // After the provider rotates its signing keys: drop every cached key, so TM1 fetches
  // them again
  const keys = await tm1.oidcKeys.keys().toArray();
  await tm1.oidcKeys.remove(keys);
  // @end
}
