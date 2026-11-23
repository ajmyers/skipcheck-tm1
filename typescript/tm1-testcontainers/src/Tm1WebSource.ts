import { join } from "node:path";
import { inspect } from "node:util";
import { IllegalArgumentException, parseUrl, Settings, type Lookup } from "./settings.js";
import { Tm1Source } from "./Tm1Source.js";

/** Where a {@link Tm1WebContainer} gets TM1 Web from, as {@link Tm1Source} for TM1 and the Java
 * `Tm1WebSource`: an image with TM1 Web built in, an installed `tm1web` folder, a volume holding
 * one, a folder holding IBM's TM1 Web bundle, or a download link for it.
 * {@link Tm1WebSource.discover} tries `TM1_WEB_PRIVATE_IMAGE`, `TM1_WEB_HOME_DIR`,
 * `TM1_WEB_INSTALL_VOLUME`, `TM1_WEB_BUNDLE_DIR` (with `TM1_WEB_BUNDLE`), `TM1_WEB_BUNDLE_URL`
 * (with `TM1_WEB_BUNDLE_SHA256`), then `TM1_BUNDLE_DIR` when that folder holds a
 * `tm1web-*_bundle.tar.gz` or `tm1web-*-linuxi38664h.zip`. */
export abstract class Tm1WebSource {
  /** Settings read by {@link Tm1WebSource.discover}, in the order they're tried. */
  static readonly PRIVATE_IMAGE = "TM1_WEB_PRIVATE_IMAGE";
  /** An installed `tm1web` folder, for {@link Tm1WebSource.InstallDir}. */
  static readonly HOME_DIR = "TM1_WEB_HOME_DIR";
  /** A Docker volume holding an install, for {@link Tm1WebSource.InstallVolume}. */
  static readonly INSTALL_VOLUME = "TM1_WEB_INSTALL_VOLUME";
  /** A folder holding IBM's bundle, for {@link Tm1WebSource.Bundle}. */
  static readonly BUNDLE_DIR = "TM1_WEB_BUNDLE_DIR";
  /** The bundle's file name within {@link Tm1WebSource.BUNDLE_DIR}; the newest one if unset. */
  static readonly BUNDLE = "TM1_WEB_BUNDLE";
  /** A download link for IBM's bundle, for {@link Tm1WebSource.BundleUrl}. */
  static readonly BUNDLE_URL = "TM1_WEB_BUNDLE_URL";
  /** The SHA-256 to check the {@link Tm1WebSource.BUNDLE_URL} download against; optional. */
  static readonly BUNDLE_SHA256 = "TM1_WEB_BUNDLE_SHA256";

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
  static discover(lookup: Lookup = Settings.get): Tm1WebSource | undefined {
    const get = Settings.trimmed(lookup);
    const image = get(Tm1WebSource.PRIVATE_IMAGE);
    if (image !== undefined) return new Tm1WebSource.PrivateImage(image);
    const home = get(Tm1WebSource.HOME_DIR);
    if (home !== undefined) return new Tm1WebSource.InstallDir(home);
    const volume = get(Tm1WebSource.INSTALL_VOLUME);
    if (volume !== undefined) return new Tm1WebSource.InstallVolume(volume);
    const dir = get(Tm1WebSource.BUNDLE_DIR);
    if (dir !== undefined) return new Tm1WebSource.Bundle(dir, get(Tm1WebSource.BUNDLE));
    const url = get(Tm1WebSource.BUNDLE_URL);
    if (url !== undefined) return new Tm1WebSource.BundleUrl(url, get(Tm1WebSource.BUNDLE_SHA256));
    // The TM1 downloads folder, when it holds TM1 Web too. Skipped quietly when it doesn't
    const tm1Dir = get(Tm1Source.BUNDLE_DIR);
    if (
      tm1Dir !== undefined &&
      (get(Tm1WebSource.BUNDLE) !== undefined ||
        Settings.hasFile(tm1Dir, (n) => n.startsWith("tm1web-") && (n.endsWith("_bundle.tar.gz") || n.endsWith("-linuxi38664h.zip"))))
    ) {
      return new Tm1WebSource.Bundle(tm1Dir, get(Tm1WebSource.BUNDLE));
    }
    return undefined;
  }
}

export namespace Tm1WebSource {
  /** An image built on `tm1-web` with TM1 Web inside, from a **private** registry. It contains IBM
   * software, so it must never be pushed anywhere public. */
  export class PrivateImage extends Tm1WebSource {
    /** @param image the image's full name, including its registry and tag */
    constructor(readonly image: string) {
      super();
      Settings.requireText(image, "image");
    }

    describe(): string {
      return "private image " + this.image;
    }
  }

  /** An installed `tm1web` folder, the one containing `wlp/bin/server`. Mounted read-only. */
  export class InstallDir extends Tm1WebSource {
    /** The `tm1web` folder, absolute. */
    readonly dir: string;

    /** @throws IllegalArgumentException if it has no `wlp/bin/server` */
    constructor(dir: string) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isFile(join(this.dir, "wlp", "bin", "server"))) {
        throw new IllegalArgumentException(
          `Not a TM1 Web install: ${this.dir} has no wlp/bin/server. Point ${Tm1WebSource.HOME_DIR} at the tm1web folder of a Linux TM1 Web install.`,
        );
      }
    }

    describe(): string {
      return "install folder " + this.dir;
    }
  }

  /** A named Docker volume holding a TM1 Web install. If the volume is empty, the container fails
   * to start. */
  export class InstallVolume extends Tm1WebSource {
    constructor(readonly name: string) {
      super();
      Settings.requireText(name, "name");
    }

    describe(): string {
      return "install volume " + this.name;
    }
  }

  /** A folder holding IBM's `tm1web-*_bundle.tar.gz`, mounted read-only. The
   * image installs TM1 Web from it on first start into the shared cache volume, so later starts are
   * fast. */
  export class Bundle extends Tm1WebSource {
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
          `${Tm1WebSource.BUNDLE_DIR} is not a folder: ${this.dir}. Point it at the folder holding the bundle; name the file itself with ${Tm1WebSource.BUNDLE}.`,
        );
      }
      this.file = file === undefined || file === null || file.trim() === "" ? undefined : file;
      if (this.file !== undefined && !Settings.isFile(join(this.dir, this.file))) {
        throw new IllegalArgumentException(`${Tm1WebSource.BUNDLE} names ${this.file}, which isn't in ${this.dir}`);
      }
    }

    describe(): string {
      return "bundle " + (this.file !== undefined ? join(this.dir, this.file) : this.dir + " (newest)");
    }
  }

  /** A download link for IBM's bundle, such as a pre-signed S3, R2 or GCS URL. Links can carry
   * credentials in the query string (pre-signed links) or the user-info part
   * (`https://user:token@host/...`), so neither is ever logged. */
  export class BundleUrl extends Tm1WebSource {
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
