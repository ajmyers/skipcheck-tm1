// SPDX-License-Identifier: Apache-2.0

import { CognosSource } from "./CognosSource.js";
import { GenericContainer } from "./GenericContainer.js";
import { KeycloakContainer } from "./KeycloakContainer.js";
import { IllegalArgumentException, IllegalStateException } from "./settings.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";

const NAME = /^[A-Za-z0-9._-]+$/;
const INSTALL_PATH = "/opt/ibm/cognos/analytics";
const STARTUP_TIMEOUT = 15 * 60_000;

/** Cognos Analytics in Docker, for TM1 in CAM security, as the Java `CognosContainer`: the
 * `cognos-server` image (no IBM software in it) with Cognos from a {@link CognosSource}, an LDAP
 * namespace holding the test users added with {@link withUser}, reached as `cognos` on its
 * network. With {@link withKeycloak} it has an OIDC namespace too.
 *
 * ```ts
 * const cognos = new CognosContainer(CognosSource.discover()!).withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers");
 * await using tm1 = new Tm1ServerContainer(Tm1Source.discover()!).withCamSecurity(cognos, "admin");
 * await tm1.start(); // starts Cognos first
 * ```
 */
export class CognosContainer extends GenericContainer {
  /** The `cognos-server` image, without a tag. */
  static readonly DEFAULT_IMAGE = "ghcr.io/ajmyers/cognos-server";

  /** Overrides the `cognos-server` image (not used with {@link CognosSource.PrivateImage}). */
  static readonly SERVER_IMAGE = "COGNOS_SERVER_IMAGE";

  /** The dispatcher's port inside the container. */
  static readonly DISPATCHER_PORT = 9300;

  /** Its name on the network, and its host name, which Cognos's own URLs use. */
  static readonly NETWORK_ALIAS = "cognos";

  /** The ID of the LDAP namespace the test users are in. */
  static readonly NAMESPACE = "LDAP";

  readonly #source: CognosSource;
  readonly #users = new Map<string, CognosContainer.User>();
  #keycloak: KeycloakContainer | undefined;

  /** Cognos from `source`, in the default image (or `COGNOS_SERVER_IMAGE`). */
  constructor(source: CognosSource);
  /** Cognos from `source`, in `image`, which must be `cognos-server` or built on it. */
  constructor(image: string, source: CognosSource);
  constructor(imageOrSource: string | CognosSource, maybeSource?: CognosSource) {
    const source = typeof imageOrSource === "string" ? maybeSource : imageOrSource;
    if (!(source instanceof CognosSource)) throw new TypeError("source must be a CognosSource");
    super(
      typeof imageOrSource === "string"
        ? imageOrSource
        : source instanceof CognosSource.PrivateImage
          ? source.image
          : Tm1ServerContainer.imageFor(CognosContainer.SERVER_IMAGE, CognosContainer.DEFAULT_IMAGE),
    );
    this.#source = source;
    this.withExposedPorts(CognosContainer.DISPATCHER_PORT);
    this.withNetworkAliases(CognosContainer.NETWORK_ALIAS);
    this.withEnv("COGNOS_HOST", CognosContainer.NETWORK_ALIAS);
    this.withHostname(CognosContainer.NETWORK_ALIAS);
    // The image's healthcheck: 200 (anonymous session) or 441 (logon prompt, with users)
    this.waitingForHttp("/bi/v1/login", CognosContainer.DISPATCHER_PORT, (s) => s === 200 || s === 441, STARTUP_TIMEOUT);
    if (source instanceof CognosSource.InstallVolume) {
      this.withBind(source.name, INSTALL_PATH);
    } else if (source instanceof CognosSource.Zip) {
      this.withBind(source.dir, "/ibm", true);
      if (source.file !== undefined) this.withEnv("COGNOS_ZIP", source.file);
    } else if (source instanceof CognosSource.ZipUrl) {
      this.withEnv("COGNOS_ZIP_URL", source.text);
      if (source.sha256 !== undefined) this.withEnv("COGNOS_ZIP_SHA256", source.sha256);
    }
    // CognosSource.PrivateImage: Cognos is already in the image
  }

  /** Adds a test user to the LDAP namespace, in `groups`.
   * @throws IllegalArgumentException for a name or password the image can't take apart, or a
   * user added twice */
  withUser(name: string, password: string, ...groups: string[]): this {
    requireName(name, "user name");
    if (typeof password !== "string") throw new TypeError("password must be a string");
    if (password === "" || password.includes(":") || /\s/.test(password)) {
      throw new IllegalArgumentException("The password for " + name + " must be non-empty, without ':' or spaces");
    }
    for (const group of groups) requireName(group, "group name");
    if (this.#users.has(name)) throw new IllegalArgumentException("User " + name + " was already added");
    this.#users.set(name, new CognosContainer.User(name, password, groups));
    return this;
  }

  /** Adds the OIDC namespace, backed by `keycloak`, on a network the two share. Starting Cognos
   * starts Keycloak first. */
  withKeycloak(keycloak: KeycloakContainer): this {
    if (!(keycloak instanceof KeycloakContainer)) throw new TypeError("keycloak must be a KeycloakContainer");
    this.#keycloak = keycloak;
    Tm1ServerContainer.shareNetwork(this, keycloak);
    this.dependsOn(keycloak);
    return this.withEnv("COGNOS_KEYCLOAK_URL", keycloak.getNetworkUrl());
  }

  /** The Keycloak behind the OIDC namespace; `undefined` without one. */
  getKeycloak(): KeycloakContainer | undefined {
    return this.#keycloak;
  }

  /** The test users, in the order added. */
  getUsers(): CognosContainer.User[] {
    return [...this.#users.values()];
  }

  /** A test user.
   * @throws IllegalArgumentException if there's no such user */
  getUser(name: string): CognosContainer.User {
    const user = this.#users.get(name);
    if (!user) throw new IllegalArgumentException("No test user " + name + "; add it with withUser");
    return user;
  }

  /** The test users' groups, in the order first named. */
  getGroups(): ReadonlySet<string> {
    return new Set(this.getUsers().flatMap((u) => u.groups));
  }

  /** Where Cognos comes from. */
  getSource(): CognosSource {
    return this.#source;
  }

  /** Cognos's web address on the Docker host, `http://host:port/bi/`. */
  getUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(CognosContainer.DISPATCHER_PORT) + "/bi/";
  }

  /** The dispatcher's address on the network, as TM1 reaches it. */
  getNetworkUrl(): string {
    return "http://" + CognosContainer.NETWORK_ALIAS + ":" + CognosContainer.DISPATCHER_PORT;
  }

  /** The `Authorization` header that logs `user` (a test user) on to TM1 through `namespace`
   * (LDAP by default).
   * @throws IllegalArgumentException if there's no such user */
  camAuthorization(user: string, namespace: CognosContainer.Namespace = CognosContainer.Namespace.LDAP): string {
    return CognosContainer.camAuthorization(user, this.getUser(user).password, namespace.id());
  }

  /** `CAMNamespace` with `user:password:namespace`, as TM1 takes it. */
  static camAuthorization(user: string, password: string, namespace: string): string {
    return "CAMNamespace " + Buffer.from(user + ":" + password + ":" + namespace, "utf8").toString("base64");
  }

  /** A CAM passport for an OIDC ID token (from {@link KeycloakContainer.idToken}), as a browser
   * gets one, for TM1's `CAMPassport` logon.
   * @throws IllegalStateException if Cognos refused the token */
  async passport(idToken: string): Promise<string> {
    const body =
      '{"parameters":[{"name":"CAMNamespace","value":"' + CognosContainer.Namespace.OIDC.id() + '"},{"name":"CAMOIDCIdToken","value":"' + idToken + '"}]}';
    const response = await fetch("http://" + this.getHost() + ":" + this.getMappedPort(CognosContainer.DISPATCHER_PORT) + "/api/v1/session", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body,
      signal: AbortSignal.timeout(60_000),
    });
    const text = await response.text();
    const passport = response.headers
      .getSetCookie()
      .map((c) => c.split(";", 1)[0]!.trim())
      .find((c) => c.startsWith("cam_passport="));
    if (passport === undefined) throw new IllegalStateException(`Cognos refused the ID token (HTTP ${response.status}): ${text}`);
    return passport.slice("cam_passport=".length);
  }

  /** The users as the image takes them: `name:password[:group,group]`, space-separated.
   * @internal */
  usersSpec(): string {
    return this.getUsers()
      .map((u) => u.name + ":" + u.password + (u.groups.length === 0 ? "" : ":" + u.groups.join(",")))
      .join(" ");
  }

  /** @internal */
  override configure(): void {
    if (this.#users.size > 0) this.withEnv("COGNOS_USERS", this.usersSpec());
  }

  protected override async containerIsStarted(): Promise<void> {
    this.log(`Cognos is up at ${this.getUrl()} (${this.#source.describe()}), with ${this.#users.size} test user(s) in namespace ${CognosContainer.NAMESPACE}`);
  }
}

/** The types nested in {@link CognosContainer}, as in Java. */
export namespace CognosContainer {
  /** A Cognos namespace TM1 can log users on through, as the Java enum. */
  export class Namespace {
    /** The image's LDAP namespace, holding the test users. */
    static readonly LDAP = new Namespace("LDAP", "LDAP", "ldap");

    /** The OIDC namespace, backed by Keycloak ({@link CognosContainer.withKeycloak}). */
    static readonly OIDC = new Namespace("OIDC", "OIDC", "oidc");

    readonly #id: string;
    readonly #type: string;

    private constructor(
      /** The constant's name, as Java's `name()`. */
      readonly name: string,
      id: string,
      type: string,
    ) {
      this.#id = id;
      this.#type = type;
    }

    /** Every namespace, as Java's `values()`. */
    static values(): Namespace[] {
      return [Namespace.LDAP, Namespace.OIDC];
    }

    /** The namespace's ID, as TM1's `CAMNamespace` logon takes it. */
    id(): string {
      return this.#id;
    }

    /** The namespace's type, as the image takes it (package-private in Java).
     * @internal */
    type(): string {
      return this.#type;
    }

    toString(): string {
      return this.name;
    }
  }

  /** A test user in the LDAP namespace. */
  export class User {
    /** A test user: the name they log on with, their password, and the Cognos groups they're in
     * (copied). */
    readonly groups: readonly string[];

    constructor(
      readonly name: string,
      readonly password: string,
      groups: readonly string[],
    ) {
      this.groups = Object.freeze([...groups]);
    }
  }
}

function requireName(name: string | undefined | null, what: string): void {
  if (typeof name !== "string" || !NAME.test(name)) {
    throw new IllegalArgumentException(`Bad ${what} '${String(name)}': use letters, digits, '.', '_' and '-'`);
  }
}
