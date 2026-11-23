// SPDX-License-Identifier: Apache-2.0

import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { IllegalArgumentException, Settings, type Lookup } from "./settings.js";

const PREFIX = "ipa_workspace_local_";

/** Where a {@link PawContainer} gets Planning Analytics Workspace from: IBM's PAW Local zip,
 * `ipa_workspace_local_<release>.<build>.zip`, as the Java record `PawSource`. */
export class PawSource {
  /** The PAW Local zip to use. */
  static readonly ZIP = "PAW_ZIP";

  /** A folder holding PAW Local zips: the newest is used. */
  static readonly BUNDLE_DIR = "PAW_BUNDLE_DIR";

  /** The zip, absolute. */
  readonly zip: string;

  /** @throws IllegalArgumentException if it isn't a file */
  constructor(zip: string) {
    this.zip = Settings.absolute(zip);
    if (!Settings.isFile(this.zip)) {
      throw new IllegalArgumentException(
        `Not a file: ${this.zip}. Point ${PawSource.ZIP} at IBM's ipa_workspace_local_<release>.<build>.zip, or ${PawSource.BUNDLE_DIR} at its folder.`,
      );
    }
  }

  /** A short description for logs and messages. */
  describe(): string {
    return "PAW Local zip " + this.zip;
  }

  /** The description ({@link describe}), for logs. */
  toString(): string {
    return this.describe();
  }

  /** Finds the zip set with `PAW_ZIP`, or else the newest in `PAW_BUNDLE_DIR`, using `lookup`
   * (environment variables by default).
   * @returns the source, or `undefined` when neither is set
   * @throws IllegalArgumentException if a setting is present but wrong */
  static discover(lookup: Lookup = Settings.get): PawSource | undefined {
    const zip = Settings.text(lookup(PawSource.ZIP));
    if (zip !== undefined) return new PawSource(zip);
    const dir = Settings.text(lookup(PawSource.BUNDLE_DIR));
    if (dir !== undefined) return new PawSource(newestZip(dir));
    return undefined;
  }
}

function newestZip(dir: string): string {
  if (!Settings.isDirectory(dir)) throw new IllegalArgumentException(`${PawSource.BUNDLE_DIR} is not a folder: ${dir}`);
  let newest: { path: string; modified: number } | undefined;
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (!name.startsWith(PREFIX) || !name.endsWith(".zip") || !Settings.isFile(path)) continue;
    const modified = statSync(path).mtimeMs;
    if (!newest || modified > newest.modified) newest = { path, modified };
  }
  if (!newest) throw new IllegalArgumentException(`${PawSource.BUNDLE_DIR} has no ${PREFIX}*.zip: ${Settings.absolute(dir)}`);
  return newest.path;
}
