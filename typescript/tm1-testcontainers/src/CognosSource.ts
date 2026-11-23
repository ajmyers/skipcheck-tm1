import { join } from "node:path";
import { inspect } from "node:util";
import { IllegalArgumentException, parseUrl, Settings, type Lookup } from "./settings.js";
import { Tm1Source } from "./Tm1Source.js";

/** Where a {@link CognosContainer} gets Cognos Analytics from, as the Java `CognosSource`: an
 * image with it built in ({@link CognosSource.PrivateImage}), a volume holding an install
 * ({@link CognosSource.InstallVolume}), a folder holding IBM's `casrv-*-linuxi38664h.zip`
 * ({@link CognosSource.Zip}), or a download link for it ({@link CognosSource.ZipUrl}).
 * {@link CognosSource.discover} tries them in that order, then `TM1_BUNDLE_DIR` when that folder
 * holds the zip. */
export abstract class CognosSource {
  /** An image with Cognos built in, for {@link CognosSource.PrivateImage}. */
  static readonly PRIVATE_IMAGE = "COGNOS_PRIVATE_IMAGE";
  /** A Docker volume holding an install, for {@link CognosSource.InstallVolume}. */
  static readonly INSTALL_VOLUME = "COGNOS_INSTALL_VOLUME";
  /** A folder holding IBM's zip, for {@link CognosSource.Zip}. */
  static readonly ZIP_DIR = "COGNOS_ZIP_DIR";
  /** The zip's file name within {@link CognosSource.ZIP_DIR}; the newest one if unset. */
  static readonly ZIP = "COGNOS_ZIP";
  /** A download link for IBM's zip, for {@link CognosSource.ZipUrl}. */
  static readonly ZIP_URL = "COGNOS_ZIP_URL";
  /** The SHA-256 to check the {@link CognosSource.ZIP_URL} download against; optional. */
  static readonly ZIP_SHA256 = "COGNOS_ZIP_SHA256";

  protected constructor() {}

  /** A short description for logs and messages. Never includes a URL's credentials or query. */
  abstract describe(): string;

  /** The description ({@link describe}), for logs. */
  toString(): string {
    return this.describe();
  }

  /** How Node prints it: the class and its description, never a URL's credentials or query. */
  [inspect.custom](): string {
    return this.constructor.name + "[" + this.describe() + "]";
  }

  /** Finds a source using `lookup` to read each setting (environment variables by default).
   * @returns the first source found, or `undefined` if none is configured
   * @throws IllegalArgumentException if a setting is present but invalid */
  static discover(lookup: Lookup = Settings.get): CognosSource | undefined {
    const get = Settings.trimmed(lookup);
    const image = get(CognosSource.PRIVATE_IMAGE);
    if (image !== undefined) return new CognosSource.PrivateImage(image);
    const volume = get(CognosSource.INSTALL_VOLUME);
    if (volume !== undefined) return new CognosSource.InstallVolume(volume);
    const dir = get(CognosSource.ZIP_DIR);
    if (dir !== undefined) return new CognosSource.Zip(dir, get(CognosSource.ZIP));
    const url = get(CognosSource.ZIP_URL);
    if (url !== undefined) return new CognosSource.ZipUrl(url, get(CognosSource.ZIP_SHA256));
    // The TM1 downloads folder, when it holds Cognos too. Skipped quietly when it doesn't: that
    // folder is TM1's setting, and is valid without Cognos in it.
    const tm1Dir = get(Tm1Source.BUNDLE_DIR);
    if (
      tm1Dir !== undefined &&
      (get(CognosSource.ZIP) !== undefined || Settings.hasFile(tm1Dir, (n) => n.startsWith("casrv-") && n.endsWith("-linuxi38664h.zip")))
    ) {
      return new CognosSource.Zip(tm1Dir, get(CognosSource.ZIP));
    }
    return undefined;
  }
}

/** The types nested in {@link CognosSource}, as in Java. */
export namespace CognosSource {
  /** An image built on `cognos-server` with Cognos inside, from a **private** registry. */
  export class PrivateImage extends CognosSource {
    constructor(readonly image: string) {
      super();
      Settings.requireText(image, "image");
    }

    describe(): string {
      return "private image " + this.image;
    }
  }

  /** A named Docker volume holding a Cognos Analytics install. */
  export class InstallVolume extends CognosSource {
    constructor(readonly name: string) {
      super();
      Settings.requireText(name, "name");
    }

    describe(): string {
      return "install volume " + this.name;
    }
  }

  /** A folder holding IBM's Cognos Analytics zip, mounted read-only; the image installs from it. */
  export class Zip extends CognosSource {
    /** The folder, absolute. */
    readonly dir: string;
    /** The zip's file name within {@link dir}, or `undefined` for the newest one. */
    readonly file: string | undefined;

    /** @throws IllegalArgumentException if the folder isn't there, or the file isn't in it */
    constructor(dir: string, file?: string | null) {
      super();
      this.dir = Settings.absolute(dir);
      if (!Settings.isDirectory(this.dir)) {
        throw new IllegalArgumentException(
          `${CognosSource.ZIP_DIR} is not a folder: ${this.dir}. Point it at the folder holding the zip; name the file itself with ${CognosSource.ZIP}.`,
        );
      }
      this.file = file === undefined || file === null || file.trim() === "" ? undefined : file;
      if (this.file !== undefined && !Settings.isFile(join(this.dir, this.file))) {
        throw new IllegalArgumentException(`${CognosSource.ZIP} names ${this.file}, which isn't in ${this.dir}`);
      }
    }

    describe(): string {
      return "zip " + (this.file !== undefined ? join(this.dir, this.file) : this.dir + " (newest)");
    }
  }

  /** A download link for IBM's Cognos Analytics zip; never logged with its credentials or query. */
  export class ZipUrl extends CognosSource {
    /** The download link. */
    readonly url: URL;
    /** The zip's expected SHA-256, if the download is to be checked. */
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
      return "zip URL " + Settings.safe(this.url);
    }
  }
}
