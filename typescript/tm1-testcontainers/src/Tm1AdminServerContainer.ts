import { GenericContainer } from "./GenericContainer.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";
import { Tm1Source } from "./Tm1Source.js";

const STARTUP_TIMEOUT = 5 * 60_000;

/** A TM1 Admin Server on its own, shared by several {@link Tm1ServerContainer}s, as the Java
 * `Tm1AdminServerContainer`: the `tm1-server` image run with `admin`, reached as `tm1-admin` on
 * its network. Servers register with it through {@link Tm1ServerContainer.withAdminServer}. */
export class Tm1AdminServerContainer extends GenericContainer {
  /** The Admin Server's REST port. */
  static readonly REST_PORT = 5895;

  /** Its name on the network, which the servers registering with it are given. */
  static readonly NETWORK_ALIAS = "tm1-admin";

  readonly #source: Tm1Source;

  /** An Admin Server from `source`, in the image TM1 runs in. */
  constructor(source: Tm1Source);
  /** An Admin Server from `source`, in `image`. */
  constructor(image: string, source: Tm1Source);
  constructor(imageOrSource: string | Tm1Source, maybeSource?: Tm1Source) {
    const source = typeof imageOrSource === "string" ? maybeSource : imageOrSource;
    if (!(source instanceof Tm1Source)) throw new TypeError("source must be a Tm1Source");
    super(typeof imageOrSource === "string" ? imageOrSource : Tm1ServerContainer.defaultImage(source));
    this.#source = source;
    this.withCommand("admin");
    this.withExposedPorts(Tm1AdminServerContainer.REST_PORT);
    this.withNetworkAliases(Tm1AdminServerContainer.NETWORK_ALIAS);
    // The image's healthcheck in admin mode: the server list answers without a login
    this.waitingForHttp("/api/v1/Servers", Tm1AdminServerContainer.REST_PORT, (s) => s === 200, STARTUP_TIMEOUT);
    Tm1ServerContainer.mountSource(
      source,
      (s, t, ro) => this.withBind(s, t, ro),
      (n, v) => this.withEnv(n, v),
    );
  }

  /** Where TM1 comes from. */
  getSource(): Tm1Source {
    return this.#source;
  }

  /** The Admin Server's REST root as seen from the host, `http://host:port/api/v1/`. */
  getRestUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(Tm1AdminServerContainer.REST_PORT) + "/api/v1/";
  }

  protected override async containerIsStarted(): Promise<void> {
    this.log(`TM1 Admin Server is up at ${this.getRestUrl()} (${this.#source.describe()})`);
  }
}
