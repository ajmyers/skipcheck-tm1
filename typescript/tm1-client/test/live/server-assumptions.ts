// SPDX-License-Identifier: Apache-2.0

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { type Tm1Capabilities, type Tm1Client, type Tm1Operation, Tm1Version } from "../../src/index.js";
import { Repo } from "../support/repo.js";

/** What a live test needs of the server it runs against, as Java's `ServerAssumptions`, so the suite
 * runs against every supported TM1: a test of what an older server doesn't define is skipped there
 * (`context.skip(why)`), not failed. The server's own `$metadata` decides, as the client decides. */

/** From this TM1 the first Git pull after the remote moves plans the commit TM1 had, and the next
 * the new one; before, the first plans the new one. Tried on every Planning Analytics 2.1 release:
 * 11.8.03200.3 (2.1.13 to 2.1.16) plans the new one, 11.8.03400.2 (2.1.17) the stale, and no build
 * between them was released. */
export const STALE_FIRST_PULL_FROM = Tm1Version.parse("11.8.03400");

/** From this TM1 the server makes a local key file itself, so the image's settings check as valid.
 * Tried at 11.8.035 (invalid) and 11.8.040 (valid); a build between falls on the older side
 * unverified. */
export const MAKES_OWN_KEY_FROM = Tm1Version.parse("11.8.04000");

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

/** Whether the server's product version is at least the version. */
export async function isAtLeast(tm1: Tm1Client, version: Tm1Version): Promise<boolean> {
  return (await productVersion(tm1)).isAtLeast(version);
}

/** The committed snapshot of the server's own schema version, or undefined: its first document,
 * whose elements a later document of the schema has too (Java's `MetadataSnapshotTest` holds them
 * to it). Every supported version has one; a server of a schema without one (a newer TM1) skips the
 * rest of the test (`context.skip`), as Java's `MetadataDriftTm1Test` passes it by. */
export function ownSnapshot(capabilities: Tm1Capabilities): string | undefined {
  const path = join(Repo.root, "java", "tm1-client", "src", "model", "metadata", `tm1-schema-${capabilities.schemaVersion}.xml`);
  return existsSync(path) ? readFileSync(path, "utf8") : undefined;
}

/** The schema version a `$metadata` document's `Core.SchemaVersion` annotation gives, read from its
 * text rather than by the client's parser. */
export function schemaVersionIn(metadata: string): string {
  const found = /Term="Core\.SchemaVersion">\s*<String>([^<]+)<\/String>/.exec(metadata);
  if (!found) throw new Error("$metadata has no Core.SchemaVersion annotation");
  return found[1]!.trim();
}
