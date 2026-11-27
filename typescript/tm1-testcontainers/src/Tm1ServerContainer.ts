// SPDX-License-Identifier: Apache-2.0

import { Tm1Version } from "@skipcheck/tm1-client";
import { CognosContainer } from "./CognosContainer.js";
import { GenericContainer } from "./GenericContainer.js";
import { Messages } from "./messages.js";
import { Network } from "./Network.js";
import { IllegalArgumentException, IllegalStateException, Settings } from "./settings.js";
import { Tm1AdminServerContainer } from "./Tm1AdminServerContainer.js";
import { Tm1Seed } from "./Tm1Seed.js";
import { Tm1Source } from "./Tm1Source.js";

const INSTALL_PATH = "/opt/ibm/cognos/tm1_64";
const STARTUP_TIMEOUT = 5 * 60_000;
const REQUEST_TIMEOUT = 30_000;

let shared: Promise<Tm1ServerContainer> | undefined;

/** A TM1 server in Docker, for tests, built on the `tm1-server` image, as the Java
 * `Tm1ServerContainer`.
 *
 * The image contains no IBM software, so every container needs a {@link Tm1Source}. Most tests
 * don't create containers themselves: they use `requiresTm1` (from
 * `@skipcheck/tm1-testcontainers/vitest`), which finds a source from the environment, starts one
 * {@link Tm1ServerContainer.shared} container, and skips the tests when there's no source or no
 * Docker.
 *
 * By default the server starts with an empty database and the login `admin` with no password.
 * Once started, the container reads the server's version ({@link getTm1Version}). If
 * {@link withExpectedVersion} was set and the version differs, it fails to start, before any test
 * runs against the wrong TM1.
 *
 * ```ts
 * await using tm1 = new Tm1ServerContainer(Tm1Source.discover()!).withSeed(Tm1Seed.SDATA);
 * await tm1.start();
 * const client = Tm1Client.builder().baseUrl(tm1.getRestUrl()).basicAuth(tm1.getUsername(), tm1.getPassword()).build();
 * ```
 */
export class Tm1ServerContainer extends GenericContainer {
  /** The `tm1-server` image, without a tag. */
  static readonly DEFAULT_IMAGE = "ghcr.io/ajmyers/tm1-server";

  /** Overrides the `tm1-server` image (not used with {@link Tm1Source.PrivateImage}), e.g.
   * `tm1-server:local` for a local build. */
  static readonly SERVER_IMAGE = "TM1_SERVER_IMAGE";

  /** The REST API's port inside the container. */
  static readonly REST_PORT = 8010;

  /** The volume bundle installs are cached in, shared by every container on this Docker host
   * (and with the Java, Python and .NET tests). Only the first container to see a bundle installs
   * it; the rest reuse it. Remove the volume to clear it. */
  static readonly CACHE_VOLUME = "tm1-testcontainers-cache";

  readonly #source: Tm1Source;
  #username = "admin";
  #password = "";
  #expectedVersion: Tm1Version | undefined;
  #version: Tm1Version | undefined;
  #cognos: CognosContainer | undefined;
  #camAdmins: string[] = [];
  #camNamespace: CognosContainer.Namespace = CognosContainer.Namespace.LDAP;
  #adminServer: Tm1AdminServerContainer | undefined;

  /** A container on the default `tm1-server` image (or `TM1_SERVER_IMAGE`), getting TM1 from
   * `source`. */
  constructor(source: Tm1Source);
  /** A container on `image`, which must be `tm1-server` or built on it. */
  constructor(image: string, source: Tm1Source);
  constructor(imageOrSource: string | Tm1Source, maybeSource?: Tm1Source) {
    const source = typeof imageOrSource === "string" ? maybeSource : imageOrSource;
    if (!(source instanceof Tm1Source)) throw new TypeError("source must be a Tm1Source");
    super(typeof imageOrSource === "string" ? imageOrSource : Tm1ServerContainer.defaultImage(source));
    this.#source = source;
    this.withExposedPorts(Tm1ServerContainer.REST_PORT);
    this.withEnv("TM1_SEED", Tm1Seed.EMPTY.value);
    // TM1 serves $metadata without a login, so a 200 means the REST API is really up. This is
    // also the image's healthcheck.
    this.waitingForHttp("/api/v1/$metadata", Tm1ServerContainer.REST_PORT, (s) => s === 200, STARTUP_TIMEOUT);
    Tm1ServerContainer.mountSource(
      source,
      (s, t, ro) => this.withBind(s, t, ro),
      (n, v) => this.withEnv(n, v),
    );
  }

  /** The container most tests share: one per test run (per vitest worker), started on first use
   * with the source {@link Tm1Source.discover} finds. Testcontainers removes it when the run ends.
   *
   * It's marked {@link withReuse reusable}. If you've turned on Testcontainers reuse
   * (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or
   * `TESTCONTAINERS_REUSE_ENABLE=true`), it keeps running between test runs, so tests start
   * instantly, and objects tests leave behind stay too.
   * @throws IllegalStateException if no source is configured */
  static shared(): Promise<Tm1ServerContainer> {
    shared ??= (async () => {
      const source = Tm1Source.discover();
      if (!source) throw new IllegalStateException(Messages.TM1_NO_SOURCE);
      const container = new Tm1ServerContainer(source).withReuse(true);
      await container.start();
      return container;
    })().catch((e: unknown) => {
      shared = undefined;
      throw e;
    });
    return shared;
  }

  /** Seeds an empty database with objects. The default is {@link Tm1Seed.EMPTY}, which seeds
   * nothing. Also sets {@link withCredentials credentials} to the seed's login. */
  withSeed(seed: Tm1Seed): this {
    this.withEnv("TM1_SEED", seed.value);
    return this.withCredentials(seed.username, seed.password);
  }

  /** Copies a TM1 database (a folder of `.cub`, `.dim`, `.pro` and so on) into the container
   * before it starts. Any seed is ignored, since the database isn't empty. Set
   * {@link withCredentials} to a login the database has.
   * @throws IllegalArgumentException if `dataDir` isn't a folder */
  withData(dataDir: string): this {
    if (!Settings.isDirectory(dataDir)) throw new IllegalArgumentException("Not a folder: " + dataDir);
    return this.withCopyFileToContainer(Settings.absolute(dataDir), "/tm1/data/");
  }

  /** The login tests should use. The default is `admin` with no password (`""`). */
  withCredentials(username: string, password: string): this {
    if (typeof username !== "string" || typeof password !== "string") throw new TypeError("username and password must be strings");
    this.#username = username;
    this.#password = password;
    return this;
  }

  /** Runs TM1 in Cognos (CAM) security against `cognos`: TM1 takes its users from Cognos's test
   * users, who log on with {@link CognosContainer.camAuthorization}.
   *
   * - `admins` become TM1 administrators, and {@link getUsername} is the first of them, so the
   *   container's own checks log on as that user;
   * - every group of the test users is added to TM1 (named by its CAMID), so users get their
   *   groups at logon.
   *
   * The two containers share a Docker network: `cognos`'s, or a new one if it has none. Starting
   * TM1 starts Cognos first. Call this before either is started. With a `namespace`
   * ({@link CognosContainer.Namespace.OIDC} needs {@link CognosContainer.withKeycloak}), TM1's
   * administrators, groups and this container's own login go through it; LDAP otherwise.
   * @throws IllegalArgumentException if no admin is given */
  withCamSecurity(cognos: CognosContainer, ...admins: string[]): this;
  /** Runs TM1 in Cognos (CAM) security through `namespace`: TM1's administrators, groups and this
   * container's own login go through it. {@link CognosContainer.Namespace.OIDC} needs {@link
   * CognosContainer.withKeycloak}. @throws IllegalArgumentException if no admin is given */
  withCamSecurity(cognos: CognosContainer, namespace: CognosContainer.Namespace, ...admins: string[]): this;
  withCamSecurity(cognos: CognosContainer, ...rest: (string | CognosContainer.Namespace)[]): this {
    if (!(cognos instanceof CognosContainer)) throw new TypeError("cognos must be a CognosContainer");
    const first = rest[0];
    const namespace = first instanceof CognosContainer.Namespace ? first : CognosContainer.Namespace.LDAP;
    const admins = (first instanceof CognosContainer.Namespace ? rest.slice(1) : rest) as string[];
    this.#camNamespace = namespace;
    this.#cognos = cognos;
    if (admins.length === 0) throw new IllegalArgumentException("Name at least one of the Cognos test users as a TM1 administrator");
    this.#camAdmins = [...admins];
    Tm1ServerContainer.shareNetwork(this, cognos);
    this.dependsOn(cognos);
    return this;
  }

  /** Registers this server with a separate Admin Server, as in a deployment with several TM1
   * servers, instead of running one inside this container (`TM1_ADMIN_HOST`). TM1 Web and PAW
   * pointed at that Admin Server list every server registered with it. The two share a Docker
   * network, and starting this server starts the Admin Server first. */
  withAdminServer(admin: Tm1AdminServerContainer): this {
    if (!(admin instanceof Tm1AdminServerContainer)) throw new TypeError("admin must be a Tm1AdminServerContainer");
    this.#adminServer = admin;
    Tm1ServerContainer.shareNetwork(this, admin);
    this.dependsOn(admin);
    return this.withEnv("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS);
  }

  /** The Admin Server set with {@link withAdminServer}; `undefined` if this container runs its own. */
  getAdminServer(): Tm1AdminServerContainer | undefined {
    return this.#adminServer;
  }

  /** The Cognos set with {@link withCamSecurity}; `undefined` if TM1 uses its own (native) security. */
  getCognos(): CognosContainer | undefined {
    return this.#cognos;
  }

  /** The Cognos namespace TM1's users log on through; `undefined` in native security. */
  getCamNamespace(): CognosContainer.Namespace | undefined {
    return this.#cognos === undefined ? undefined : this.#camNamespace;
  }

  /** Whether the server ends up in CAM security: set with {@link withCamSecurity}, unless a
   * `tm1s.cfg` parameter given as `TM1_CFG_IntegratedSecurityMode` says another mode than 4 or 5.
   * The image applies those last, and takes a parameter's name in any case.
   * @internal */
  inCamSecurity(): boolean {
    if (this.#cognos === undefined) return false;
    const mode = this.#cfg("IntegratedSecurityMode");
    return mode === undefined || mode.trim() === "4" || mode.trim() === "5";
  }

  /** Whether a `tm1s.cfg` parameter given as `TM1_CFG_UseSSL` turns TLS on.
   * @internal */
  usesSsl(): boolean {
    const ssl = this.#cfg("UseSSL");
    return ssl !== undefined && ssl.trim().toLowerCase() !== "f" && ssl.trim().toLowerCase() !== "false";
  }

  /** A tm1s.cfg parameter given in the environment, whatever the case of its name. */
  #cfg(parameter: string): string | undefined {
    for (const [name, value] of this.getEnvMap()) {
      if (name.toLowerCase() === ("TM1_CFG_" + parameter).toLowerCase()) return value;
    }
    return undefined;
  }

  /** The Cognos test users made TM1 administrators by {@link withCamSecurity}.
   * @internal */
  getCamAdmins(): readonly string[] {
    return this.#camAdmins;
  }

  /** The `Authorization` header for the container's login: `Basic` with {@link getUsername} and
   * {@link getPassword}, or, in CAM security, `CAMNamespace` for that Cognos user. */
  getAuthorization(): string {
    if (this.#cognos) return CognosContainer.camAuthorization(this.#username, this.#password, this.#camNamespace.id());
    return "Basic " + Buffer.from(this.#username + ":" + this.#password, "utf8").toString("base64");
  }

  /** Fails startup unless the server reports exactly this version. */
  withExpectedVersion(version: Tm1Version | string): this {
    this.#expectedVersion = typeof version === "string" ? Tm1Version.parse(version) : version;
    return this;
  }

  /** Where TM1 comes from. */
  getSource(): Tm1Source {
    return this.#source;
  }

  /** The REST API's service root as seen from the host, e.g. `http://localhost:54321/api/v1/`. */
  getRestUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(Tm1ServerContainer.REST_PORT) + "/api/v1/";
  }

  /** The TM1 user name tests should log in as; `admin` unless set. */
  getUsername(): string {
    return this.#username;
  }

  /** The password for {@link getUsername}; empty unless set. */
  getPassword(): string {
    return this.#password;
  }

  /** The version the running server reports.
   * @throws IllegalStateException if the container isn't started yet */
  getTm1Version(): Tm1Version {
    if (!this.#version) throw new IllegalStateException("The container isn't started yet");
    return this.#version;
  }

  /** @internal */
  override configure(): void {
    const cognos = this.#cognos;
    if (!cognos) return;
    // Here rather than in withCamSecurity, so users added to Cognos later still count
    const admin = cognos.getUser(this.#camAdmins[0]!);
    for (const name of this.#camAdmins) cognos.getUser(name);
    this.withCredentials(admin.name, admin.password);
    this.withEnv("TM1_CAM_URI", cognos.getNetworkUrl());
    if (this.#camNamespace === CognosContainer.Namespace.OIDC && cognos.getKeycloak() === undefined) {
      throw new IllegalStateException("CAM security through OIDC needs the Cognos to have withKeycloak");
    }
    this.withEnv("TM1_CAM_NAMESPACE", this.#camNamespace.id());
    this.withEnv("TM1_CAM_NAMESPACE_TYPE", this.#camNamespace.type());
    this.withEnv("TM1_CAM_ADMINS", this.#camAdmins.join(" "));
    if (cognos.getGroups().size > 0) this.withEnv("TM1_CAM_GROUPS", [...cognos.getGroups()].join(" "));
  }

  protected override async containerIsStarted(): Promise<void> {
    const version = await this.#readVersion();
    this.#version = version;
    if (this.#expectedVersion && !this.#expectedVersion.equals(version)) {
      throw new IllegalStateException(
        `Expected TM1 ${this.#expectedVersion.toString()} but the server from ${this.#source.describe()} reports ${version.toString()}`,
      );
    }
    this.log(`TM1 ${version.toString()} is up at ${this.getRestUrl()} (${this.#source.describe()})`);
  }

  /** Stops and removes the container, and forgets the version it read. */
  override async stop(): Promise<void> {
    this.#version = undefined;
    await super.stop();
  }

  /** Puts two containers that talk to each other on one Docker network: the one either already
   * has, or a new one. Fails if they're on different networks, since neither could then reach the
   * other by name.
   * @internal */
  static shareNetwork(a: GenericContainer, b: GenericContainer): void {
    const network = a.getNetwork() ?? b.getNetwork() ?? Network.newNetwork();
    for (const container of [a, b]) {
      const current = container.getNetwork();
      if (current === undefined) container.withNetwork(network);
      else if (current !== network) {
        throw new IllegalStateException(
          `Containers that work together must share one Network, but ${container.constructor.name} is on another. Give them the same Network.`,
        );
      }
    }
  }

  /** Mounts a TM1 source into a `tm1-server` container: the install, the bundle folder and
   * cache, or the download settings. Shared with {@link Tm1AdminServerContainer}, which runs the
   * same image.
   * @internal */
  static mountSource(
    source: Tm1Source,
    bind: (source: string, target: string, readOnly: boolean) => void,
    withEnv: (name: string, value: string) => void,
  ): void {
    if (source instanceof Tm1Source.InstallDir) {
      bind(source.dir, INSTALL_PATH, true);
    } else if (source instanceof Tm1Source.InstallVolume) {
      bind(source.name, INSTALL_PATH, false);
    } else if (source instanceof Tm1Source.Bundle) {
      bind(source.dir, "/ibm", true);
      if (source.file !== undefined) withEnv("TM1_BUNDLE", source.file);
      bind(Tm1ServerContainer.CACHE_VOLUME, "/tm1/cache", false);
    } else if (source instanceof Tm1Source.BundleUrl) {
      withEnv("TM1_BUNDLE_URL", source.text);
      if (source.sha256 !== undefined) withEnv("TM1_BUNDLE_SHA256", source.sha256);
      bind(Tm1ServerContainer.CACHE_VOLUME, "/tm1/cache", false);
    }
    // Tm1Source.PrivateImage: TM1 is already in the image
  }

  /** Reads the version with a throwaway login, then closes the session it opened, so the
   * container doesn't start with a session held open. Plain `fetch` rather than tm1-client, so a
   * client bug can't break the harness that tests the client. */
  async #readVersion(): Promise<Tm1Version> {
    const url = this.getRestUrl();
    let response: Response;
    let body: string;
    try {
      response = await fetch(url + "Configuration/ProductVersion/$value", {
        headers: { Authorization: this.getAuthorization() },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT),
      });
      body = await response.text();
    } catch (e) {
      throw new Error("Couldn't read the TM1 version from " + url, { cause: e });
    }
    if (response.status !== 200) {
      throw new IllegalStateException(
        `TM1 refused the login '${this.#username}' (HTTP ${response.status}): ${body}. Set the database's login with withCredentials.`,
      );
    }
    const cookie = response.headers
      .getSetCookie()
      .map((c) => c.split(";", 1)[0]!.trim())
      .find((c) => c.startsWith("TM1SessionId="));
    if (cookie) {
      try {
        // TM1 rejects actions without a JSON content type, even with no parameters
        const closed = await fetch(url + "ActiveSession/tm1.Close", {
          method: "POST",
          headers: { Cookie: cookie, "Content-Type": "application/json" },
          body: "{}",
          signal: AbortSignal.timeout(REQUEST_TIMEOUT),
        });
        await closed.arrayBuffer();
      } catch {
        // Couldn't close the version-check session; TM1 will time it out
      }
    }
    return Tm1Version.parse(body);
  }

  /** The image for `source`: a private image as it is; otherwise `TM1_SERVER_IMAGE` if set;
   * otherwise {@link DEFAULT_IMAGE} tagged with this library's version (`edge` for a development
   * build).
   * @internal */
  static defaultImage(source: Tm1Source): string {
    return source instanceof Tm1Source.PrivateImage
      ? source.image
      : Tm1ServerContainer.imageFor(Tm1ServerContainer.SERVER_IMAGE, Tm1ServerContainer.DEFAULT_IMAGE);
  }

  /** @internal */
  static imageFor(setting: string, image: string): string {
    return Settings.text(Settings.get(setting)) ?? image + ":" + Tm1ServerContainer.imageTag(Settings.libraryVersion());
  }

  /** Released libraries use the image of the same version; development builds (Java's
   * `-SNAPSHOT`, npm's `-dev` and a published `-dev.1`) use `edge`, built from master: a
   * development pre-release is published by hand, without the images a tagged release gets.
   * @internal */
  static imageTag(libraryVersion: string): string {
    return libraryVersion.endsWith("-SNAPSHOT") || libraryVersion.endsWith("-dev") || libraryVersion.includes("-dev.") ? "edge" : libraryVersion;
  }
}
