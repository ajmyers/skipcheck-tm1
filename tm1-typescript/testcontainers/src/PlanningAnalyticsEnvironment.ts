import { CognosContainer } from "./CognosContainer.js";
import { CognosSource } from "./CognosSource.js";
import type { Startable } from "./GenericContainer.js";
import { KeycloakContainer } from "./KeycloakContainer.js";
import { Messages } from "./messages.js";
import { Network } from "./Network.js";
import { PawContainer } from "./PawContainer.js";
import { PawSource } from "./PawSource.js";
import { IllegalArgumentException, IllegalStateException } from "./settings.js";
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
    this.#keycloak = parts.keycloak === undefined ? undefined : new KeycloakContainer().withNetwork(network);
    if (parts.cognos !== undefined) {
      const cognos = new CognosContainer(get(parts.cognosSource, () => CognosSource.discover(), Messages.COGNOS_NO_SOURCE)).withNetwork(network);
      if (this.#keycloak) cognos.withKeycloak(this.#keycloak);
      // First: the TM1 servers' CAM security is wired from the test users
      parts.cognos(cognos);
      this.#cognos = cognos;
    }
    const tm1Source = get(parts.tm1Source, () => Tm1Source.discover(), Messages.TM1_NO_SOURCE);
    this.#adminServer = parts.adminServer !== undefined || parts.tm1.size > 1 ? new Tm1AdminServerContainer(tm1Source).withNetwork(network) : undefined;
    const servers = new Map<string, Tm1ServerContainer>();
    for (const name of parts.tm1.keys()) {
      const server = new Tm1ServerContainer(tm1Source).withNetwork(network).withNetworkAliases(name).withEnv("TM1_SERVER_NAME", name);
      if (this.#adminServer) server.withAdminServer(this.#adminServer);
      if (this.#cognos) server.withCamSecurity(this.#cognos, parts.camNamespace, ...camAdmins(parts, this.#cognos));
      servers.set(name, server);
    }
    this.#tm1Servers = servers;
    const first = servers.values().next().value!;
    if (parts.tm1Web !== undefined) {
      const web = new Tm1WebContainer(get(parts.tm1WebSource, () => Tm1WebSource.discover(), Messages.TM1_WEB_NO_SOURCE)).withTm1Server(first);
      if (this.#adminServer) web.withAdminServer(this.#adminServer);
      // List every server: start them all before TM1 Web
      for (const server of servers.values()) web.dependsOn(server);
      this.#tm1Web = web;
    }
    if (parts.paw !== undefined) {
      const paw = new PawContainer(get(parts.pawSource, () => PawSource.discover(), Messages.PAW_NO_SOURCE)).withTm1Server(first);
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
  tm1: Map<string, Customizer<Tm1ServerContainer>>;
  adminServer?: Customizer<Tm1AdminServerContainer>;
  tm1Web?: Customizer<Tm1WebContainer>;
  paw?: Customizer<PawContainer>;
  cognos?: Customizer<CognosContainer>;
  keycloak?: Customizer<KeycloakContainer>;
  camNamespace: CognosContainer.Namespace;
  camAdmins: string[];
  tm1Source?: Tm1Source;
  tm1WebSource?: Tm1WebSource;
  pawSource?: PawSource;
  cognosSource?: CognosSource;
  network?: Network;
}

const SERVER_NAME = /^[A-Za-z0-9_-]+$/;
const none = (): void => {};

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

function get<T>(configured: T | undefined, discover: () => T | undefined, message: string): T {
  const found = configured ?? discover();
  if (found === undefined) throw new IllegalStateException(message);
  return found;
}

function apply<T>(customizer: Customizer<T> | undefined, part: T | undefined): void {
  if (customizer !== undefined && part !== undefined) customizer(part);
}

function required<T>(part: T | undefined, what: string, how: string): T {
  if (part === undefined) throw new IllegalStateException(`This environment has no ${what}; add it with ${how}`);
  return part;
}
