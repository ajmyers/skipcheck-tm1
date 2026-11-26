// SPDX-License-Identifier: Apache-2.0

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { Tm1Capabilities, Tm1Client, Tm1Operation, Tm1Version } from "../../src/index.js";
import { Repo } from "../support/repo.js";

/** What a live test needs of the server it runs against, as Java's `ServerAssumptions`, so the suite
 * runs against every supported TM1: a test of what an older server doesn't define is skipped there
 * (`context.skip(why)`), not failed. The server's own `$metadata` decides, as the client decides. */

function described(capabilities: Tm1Capabilities): string {
  return `the server (schema ${capabilities.schemaVersion}, product version ${capabilities.productVersion?.toString()})`;
}

/** Why the test can't go on, if the server doesn't define the operation. */
export async function lacks(tm1: Tm1Client, operation: Tm1Operation): Promise<string | undefined> {
  const capabilities = await tm1.session.capabilities();
  return capabilities.supports(operation.capability()) ? undefined : `${described(capabilities)} doesn't define ${operation.endpoint()}`;
}

/** Why the test can't go on, if the server's operation hasn't the parameter. */
export async function lacksParameter(tm1: Tm1Client, operation: Tm1Operation, parameter: string): Promise<string | undefined> {
  const capabilities = await tm1.session.capabilities();
  return capabilities.supportsParameter(operation.capability(), parameter)
    ? undefined
    : `${described(capabilities)} has no parameter ${parameter} on ${operation.endpoint()}`;
}

/** The server's product version. */
export async function productVersion(tm1: Tm1Client): Promise<Tm1Version> {
  return (await tm1.session.capabilities()).productVersion!;
}

/** The committed snapshot of the server's own schema version: every supported version has one. */
export function ownSnapshot(capabilities: Tm1Capabilities): string {
  const path = join(Repo.root, "java", "tm1-client", "src", "model", "metadata", `tm1-schema-${capabilities.schemaVersion}.xml`);
  if (!existsSync(path)) throw new Error("No metadata snapshot for schema " + capabilities.schemaVersion);
  return readFileSync(path, "utf8");
}
