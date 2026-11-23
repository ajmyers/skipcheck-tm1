// SPDX-License-Identifier: Apache-2.0

// The Getting started example on tm1-testcontainers' overview page: a vitest test, type-checked but
// never run.

// @start region = hello-test
import { Tm1Client } from "@skipcheck/tm1-client";
import { requiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { expect } from "vitest";

// Skipped when there's no Docker or no TM1 source; "tm1" is the shared server
requiresTm1("connects", async ({ tm1 }) => {
  await using client = Tm1Client.builder().baseUrl(tm1.getRestUrl()).basicAuth(tm1.getUsername(), tm1.getPassword()).build();
  expect(await client.session.version()).toBeDefined();
});
// @end
