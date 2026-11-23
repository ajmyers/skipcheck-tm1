import { GenericContainer } from "./GenericContainer.js";
import { Messages } from "./messages.js";
import { Network } from "./Network.js";
import { IllegalStateException } from "./settings.js";
import { Tm1AdminServerContainer } from "./Tm1AdminServerContainer.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";
import { Tm1WebSource } from "./Tm1WebSource.js";

const INSTALL_PATH = "/opt/ibm/cognos/tm1web";
const STARTUP_TIMEOUT = 5 * 60_000;

let shared: Promise<Tm1WebContainer> | undefined;

/** TM1 Web in Docker, as the Java `Tm1WebContainer`: the `tm1-web` image (no IBM software in it)
 * with TM1 Web from a {@link Tm1WebSource}, at `/tm1web/` on port 9510, listing the servers of a
 * TM1 server's own Admin Server ({@link withTm1Server}) or of a shared one
 * ({@link withAdminServer}). {@link Tm1WebContainer.shared} gives the one TM1 Web the tests of a
 * run share, wired to {@link Tm1ServerContainer.shared}. */
export class Tm1WebContainer extends GenericContainer {
  /** The `tm1-web` image, without a tag. */
  static readonly DEFAULT_IMAGE = "ghcr.io/ajmyers/tm1-web";

  /** Overrides the `tm1-web` image (not used with {@link Tm1WebSource.PrivateImage}). */
  static readonly WEB_IMAGE = "TM1_WEB_IMAGE";

  /** TM1 Web's HTTP port inside the container. */
  static readonly HTTP_PORT = 9510;

  readonly #source: Tm1WebSource;
  #tm1: Tm1ServerContainer | undefined;
  #adminServer: Tm1AdminServerContainer | undefined;

  /** TM1 Web from `source`, in the default image (or `TM1_WEB_IMAGE`). */
  constructor(source: Tm1WebSource);
  /** TM1 Web from `source`, in `image`, which must be `tm1-web` or built on it. */
  constructor(image: string, source: Tm1WebSource);
  constructor(imageOrSource: string | Tm1WebSource, maybeSource?: Tm1WebSource) {
    const source = typeof imageOrSource === "string" ? maybeSource : imageOrSource;
    if (!(source instanceof Tm1WebSource)) throw new TypeError("source must be a Tm1WebSource");
    super(
      typeof imageOrSource === "string"
        ? imageOrSource
        : source instanceof Tm1WebSource.PrivateImage
          ? source.image
          : Tm1ServerContainer.imageFor(Tm1WebContainer.WEB_IMAGE, Tm1WebContainer.DEFAULT_IMAGE),
    );
    this.#source = source;
    this.withExposedPorts(Tm1WebContainer.HTTP_PORT);
    // The login page, as the image's healthcheck uses: 200 once both apps are up
    this.waitingForHttp("/tm1web/", Tm1WebContainer.HTTP_PORT, (s) => s === 200, STARTUP_TIMEOUT);
    if (source instanceof Tm1WebSource.InstallDir) {
      this.withBind(source.dir, INSTALL_PATH, true);
    } else if (source instanceof Tm1WebSource.InstallVolume) {
      this.withBind(source.name, INSTALL_PATH);
    } else if (source instanceof Tm1WebSource.Bundle) {
      this.withBind(source.dir, "/ibm", true);
      if (source.file !== undefined) this.withEnv("TM1_WEB_BUNDLE", source.file);
      this.withBind(Tm1ServerContainer.CACHE_VOLUME, "/tm1-web/cache");
    } else if (source instanceof Tm1WebSource.BundleUrl) {
      this.withEnv("TM1_WEB_BUNDLE_URL", source.text);
      if (source.sha256 !== undefined) this.withEnv("TM1_WEB_BUNDLE_SHA256", source.sha256);
      this.withBind(Tm1ServerContainer.CACHE_VOLUME, "/tm1-web/cache");
    }
    // Tm1WebSource.PrivateImage: TM1 Web is already in the image
  }

  /** The TM1 Web most tests share: one per test run, started on first use with the source
   * {@link Tm1WebSource.discover} finds, listing {@link Tm1ServerContainer.shared}'s server.
   * Marked reusable, as the shared TM1 server.
   * @throws IllegalStateException if no TM1 Web or TM1 source is configured */
  static shared(): Promise<Tm1WebContainer> {
    shared ??= (async () => {
      const source = Tm1WebSource.discover();
      if (!source) throw new IllegalStateException(Messages.TM1_WEB_NO_SOURCE);
      const container = new Tm1WebContainer(source).withTm1Server(await Tm1ServerContainer.shared()).withReuse(true);
      await container.start();
      return container;
    })().catch((e: unknown) => {
      shared = undefined;
      throw e;
    });
    return shared;
  }

  /** Lists the servers of `tm1`'s own Admin Server. Starting TM1 Web starts `tm1` first, and
   * gives it a network if it has none and isn't running. */
  withTm1Server(tm1: Tm1ServerContainer): this {
    if (!(tm1 instanceof Tm1ServerContainer)) throw new TypeError("tm1 must be a Tm1ServerContainer");
    this.#tm1 = tm1;
    if (!tm1.isRunning() && tm1.getNetwork() === undefined) tm1.withNetwork(Network.newNetwork());
    return this.dependsOn(tm1);
  }

  /** Lists the servers of a shared Admin Server ({@link Tm1ServerContainer.withAdminServer}),
   * on a network the two share. */
  withAdminServer(admin: Tm1AdminServerContainer): this {
    if (!(admin instanceof Tm1AdminServerContainer)) throw new TypeError("admin must be a Tm1AdminServerContainer");
    this.#adminServer = admin;
    Tm1ServerContainer.shareNetwork(this, admin);
    return this.dependsOn(admin);
  }

  /** The Admin Server set with {@link withAdminServer}, or `undefined`. */
  getAdminServer(): Tm1AdminServerContainer | undefined {
    return this.#adminServer;
  }

  /** Where TM1 Web comes from. */
  getSource(): Tm1WebSource {
    return this.#source;
  }

  /** The TM1 server set with {@link withTm1Server}, or `undefined`. */
  getTm1Server(): Tm1ServerContainer | undefined {
    return this.#tm1;
  }

  /** TM1 Web's address as seen from the host, e.g. `http://localhost:54321/tm1web/`. */
  getUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(Tm1WebContainer.HTTP_PORT) + "/tm1web/";
  }

  /** The Admin Server host TM1 Web lists servers from, as a TM1 Web login names it.
   * @throws IllegalStateException if there's none yet */
  getAdminHost(): string {
    const host = this.getEnvMap().get("TM1_ADMIN_HOST");
    if (host === undefined) throw new IllegalStateException("No Admin Server yet: set a TM1 server with withTm1Server and start");
    return host;
  }

  /** @internal */
  override configure(): void {
    if (this.#adminServer) {
      this.withEnv("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS);
      return;
    }
    const tm1 = this.#tm1;
    if (!tm1) return;
    if (!tm1.isRunning()) throw new IllegalStateException("The TM1 server isn't running; it should have started first");
    const network = tm1.getNetwork();
    const address = Tm1WebContainer.addressOn(tm1, "TM1 Web");
    if (network) {
      this.withNetwork(network);
    } else {
      // TM1 registers with the Admin Server as http://<its hostname>:8010, and TM1 Web's
      // evalservice (universal reports) calls that URL. Docker's DNS resolves hostnames on
      // networks it creates, but not on the default bridge, so name it here.
      this.withExtraHost(tm1.getContainer().getHostname(), address);
    }
    this.withEnv("TM1_ADMIN_HOST", address);
  }

  protected override async containerIsStarted(): Promise<void> {
    this.log(
      `TM1 Web is up at ${this.getUrl()} (${this.#source.describe()}), listing servers from the Admin Server at ${this.getEnvMap().get("TM1_ADMIN_HOST") ?? "(none)"}`,
    );
  }

  /** A started container's address on its {@link Network}, or on Docker's default bridge.
   * @internal */
  static addressOn(container: GenericContainer, who: string): string {
    const started = container.getContainer();
    const name = container.getNetwork()?.getName() ?? "bridge";
    const names = started.getNetworkNames();
    const address = names.includes(name) ? started.getIpAddress(name) : "";
    if (!address || address.trim() === "") {
      throw new IllegalStateException(
        `${who} can't reach the TM1 server: it's on [${names.join(", ")}]. Give the TM1 server a Network (withNetwork), or leave it on Docker's default bridge network.`,
      );
    }
    return address;
  }
}
