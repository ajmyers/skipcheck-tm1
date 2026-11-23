// SPDX-License-Identifier: Apache-2.0

import { join } from "node:path";
import { CognosContainer } from "./CognosContainer.js";
import { CognosSource } from "./CognosSource.js";
import type { Startable } from "./GenericContainer.js";
import { KeycloakContainer } from "./KeycloakContainer.js";
import { Messages } from "./messages.js";
import { readPaEnvironment, type FileSources } from "./PaEnvironmentFile.js";
import { Network } from "./Network.js";
import { PawContainer } from "./PawContainer.js";
import { PawSource } from "./PawSource.js";
import { IllegalArgumentException, IllegalStateException, Settings } from "./settings.js";
import { Tm1AdminServerContainer } from "./Tm1AdminServerContainer.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";
import { Tm1Source } from "./Tm1Source.js";
import { Tm1WebContainer } from "./Tm1WebContainer.js";
import { Tm1WebSource } from "./Tm1WebSource.js";

type Customizer<T> = (part: T) => unknown;

/** A whole Planning Analytics setup on one Docker network, as the Java
 * `PlanningAnalyticsEnvironment`: any mix of TM1 servers (by name), a shared Admin Server, TM1
 * Web, PAW, Cognos and Keycloak, wired together, every part optional but one TM1 server.
 *
 * ```ts
 * await using env = PlanningAnalyticsEnvironment.builder()
 *   .tm1("planning", (tm1) => tm1.withSeed(Tm1Seed.SDATA))
 *   .tm1("reporting")
 *   .tm1Web()
 *   .cognos((cognos) => cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
 *   .keycloak()                                          // adds Cognos's OIDC namespace
 *   .camSecurity(CognosContainer.Namespace.OIDC, "admin") // default: LDAP, the first test user as admin
 *   .build();
 * await env.start();
 * env.tm1("planning").getRestUrl();
 * ```
 *
 * - Every builder method takes a customizer that receives the ordinary container, run after the
 *   environment's wiring, so it can change it. Cognos's runs first, because the TM1 servers are
 *   wired from its test users.
 * - Servers are named (`TM1_SERVER_NAME`) and reachable by name on the network. With more than
 *   one, or with `adminServer()`, they share a {@link Tm1AdminServerContainer}, and TM1 Web and
 *   PAW list them all.
 * - With `cognos(...)`, every server runs in CAM security. PAW with CAM security isn't supported
 *   yet.
 * - Sources are discovered from the environment, as for each container, unless set with
 *   `tm1Source(...)` and so on. {@link start} starts parts in dependency order, in parallel where
 *   it can; {@link stop} stops everything and removes the network. */
export class PlanningAnalyticsEnvironment implements Startable {
  readonly #network: Network;
  readonly #ownsNetwork: boolean;
  readonly #tm1Servers: ReadonlyMap<string, Tm1ServerContainer>;
  readonly #adminServer: Tm1AdminServerContainer | undefined;
  readonly #tm1Web: Tm1WebContainer | undefined;
  readonly #paw: PawContainer | undefined;
  readonly #cognos: CognosContainer | undefined;
  readonly #keycloak: KeycloakContainer | undefined;

  /** @internal */
  constructor(parts: BuilderParts, network: Network) {
    this.#network = network;
    this.#ownsNetwork = parts.network === undefined;
    this.#keycloak = parts.keycloak === undefined ? undefined : new KeycloakContainer(parts.keycloakImage).withNetwork(network);
    if (parts.cognos !== undefined) {
      const source = get(
        parts,
        parts.cognosSource,
        (ibm) => new CognosSource.Zip(ibm),
        () => CognosSource.discover(),
        (image) => new CognosSource.PrivateImage(image),
        parts.cognosImage,
        Messages.COGNOS_NO_SOURCE,
      );
      const image = runs(parts.cognosImage, source instanceof CognosSource.PrivateImage);
      const cognos = (image !== undefined ? new CognosContainer(image, source) : new CognosContainer(source)).withNetwork(network);
      if (this.#keycloak) cognos.withKeycloak(this.#keycloak);
      // First: the TM1 servers' CAM security is wired from the test users
      parts.cognos(cognos);
      this.#cognos = cognos;
    }
    const tm1Source = get(
      parts,
      parts.tm1Source,
      (ibm) => new Tm1Source.Bundle(ibm),
      () => Tm1Source.discover(),
      (image) => new Tm1Source.PrivateImage(image),
      parts.tm1Image,
      Messages.TM1_NO_SOURCE,
    );
    const tm1Image = runs(parts.tm1Image, tm1Source instanceof Tm1Source.PrivateImage);
    this.#adminServer =
      parts.adminServer !== undefined || parts.tm1.size > 1
        ? (tm1Image !== undefined ? new Tm1AdminServerContainer(tm1Image, tm1Source) : new Tm1AdminServerContainer(tm1Source)).withNetwork(network)
        : undefined;
    const servers = new Map<string, Tm1ServerContainer>();
    for (const name of parts.tm1.keys()) {
      const server = (tm1Image !== undefined ? new Tm1ServerContainer(tm1Image, tm1Source) : new Tm1ServerContainer(tm1Source))
        .withNetwork(network)
        .withNetworkAliases(name)
        .withEnv("TM1_SERVER_NAME", name);
      if (this.#adminServer) server.withAdminServer(this.#adminServer);
      if (this.#cognos) server.withCamSecurity(this.#cognos, parts.camNamespace, ...camAdmins(parts, this.#cognos));
      servers.set(name, server);
    }
    this.#tm1Servers = servers;
    const first = servers.values().next().value!;
    if (parts.tm1Web !== undefined) {
      const source = get(
        parts,
        parts.tm1WebSource,
        (ibm) => new Tm1WebSource.Bundle(ibm),
        () => Tm1WebSource.discover(),
        (image) => new Tm1WebSource.PrivateImage(image),
        parts.tm1WebImage,
        Messages.TM1_WEB_NO_SOURCE,
      );
      const image = runs(parts.tm1WebImage, source instanceof Tm1WebSource.PrivateImage);
      const web = (image !== undefined ? new Tm1WebContainer(image, source) : new Tm1WebContainer(source)).withTm1Server(first);
      if (this.#adminServer) web.withAdminServer(this.#adminServer);
      // List every server: start them all before TM1 Web
      for (const server of servers.values()) web.dependsOn(server);
      this.#tm1Web = web;
    }
    if (parts.paw !== undefined) {
      const paw = new PawContainer(
        get(
          parts,
          parts.pawSource,
          (ibm) => pawFromIbm(ibm, parts.pawZip),
          () => PawSource.discover(),
          undefined,
          undefined,
          Messages.PAW_NO_SOURCE,
        ),
      ).withTm1Server(first);
      if (this.#adminServer) paw.withAdminServer(this.#adminServer);
      this.#paw = paw;
    }
    // The callers' own settings last, so they can change anything set above
    apply(parts.adminServer, this.#adminServer);
    for (const [name, customizer] of parts.tm1) customizer(servers.get(name)!);
    apply(parts.tm1Web, this.#tm1Web);
    apply(parts.paw, this.#paw);
    apply(parts.keycloak, this.#keycloak);
  }

  /** A builder for an environment. */
  static builder(): PlanningAnalyticsEnvironment.Builder {
    return new PlanningAnalyticsEnvironment.Builder();
  }

  /** Starts building the environment a `pa-environment.yaml` describes, the file `pa-setup` reads
   * (`docker/pa-setup`), so tests run what developers run. The file is checked as `pa-setup`
   * checks it, with its defaults. The builder comes back configured; change it further, or call
   * {@link PlanningAnalyticsEnvironment.Builder.build}:
   *
   * ```ts
   * await using env = PlanningAnalyticsEnvironment.fromConfig("pa-environment.yaml").build();
   * await env.start();
   * env.tm1("planning").getRestUrl();
   * ```
   *
   * How each setting maps:
   * - `tm1.servers.<name>`: {@link PlanningAnalyticsEnvironment.Builder.tm1}, with `seed` as
   *   {@link Tm1ServerContainer.withSeed} and each `cfg` parameter as `TM1_CFG_<name>`, written as
   *   `pa-setup` writes it (`true` is `True`);
   * - `tm1.adminServer`: {@link PlanningAnalyticsEnvironment.Builder.adminServer}; with more than
   *   one server there's one anyway, and `false` then fails, as in `pa-setup`;
   * - `tm1Web`: {@link PlanningAnalyticsEnvironment.Builder.tm1Web}, with `heap` as `TM1_WEB_HEAP`
   *   (`1g`);
   * - `cognos`: {@link PlanningAnalyticsEnvironment.Builder.cognos}, with `users` as
   *   {@link CognosContainer.withUser} and `memory` as `COGNOS_MAX_MEMORY` (`6144`); `oidc` adds
   *   {@link PlanningAnalyticsEnvironment.Builder.keycloak}; `cam` is
   *   {@link PlanningAnalyticsEnvironment.Builder.camSecurity}, by default LDAP with the first user
   *   as administrator;
   * - `paw`: {@link PlanningAnalyticsEnvironment.Builder.paw} with {@link PawContainer.acceptLicense},
   *   which the file must say (`acceptLicense: true`); the zip is `zip` in `ibm`, or the newest
   *   there;
   * - `ibm`: each part's source, the folder as {@link Tm1Source.Bundle}, {@link Tm1WebSource.Bundle}
   *   and {@link CognosSource.Zip}. A relative path is from the file's folder; `~` is the user's
   *   home;
   * - `images`: the image each part runs, as a container's `image` argument. A part with no other
   *   source takes its image as the private image with IBM's software built in
   *   ({@link Tm1Source.PrivateImage} and the like).
   *
   * A part's source is the one set on the builder, else `ibm`, else what `discover()` finds (the
   * variables pa-setup's `.env` sets, such as `TM1_BUNDLE_URL`), else its image as a private
   * image.
   *
   * Ignored, because they place things on the host that Testcontainers manages itself: `name` (the
   * compose project; the environment has its own network), every `port`, `adminPort` and
   * `keycloakPort` (Testcontainers maps random host ports), and `files` (the host folder pa-setup
   * keeps databases in; each test environment starts from a new database, or one copied in with
   * {@link Tm1ServerContainer.withData}). They're still checked as `pa-setup` checks them.
   *
   * Refused, because the environment can't run them yet: `cognos` without `tm1.servers`, `paw`
   * with `cognos`, and `seed: 24retail`.
   * @param path the file, usually `pa-environment.yaml`
   * @returns a builder configured from the file
   * @throws IllegalArgumentException if the file can't be read, isn't valid, or asks for what the
   * environment can't run; the message names the setting */
  static fromConfig(path: string): PlanningAnalyticsEnvironment.Builder {
    if (typeof path !== "string") throw new TypeError("path must be a string");
    return readPaEnvironment(path, new PlanningAnalyticsEnvironment.Builder());
  }

  /** Starts everything, what's depended on first and in parallel where it can, then PAW. */
  async start(): Promise<void> {
    await Promise.all(
      this.containers()
        .filter((c) => c !== this.#paw)
        .map((c) => c.start()),
    );
    if (this.#paw) await this.#paw.start();
  }

  /** Stops everything, last started first, and removes the network it made. */
  async stop(): Promise<void> {
    for (const part of this.containers().reverse()) await part.stop();
    if (this.#ownsNetwork) await this.#network.close();
  }

  /** Stops it, for `await using`: the same as {@link stop}. */
  async [Symbol.asyncDispose](): Promise<void> {
    await this.stop();
  }

  /** Whether every part is running. */
  isRunning(): boolean {
    return this.containers().every((c) => c.isRunning());
  }

  /** The TM1 server named `name`.
   * @throws IllegalArgumentException if there's none */
  tm1(name: string): Tm1ServerContainer {
    const server = this.#tm1Servers.get(name);
    if (!server) throw new IllegalArgumentException(`No TM1 server ${name}; there's [${[...this.#tm1Servers.keys()].join(", ")}]`);
    return server;
  }

  /** The TM1 servers by name, in the order added. */
  tm1Servers(): ReadonlyMap<string, Tm1ServerContainer> {
    return this.#tm1Servers;
  }

  /** The shared Admin Server, or `undefined` when the one TM1 server runs its own. */
  adminServer(): Tm1AdminServerContainer | undefined {
    return this.#adminServer;
  }

  /** TM1 Web.
   * @throws IllegalStateException if it wasn't added */
  tm1Web(): Tm1WebContainer {
    return required(this.#tm1Web, "TM1 Web", "tm1Web()");
  }

  /** PAW.
   * @throws IllegalStateException if it wasn't added */
  paw(): PawContainer {
    return required(this.#paw, "PAW", "paw(...)");
  }

  /** Cognos.
   * @throws IllegalStateException if it wasn't added */
  cognos(): CognosContainer {
    return required(this.#cognos, "Cognos", "cognos(...)");
  }

  /** Keycloak.
   * @throws IllegalStateException if it wasn't added */
  keycloak(): KeycloakContainer {
    return required(this.#keycloak, "Keycloak", "keycloak()");
  }

  /** The network everything is on. */
  network(): Network {
    return this.#network;
  }

  /** Every part, in start order: Keycloak, Cognos, the Admin Server, the TM1 servers, TM1 Web, PAW.
   * @internal */
  containers(): Startable[] {
    const all: Startable[] = [];
    if (this.#keycloak) all.push(this.#keycloak);
    if (this.#cognos) all.push(this.#cognos);
    if (this.#adminServer) all.push(this.#adminServer);
    all.push(...this.#tm1Servers.values());
    if (this.#tm1Web) all.push(this.#tm1Web);
    if (this.#paw) all.push(this.#paw);
    return all;
  }
}

/** What a builder collects.
 * @internal */
export interface BuilderParts {
  /** Each TM1 server's settings, by server name. */
  tm1: Map<string, Customizer<Tm1ServerContainer>>;
  /** The separate Admin Server's settings, if there is one. */
  adminServer?: Customizer<Tm1AdminServerContainer>;
  /** TM1 Web's settings, if it's in the environment. */
  tm1Web?: Customizer<Tm1WebContainer>;
  /** PAW's settings, if it's in the environment. */
  paw?: Customizer<PawContainer>;
  /** Cognos's settings, if it's in the environment. */
  cognos?: Customizer<CognosContainer>;
  /** Keycloak's settings, if it's in the environment. */
  keycloak?: Customizer<KeycloakContainer>;
  /** The Cognos namespace TM1's users log on through. */
  camNamespace: CognosContainer.Namespace;
  /** The Cognos test users who are TM1 administrators. */
  camAdmins: string[];
  /** Where TM1 comes from, if given rather than discovered. */
  tm1Source?: Tm1Source;
  /** Where TM1 Web comes from, if given rather than discovered. */
  tm1WebSource?: Tm1WebSource;
  /** Where PAW comes from, if given rather than discovered. */
  pawSource?: PawSource;
  /** Where Cognos comes from, if given rather than discovered. */
  cognosSource?: CognosSource;
  /** The network the containers share, if given rather than made. */
  network?: Network;
  /** From pa-environment.yaml ({@link PlanningAnalyticsEnvironment.fromConfig}): the IBM downloads
   * folder, absolute. */
  ibm?: string;
  /** PAW's zip in {@link ibm}, or `undefined` for the newest there. */
  pawZip?: string;
  /** The TM1 servers' and Admin Server's image, from pa-environment.yaml. */
  tm1Image?: string;
  /** TM1 Web's image, from pa-environment.yaml. */
  tm1WebImage?: string;
  /** Cognos's image, from pa-environment.yaml. */
  cognosImage?: string;
  /** Keycloak's image, from pa-environment.yaml. */
  keycloakImage?: string;
}

const SERVER_NAME = /^[A-Za-z0-9_-]+$/;
const none = (): void => {};

/** The types nested in {@link PlanningAnalyticsEnvironment}, as in Java. */
export namespace PlanningAnalyticsEnvironment {
  /** Builds a {@link PlanningAnalyticsEnvironment}, as Java's `PlanningAnalyticsEnvironment.Builder`. */
  export class Builder {
    readonly #parts: BuilderParts = { tm1: new Map(), camNamespace: CognosContainer.Namespace.LDAP, camAdmins: [] };

    /** Adds a TM1 server named `name` (its server name and network alias), changed with
     * `customizer` once wired.
     * @throws IllegalArgumentException for a bad name, or one added twice */
    tm1(name: string, customizer: Customizer<Tm1ServerContainer> = none): this {
      if (typeof name !== "string" || !SERVER_NAME.test(name))
        throw new IllegalArgumentException(`Bad TM1 server name '${String(name)}': use letters, digits, _ and -`);
      if (this.#parts.tm1.has(name)) throw new IllegalArgumentException(`TM1 server ${name} was already added`);
      this.#parts.tm1.set(name, customizer);
      return this;
    }

    /** Adds a shared Admin Server even for one TM1 server, changed with `customizer`. */
    adminServer(customizer: Customizer<Tm1AdminServerContainer> = none): this {
      this.#parts.adminServer = customizer;
      return this;
    }

    /** Adds TM1 Web, listing every server, changed with `customizer`. */
    tm1Web(customizer: Customizer<Tm1WebContainer> = none): this {
      this.#parts.tm1Web = customizer;
      return this;
    }

    /** Adds PAW, changed with `customizer` (to accept the license, say). */
    paw(customizer: Customizer<PawContainer>): this {
      this.#parts.paw = customizer;
      return this;
    }

    /** Adds Cognos, putting every TM1 server in CAM security; add its test users with
     * `customizer`. */
    cognos(customizer: Customizer<CognosContainer>): this {
      this.#parts.cognos = customizer;
      return this;
    }

    /** Adds Keycloak, giving Cognos an OIDC namespace, changed with `customizer`. */
    keycloak(customizer: Customizer<KeycloakContainer> = none): this {
      this.#parts.keycloak = customizer;
      return this;
    }

    /** The CAM namespace TM1's users log on through, and the TM1 administrators (by default, the
     * first test user). */
    camSecurity(namespace: CognosContainer.Namespace, ...admins: string[]): this {
      this.#parts.camNamespace = namespace;
      this.#parts.camAdmins = [...admins];
      return this;
    }

    /** Where TM1 comes from, rather than {@link Tm1Source.discover}. */
    tm1Source(source: Tm1Source): this {
      this.#parts.tm1Source = source;
      return this;
    }

    /** Where TM1 Web comes from, rather than {@link Tm1WebSource.discover}. */
    tm1WebSource(source: Tm1WebSource): this {
      this.#parts.tm1WebSource = source;
      return this;
    }

    /** Where PAW comes from, rather than {@link PawSource.discover}. */
    pawSource(source: PawSource): this {
      this.#parts.pawSource = source;
      return this;
    }

    /** Where Cognos comes from, rather than {@link CognosSource.discover}. */
    cognosSource(source: CognosSource): this {
      this.#parts.cognosSource = source;
      return this;
    }

    /** The network to use, rather than a new one (which the environment removes when it stops). */
    network(network: Network): this {
      this.#parts.network = network;
      return this;
    }

    /** The sources and images read from pa-environment.yaml.
     * @internal */
    withFileSources(sources: FileSources): this {
      Object.assign(this.#parts, sources);
      return this;
    }

    /** The environment, wired.
     * @throws IllegalStateException if parts are missing, or don't go together */
    build(): PlanningAnalyticsEnvironment {
      const p = this.#parts;
      if (p.tm1.size === 0) throw new IllegalStateException("Add at least one TM1 server with tm1(name)");
      if (p.keycloak !== undefined && p.cognos === undefined) throw new IllegalStateException("Keycloak gives Cognos its OIDC namespace: add cognos(...) too");
      if (p.camNamespace === CognosContainer.Namespace.OIDC && p.keycloak === undefined)
        throw new IllegalStateException("CAM security through OIDC needs keycloak()");
      if (p.paw !== undefined && p.cognos !== undefined) {
        throw new IllegalStateException("PAW with Cognos (CAM) security isn't supported yet: PAW logs users on with TM1's own security");
      }
      return new PlanningAnalyticsEnvironment(p, p.network ?? Network.newNetwork());
    }
  }
}

function camAdmins(parts: BuilderParts, cognos: CognosContainer): string[] {
  if (parts.camAdmins.length > 0) return [...parts.camAdmins];
  const users = cognos.getUsers();
  if (users.length === 0) throw new IllegalStateException("Cognos needs test users for CAM security: add them with withUser");
  return [users[0]!.name];
}

/** A part's source: the one set on the builder, else the file's `ibm` folder, else what
 * `discover` finds, else the file's image as a private image. */
function get<T>(
  parts: BuilderParts,
  configured: T | undefined,
  fromIbm: (ibm: string) => T,
  discover: () => T | undefined,
  privateImage: ((image: string) => T) | undefined,
  image: string | undefined,
  message: string,
): T {
  if (configured !== undefined) return configured;
  if (parts.ibm !== undefined) {
    if (!Settings.isDirectory(parts.ibm)) throw new IllegalStateException("ibm in pa-environment.yaml isn't a folder: " + parts.ibm);
    return fromIbm(parts.ibm);
  }
  const found = discover();
  if (found !== undefined) return found;
  if (privateImage !== undefined && image !== undefined) return privateImage(image);
  throw new IllegalStateException(message);
}

/** The image a part runs from pa-setup's image setting: none when its source is a private image,
 * which is the image. */
function runs(image: string | undefined, privateImage: boolean): string | undefined {
  return privateImage ? undefined : image;
}

function pawFromIbm(ibm: string, pawZip: string | undefined): PawSource {
  if (pawZip !== undefined) return new PawSource(join(ibm, pawZip));
  const found = PawSource.discover((name) => (name === PawSource.BUNDLE_DIR ? ibm : undefined));
  if (found === undefined) throw new IllegalStateException("No PAW Local zip in " + ibm);
  return found;
}

function apply<T>(customizer: Customizer<T> | undefined, part: T | undefined): void {
  if (customizer !== undefined && part !== undefined) customizer(part);
}

function required<T>(part: T | undefined, what: string, how: string): T {
  if (part === undefined) throw new IllegalStateException(`This environment has no ${what}; add it with ${how}`);
  return part;
}
