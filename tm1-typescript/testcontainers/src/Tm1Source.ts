import { join } from "node:path";
import { inspect } from "node:util";
import { IllegalArgumentException, parseUrl, Settings, type Lookup } from "./settings.js";

/** Where a {@link Tm1ServerContainer} gets TM1 Server from, as the Java `Tm1Source`.
 *
 * The `tm1-server` image contains no IBM software, so every container needs one of these: an
 * image with TM1 built in ({@link Tm1Source.PrivateImage}), an installed `tm1_64` folder
 * ({@link Tm1Source.InstallDir}), a Docker volume holding one ({@link Tm1Source.InstallVolume}), a
 * folder holding IBM's bundle ({@link Tm1Source.Bundle}), or a download link for the bundle
 * ({@link Tm1Source.BundleUrl}).
 *
 * {@link Tm1Source.discover} finds one from environment variables, so tests don't hard-code
 * paths. The names match the `tm1-server` compose settings, so a `.env` that works for compose
 * also works for tests. The first source found wins, in this order: `TM1_PRIVATE_IMAGE`,
 * `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` (with `TM1_BUNDLE`), `TM1_BUNDLE_URL`
 * (with `TM1_BUNDLE_SHA256`). */
export abstract class Tm1Source {
  /** Settings read by {@link Tm1Source.discover}, in the order they're tried. */
  static readonly PRIVATE_IMAGE = "TM1_PRIVATE_IMAGE";
  /** An installed `tm1_64` folder, for {@link Tm1Source.InstallDir}. */
  static readonly HOME_DIR = "TM1_HOME_DIR";
  /** A Docker volume holding an install, for {@link Tm1Source.InstallVolume}. */
  static readonly INSTALL_VOLUME = "TM1_INSTALL_VOLUME";
  /** A folder holding IBM's bundle, for {@link Tm1Source.Bundle}. */
  static readonly BUNDLE_DIR = "TM1_BUNDLE_DIR";
  /** The bundle's file name within {@link Tm1Source.BUNDLE_DIR}; the newest one if unset. */
  static readonly BUNDLE = "TM1_BUNDLE";
  /** A download link for IBM's bundle, for {@link Tm1Source.BundleUrl}. */
  static readonly BUNDLE_URL = "TM1_BUNDLE_URL";
  /** The SHA-256 to check the {@link Tm1Source.BUNDLE_URL} download against; optional. */
  static readonly BUNDLE_SHA256 = "TM1_BUNDLE_SHA256";

  protected constructor() {}

  /** A short description for logs and messages. Never includes a URL's query string. */
  abstract describe(): string;

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
  static discover(lookup: Lookup = Settings.get): Tm1Source | undefined {
    const get = Settings.trimmed(lookup);
    const image = get(Tm1Source.PRIVATE_IMAGE);
    if (image !== undefined) return new Tm1Source.PrivateImage(image);
    const home = get(Tm1Source.HOME_DIR);
    if (home !== undefined) return new Tm1Source.InstallDir(home);
    const volume = get(Tm1Source.INSTALL_VOLUME);
    if (volume !== undefined) return new Tm1Source.InstallVolume(volume);
    const dir = get(Tm1Source.BUNDLE_DIR);
    if (dir !== undefined) return new Tm1Source.Bundle(dir, get(Tm1Source.BUNDLE));
    const url = get(Tm1Source.BUNDLE_URL);
    if (url !== undefined) return new Tm1Source.BundleUrl(url, get(Tm1Source.BUNDLE_SHA256));
    return undefined;
  }
}

export namespace Tm1Source {
  /** An image built on `tm1-server` with TM1 inside, from a **private** registry. It contains IBM
   * software, so it must never be pushed anywhere public. */
  export class PrivateImage extends Tm1Source {
    /** @param image the image's full name, including its registry and tag */
    constructor(readonly image: string) {
      super();
      Settings.requireText(image, "image");
    }

    describe(): string {
      return "private image " + this.image;
    }
  }

  /** An installed `tm1_64` folder, the one containing `bin64/tm1s.exe`. Mounted read-only. */
  export class InstallDir extends Tm1Source {
    /** The `tm1_64` folder, absolute. */
    readonly dir: string;

    /** @throws IllegalArgumentException if it has no `bin64/tm1s.exe` */
    constructor(dir: string) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isFile(join(this.dir, "bin64", "tm1s.exe"))) {
        throw new IllegalArgumentException(
          `Not a TM1 install: ${this.dir} has no bin64/tm1s.exe. Point ${Tm1Source.HOME_DIR} at the tm1_64 folder of a Linux TM1 Server install.`,
        );
      }
    }

    describe(): string {
      return "install folder " + this.dir;
    }
  }

  /** A named Docker volume holding a TM1 install, such as the one the `tm1-server` compose setup
   * creates (`tm1_tm1-install`). If the volume is empty, the container fails to start. */
  export class InstallVolume extends Tm1Source {
    constructor(readonly name: string) {
      super();
      Settings.requireText(name, "name");
    }

    describe(): string {
      return "install volume " + this.name;
    }
  }

  /** A folder holding IBM's `tm1server-*-linuxi38664h_bundle.tar.gz`, mounted read-only. The
   * image installs TM1 from it on first start into the shared cache volume, so later starts are
   * fast. */
  export class Bundle extends Tm1Source {
    /** The folder holding the bundle, absolute. */
    readonly dir: string;
    /** The bundle's file name within {@link dir}, or `undefined` for the newest one. */
    readonly file: string | undefined;

    /** @throws IllegalArgumentException if the folder isn't there, or the file isn't in it */
    constructor(dir: string, file?: string | null) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isDirectory(this.dir)) {
        throw new IllegalArgumentException(
          `${Tm1Source.BUNDLE_DIR} is not a folder: ${this.dir}. Point it at the folder holding the bundle; name the file itself with ${Tm1Source.BUNDLE}.`,
        );
      }
      this.file = file === undefined || file === null || file.trim() === "" ? undefined : file;
      if (this.file !== undefined && !Settings.isFile(join(this.dir, this.file))) {
        throw new IllegalArgumentException(`${Tm1Source.BUNDLE} names ${this.file}, which isn't in ${this.dir}`);
      }
    }

    describe(): string {
      return "bundle " + (this.file !== undefined ? join(this.dir, this.file) : this.dir + " (newest)");
    }
  }

  /** A download link for IBM's bundle, such as a pre-signed S3, R2 or GCS URL. Links can carry
   * credentials in the query string (pre-signed links) or the user-info part
   * (`https://user:token@host/...`), so neither is ever logged. */
  export class BundleUrl extends Tm1Source {
    /** The link to download the bundle from. */
    readonly url: URL;
    /** The bundle's SHA-256, checked before installing; `undefined` to skip. */
    readonly sha256: string | undefined;
    readonly #text: string;

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
      return "bundle URL " + Settings.safe(this.url);
    }
  }
}
