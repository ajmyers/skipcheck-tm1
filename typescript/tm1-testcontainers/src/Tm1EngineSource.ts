// SPDX-License-Identifier: Apache-2.0

import { join } from "node:path";
import { inspect } from "node:util";
import { IllegalArgumentException, parseUrl, Settings, type Lookup } from "./settings.js";
import { Tm1Source } from "./Tm1Source.js";

/** Where a {@link Tm1EngineContainer} gets TM1 v12 from, as the Java `Tm1EngineSource`.
 *
 * The `tm1-engine` image contains no IBM software, so every container needs one of these. They
 * mirror the image's own sources (see the image's README), and {@link Tm1Source}, which does the
 * same for TM1 11.
 *
 * {@link Tm1EngineSource.discover} finds one from environment variables, so tests don't hard-code
 * paths. The first source found wins, in this order:
 *
 * 1. `TM1_ENGINE_PRIVATE_IMAGE`: an image with TM1 built in ({@link Tm1EngineSource.PrivateImage})
 * 2. `TM1_ENGINE_HOME_DIR`: an install the image made, as a folder ({@link Tm1EngineSource.InstallDir})
 * 3. `TM1_ENGINE_INSTALL_VOLUME`: a Docker volume holding such an install
 *    ({@link Tm1EngineSource.InstallVolume})
 * 4. `TM1_ENGINE_BUNDLE_DIR`, optionally with `TM1_ENGINE_BUNDLE`: a folder holding IBM's package,
 *    and the file name to use from it ({@link Tm1EngineSource.Bundle})
 * 5. `TM1_ENGINE_BUNDLE_URL`, optionally with `TM1_ENGINE_BUNDLE_SHA256`: a download link for the
 *    package ({@link Tm1EngineSource.BundleUrl})
 * 6. `TM1_BUNDLE_DIR`, TM1 11's downloads folder, when it holds a TM1 v12 package too: one folder
 *    then serves both versions */
export abstract class Tm1EngineSource {
  /** Settings read by {@link Tm1EngineSource.discover}, in the order they're tried. */
  static readonly PRIVATE_IMAGE = "TM1_ENGINE_PRIVATE_IMAGE";
  /** An install the image made, as a folder, for {@link Tm1EngineSource.InstallDir}. */
  static readonly HOME_DIR = "TM1_ENGINE_HOME_DIR";
  /** A Docker volume holding an install, for {@link Tm1EngineSource.InstallVolume}. */
  static readonly INSTALL_VOLUME = "TM1_ENGINE_INSTALL_VOLUME";
  /** A folder holding IBM's package, for {@link Tm1EngineSource.Bundle}. */
  static readonly BUNDLE_DIR = "TM1_ENGINE_BUNDLE_DIR";
  /** The package's file name within {@link Tm1EngineSource.BUNDLE_DIR}; the newest one if unset. */
  static readonly BUNDLE = "TM1_ENGINE_BUNDLE";
  /** A download link for IBM's package, for {@link Tm1EngineSource.BundleUrl}. */
  static readonly BUNDLE_URL = "TM1_ENGINE_BUNDLE_URL";
  /** The SHA-256 to check the {@link Tm1EngineSource.BUNDLE_URL} download against; optional. */
  static readonly BUNDLE_SHA256 = "TM1_ENGINE_BUNDLE_SHA256";

  protected constructor() {}

  /** A short description for logs and messages. Never includes a URL's query string. */
  abstract describe(): string;

  /** The description ({@link describe}), for logs. */
  toString(): string {
    return this.describe();
  }

  /** As {@link toString}: `console.log` never shows a URL's secrets either. */
  [inspect.custom](): string {
    return this.constructor.name + "[" + this.describe() + "]";
  }

  /** Finds a source using `lookup` to read each setting (environment variables by default).
   * Blank values count as unset.
   * @returns the first source found, or `undefined` if none is configured
   * @throws IllegalArgumentException if a setting is present but invalid, such as a folder that
   * doesn't exist. A wrong setting fails rather than silently skipping the tests. */
  static discover(lookup: Lookup = Settings.get): Tm1EngineSource | undefined {
    const get = Settings.trimmed(lookup);
    const image = get(Tm1EngineSource.PRIVATE_IMAGE);
    if (image !== undefined) return new Tm1EngineSource.PrivateImage(image);
    const home = get(Tm1EngineSource.HOME_DIR);
    if (home !== undefined) return new Tm1EngineSource.InstallDir(home);
    const volume = get(Tm1EngineSource.INSTALL_VOLUME);
    if (volume !== undefined) return new Tm1EngineSource.InstallVolume(volume);
    const dir = get(Tm1EngineSource.BUNDLE_DIR);
    if (dir !== undefined) return new Tm1EngineSource.Bundle(dir, get(Tm1EngineSource.BUNDLE));
    const url = get(Tm1EngineSource.BUNDLE_URL);
    if (url !== undefined) return new Tm1EngineSource.BundleUrl(url, get(Tm1EngineSource.BUNDLE_SHA256));
    // The TM1 downloads folder, when it holds TM1 v12 too. Skipped quietly when it doesn't: that
    // folder is TM1 11's setting, and is valid without v12 in it
    const tm1Dir = get(Tm1Source.BUNDLE_DIR);
    if (
      tm1Dir !== undefined &&
      (get(Tm1EngineSource.BUNDLE) !== undefined || Settings.hasFile(tm1Dir, (n) => n.startsWith("tm1-v") && n.endsWith("-standalone-linux.tgz")))
    ) {
      return new Tm1EngineSource.Bundle(tm1Dir, get(Tm1EngineSource.BUNDLE));
    }
    return undefined;
  }
}

/** The types nested in {@link Tm1EngineSource}, as in Java. */
export namespace Tm1EngineSource {
  /** An image built on `tm1-engine` with TM1 inside, from a **private** registry. It contains IBM
   * software, so it must never be pushed anywhere public. */
  export class PrivateImage extends Tm1EngineSource {
    /** @param image the image's full name, including its registry and tag */
    constructor(readonly image: string) {
      super();
      Settings.requireText(image, "image");
    }

    describe(): string {
      return "private image " + this.image;
    }
  }

  /** A TM1 v12 install the `tm1-engine` image made (`install` as its command), as a folder: the
   * one containing `bin/tm1sd.exe`. Mounted read-only. An install made by IBM's installer isn't
   * one. */
  export class InstallDir extends Tm1EngineSource {
    /** The install folder, absolute. */
    readonly dir: string;

    /** @throws IllegalArgumentException if it has no `bin/tm1sd.exe` */
    constructor(dir: string) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isFile(join(this.dir, "bin", "tm1sd.exe"))) {
        throw new IllegalArgumentException(
          `Not a TM1 v12 install: ${this.dir} has no bin/tm1sd.exe. Point ${Tm1EngineSource.HOME_DIR} at an install the tm1-engine image made.`,
        );
      }
    }

    describe(): string {
      return "install folder " + this.dir;
    }
  }

  /** A named Docker volume holding an install the `tm1-engine` image made. If the volume is
   * empty, the container fails to start, since there's nothing to install from. */
  export class InstallVolume extends Tm1EngineSource {
    /** @param name the volume's name */
    constructor(readonly name: string) {
      super();
      Settings.requireText(name, "name");
    }

    describe(): string {
      return "install volume " + this.name;
    }
  }

  /** A folder holding IBM's `tm1-v<version>-standalone-linux.tgz`, mounted read-only. The image
   * installs TM1 from it on first start into a shared cache volume, so later starts are fast. The
   * folder can hold TM1 11, TM1 Web and Cognos downloads too: each image picks its own. */
  export class Bundle extends Tm1EngineSource {
    /** The folder holding the package, absolute. */
    readonly dir: string;
    /** The package's file name within {@link dir}, or `undefined` for the newest one. */
    readonly file: string | undefined;

    /** @throws IllegalArgumentException if the folder isn't there, or the file isn't in it */
    constructor(dir: string, file?: string | null) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isDirectory(this.dir)) {
        throw new IllegalArgumentException(
          `${Tm1EngineSource.BUNDLE_DIR} is not a folder: ${this.dir}. Point it at the folder holding the package; name the file itself with ${Tm1EngineSource.BUNDLE}.`,
        );
      }
      this.file = file === undefined || file === null || file.trim() === "" ? undefined : file;
      if (this.file !== undefined && !Settings.isFile(join(this.dir, this.file))) {
        throw new IllegalArgumentException(`${Tm1EngineSource.BUNDLE} names ${this.file}, which isn't in ${this.dir}`);
      }
    }

    describe(): string {
      return "package " + (this.file !== undefined ? join(this.dir, this.file) : this.dir + " (newest)");
    }
  }

  /** A download link for IBM's package, such as a pre-signed S3, R2 or GCS URL. Links can carry
   * credentials in the query string or the user-info part, so neither is ever logged. */
  export class BundleUrl extends Tm1EngineSource {
    /** The link to download the package from. */
    readonly url: URL;
    /** The package's SHA-256, checked before installing; `undefined` to skip. */
    readonly sha256: string | undefined;
    readonly #text: string;

    /** @param url the link to download the package from
     * @param sha256 the package's SHA-256; blank counts as none */
    constructor(url: URL | string, sha256?: string | null) {
      super();
      const parsed = parseUrl(url);
      this.url = parsed.url;
      this.#text = parsed.text;
      this.sha256 = sha256 === undefined || sha256 === null || sha256.trim() === "" ? undefined : sha256;
    }

    /** The link as given, for the container.
     * @internal */
    get text(): string {
      return this.#text;
    }

    describe(): string {
      // Host and port only: the authority would include any user:password@
      return "package URL " + Settings.safe(this.url);
    }
  }
}
