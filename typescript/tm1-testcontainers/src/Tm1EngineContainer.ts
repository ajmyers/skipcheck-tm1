// SPDX-License-Identifier: Apache-2.0

import { setTimeout as sleep } from "node:timers/promises";
import { Tm1Version } from "@skipcheck/tm1-client";
import { GenericContainer } from "./GenericContainer.js";
import { Messages } from "./messages.js";
import { Network } from "./Network.js";
import { IllegalArgumentException, IllegalStateException } from "./settings.js";
import { Tm1Database } from "./Tm1Database.js";
import { Tm1EngineSource } from "./Tm1EngineSource.js";
import { Tm1Login } from "./Tm1Login.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";

const INSTALL_PATH = "/opt/ibm/tm1-engine";
const STARTUP_TIMEOUT = 5 * 60_000;
/** The image's commands are given this long, in seconds, by `timeout` in the container. */
const COMMAND_TIMEOUT = 180;
/** Creating a database is given longer: at least the image's own wait for the database to be ready. */
const CREATE_TIMEOUT = 330;
const REQUEST_TIMEOUT = 30_000;

// The image's own rule: at least three characters, and nothing the commands or a URL would need
// quoting for
const NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{2,}$/;
// An instance's name has no least length: only a database's does
const INSTANCE = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;
const USER = /^[A-Za-z0-9._-]+$/;

// The datastore is this engine's alone and goes with it: nothing else is given the password
const DATASTORE_USER = "root";
const DATASTORE_PASSWORD = "tm1datastore";

let shared: Promise<Tm1EngineContainer> | undefined;

/** What `tm1-engine credentials --json` prints: the engine's credentials, and what it holds. */
interface Credentials {
  root?: { clientId?: string; clientSecret?: string };
  instances?: {
    name?: string;
    applications?: { name?: string; clientId?: string; clientSecret?: string | null }[];
    databases?: { name?: string }[];
  }[];
}

/** How {@link Tm1EngineContainer.awaitSetup} asks about the container.
 * @internal */
export interface SetupProbe {
  /** Why the container is no longer running, or `undefined` while it runs. */
  exited(): Promise<string | undefined>;
  /** The exit code of the image's `tm1-engine health`. */
  health(): Promise<number>;
  /** The container's log. */
  logs(): Promise<string>;
}

/** The MongoDB an engine keeps its metadata in. */
class Datastore extends GenericContainer {
  constructor() {
    super(Tm1EngineContainer.DATASTORE_IMAGE);
    this.withNetworkAliases(Tm1EngineContainer.DATASTORE_ALIAS);
    this.withEnv("MONGO_INITDB_ROOT_USERNAME", DATASTORE_USER);
    this.withEnv("MONGO_INITDB_ROOT_PASSWORD", DATASTORE_PASSWORD);
    // Enough for TM1's metadata; MongoDB would otherwise take half the machine's memory
    this.withCommand("--wiredTigerCacheSizeGB", "0.5");
  }

  /** MongoDB's image is built for every platform, so it runs as the machine's own. */
  protected override platform(): string | undefined {
    return undefined;
  }
}

/** A TM1 v12 engine (Planning Analytics 3.x) in Docker, for tests, as the Java
 * `Tm1EngineContainer`: the `tm1-engine` image with TM1 from a {@link Tm1EngineSource}, and the
 * MongoDB it keeps its metadata in, on a network of their own. For TM1 11, use
 * {@link Tm1ServerContainer}.
 *
 * One engine holds **instances**, each holding **databases**. Most tests use the one instance,
 * `tm1`, and a database of their own: `requiresTm1Engine` (from
 * `@skipcheck/tm1-testcontainers/vitest`) gives each test file one, in {@link shared}'s engine. A container you make yourself
 * has the databases you name with {@link withDatabase}, or one called `tm1`:
 *
 * ```ts
 * await using engine = new Tm1EngineContainer(source).withDatabase("planning").withUser("analyst", "Analysts");
 * await engine.start();
 * const planning = engine.database("planning");
 * const url = planning.getRestUrl(); // http://localhost:54321/tm1/api/v1/Databases('planning')/
 * const analyst = planning.login("analyst");
 * ```
 *
 * TM1 v12 has no passwords of its own. A test logs in through the instance's application
 * ({@link Tm1Login}), which this container creates and keeps the credentials of.
 *
 * Starting takes about 40 seconds, and a database about 10 more to create
 * ({@link createDatabase}). The image exists for x86-64 only, as TM1 does; on Apple Silicon it
 * runs under Rosetta. */
export class Tm1EngineContainer extends GenericContainer {
  /** The `tm1-engine` image, without a tag. */
  static readonly DEFAULT_IMAGE = "ghcr.io/ajmyers/tm1-engine";

  /** Overrides the `tm1-engine` image (not used with {@link Tm1EngineSource.PrivateImage}), e.g.
   * `tm1-engine:local` for a local build. */
  static readonly ENGINE_IMAGE = "TM1_ENGINE_IMAGE";

  /** The MongoDB image the engine's datastore runs. */
  static readonly DATASTORE_IMAGE = "mongo:8.0";

  /** The port of TM1's gateway inside the container: the REST API, logins and management. */
  static readonly PORT = 4444;

  /** The instance databases are in unless one is named. */
  static readonly DEFAULT_INSTANCE = "tm1";

  /** The database a container has when none is named. */
  static readonly DEFAULT_DATABASE = "tm1";

  /** The datastore's name on the engine's network. */
  static readonly DATASTORE_ALIAS = "tm1-datastore";

  /** The volume package installs are cached in, shared by every engine container on this Docker
   * host (and with the Java, Python and .NET tests). Only the first container to see a package
   * installs it. Remove the volume to clear it. */
  static readonly CACHE_VOLUME = "tm1-engine-testcontainers-cache";

  readonly #source: Tm1EngineSource;
  readonly #datastore: GenericContainer;
  // instance/database, in the order they were added
  readonly #databases = new Set<string>();
  readonly #users = new Map<string, string[]>();
  #credentials: Credentials | undefined;
  #version: Tm1Version | undefined;

  /** A container on the default `tm1-engine` image (or `TM1_ENGINE_IMAGE`), getting TM1 from
   * `source`. */
  constructor(source: Tm1EngineSource);
  /** A container from an image of your own, such as a locally built one. */
  constructor(image: string, source: Tm1EngineSource);
  constructor(imageOrSource: string | Tm1EngineSource, maybeSource?: Tm1EngineSource) {
    const source = typeof imageOrSource === "string" ? maybeSource : imageOrSource;
    if (!(source instanceof Tm1EngineSource)) throw new TypeError("source must be a Tm1EngineSource");
    super(typeof imageOrSource === "string" ? imageOrSource : Tm1EngineContainer.defaultImage(source));
    this.#source = source;
    this.withExposedPorts(Tm1EngineContainer.PORT);
    // The gateway refuses a request without a login (401) once it's up. That isn't ready yet:
    // containerIsStarted then waits for the image to finish setting TM1 up. The wait stops if the
    // container exits, as it does when TM1 can't start.
    this.waitingForHttp("/", Tm1EngineContainer.PORT, (s) => s === 401 || s === 200, STARTUP_TIMEOUT);
    this.#datastore = new Datastore();
    this.withEnv("TM1_DATASTORE_URL", `mongodb://${DATASTORE_USER}:${DATASTORE_PASSWORD}@${Tm1EngineContainer.DATASTORE_ALIAS}:27017`);
    this.dependsOn(this.#datastore);
    // A network of their own unless one is given. It's made in Docker only when used.
    this.withNetwork(Network.newNetwork());
    this.#mountSource();
  }

  /** The engine tests share, started on first use with the source
   * {@link Tm1EngineSource.discover} finds; Testcontainers removes it when the run ends. It has one
   * database, `tm1`; `requiresTm1Engine` creates one more for each test file.
   *
   * It's one per module registry, where Java's is one per JVM. vitest isolates test files by
   * default, each with its own modules, so that is **one engine per test file**, started (about 40
   * seconds) by the first test of the file that needs it. It's one for the whole run only when the
   * files share their modules: `isolate: false`, or a single worker without isolation.
   *
   * It isn't marked reusable: an engine and its datastore belong together, and a kept engine would
   * keep every database tests left in it.
   * @throws IllegalStateException if no source is configured */
  static shared(): Promise<Tm1EngineContainer> {
    shared ??= (async () => {
      const source = Tm1EngineSource.discover();
      if (!source) throw new IllegalStateException(Messages.TM1_ENGINE_NO_SOURCE);
      const container = new Tm1EngineContainer(source);
      await container.start();
      return container;
    })().catch((e: unknown) => {
      shared = undefined;
      throw e;
    });
    return shared;
  }

  /** Puts the engine, and its datastore with it, on a network. They have one of their own unless
   * this is called. */
  override withNetwork(network: Network | undefined): this {
    super.withNetwork(network);
    // At once, not when the engine starts: whatever starts the engine may start what it depends
    // on first, and the datastore must be here by then
    this.#datastore.withNetwork(network);
    return this;
  }

  /** Adds a database, in the instance `tm1`. Without any, the container has one called `tm1`.
   * @param name the database's name: at least three letters, digits, `_` or `-`. `Sales` and
   * `sales` are two databases */
  withDatabase(name: string): this;
  /** Adds a database in a named instance, which is created with an application of its own. An
   * instance's users, sessions and applications are separate from another's. */
  withDatabase(instance: string, name: string): this;
  withDatabase(instance: string, name?: string): this {
    this.#databases.add(name === undefined ? qualified(Tm1EngineContainer.DEFAULT_INSTANCE, instance) : qualified(instance, name));
    return this;
  }

  /** Adds a user to every database, in exactly these groups (created if missing). A new database
   * has one user already: `admin`, in the `ADMIN` group.
   * @throws IllegalArgumentException if a name isn't one the image takes, or the user is there */
  withUser(name: string, ...groups: string[]): this {
    if (typeof name !== "string" || !USER.test(name)) throw new IllegalArgumentException("A user's name is letters, digits, '.', '_' and '-': " + name);
    for (const group of groups) {
      if (typeof group !== "string" || !USER.test(group)) throw new IllegalArgumentException("A group's name is letters, digits, '.', '_' and '-': " + group);
    }
    if (this.#users.has(name)) throw new IllegalArgumentException("The user " + name + " is already added");
    this.#users.set(name, [...groups]);
    return this;
  }

  /** Where TM1 comes from. */
  getSource(): Tm1EngineSource {
    return this.#source;
  }

  /** The MongoDB the engine keeps its metadata in, started and stopped with it. */
  getDatastore(): GenericContainer {
    return this.#datastore;
  }

  /** The gateway as seen from the host, ending in `/`, e.g. `http://localhost:54321/`. */
  getUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(Tm1EngineContainer.PORT) + "/";
  }

  /** The management API, which lists and creates instances, applications and databases, e.g.
   * `http://localhost:54321/manage/v1/`. It takes the root credentials as Basic authentication
   * ({@link getRootClientId}, {@link getRootClientSecret}). */
  getManagementUrl(): string {
    return this.getUrl() + "manage/v1/";
  }

  /** The client ID of the engine's root credentials, for the management API.
   * @throws IllegalStateException if the container isn't started yet */
  getRootClientId(): string {
    return this.#read().root?.clientId ?? "";
  }

  /** The client secret of the engine's root credentials.
   * @throws IllegalStateException if the container isn't started yet */
  getRootClientSecret(): string {
    return this.#read().root?.clientSecret ?? "";
  }

  /** The version of TM1 the engine runs. Only available once started.
   * @throws IllegalStateException if the container isn't started yet */
  getTm1Version(): Tm1Version {
    if (!this.#version) throw new IllegalStateException("The container isn't started yet");
    return this.#version;
  }

  /** The databases the engine has now, in every instance: asked of the engine each time. */
  async databases(): Promise<Tm1Database[]> {
    // Read again: something other than createDatabase and dropDatabase may have changed them
    await this.#readCredentials();
    return this.#known();
  }

  /** A database of the instance `tm1`.
   *
   * Of a running engine, it's one of those the engine had when it started, or when
   * {@link databases}, {@link createDatabase} or {@link dropDatabase} last ran (Java asks the engine
   * each time, which here would need a `Promise`); before the start, one of those added.
   * @throws IllegalArgumentException if there's no such database */
  database(name: string): Tm1Database;
  /** A database of a named instance.
   * @throws IllegalArgumentException if there's no such database */
  database(instance: string, name: string): Tm1Database;
  database(instance: string, name?: string): Tm1Database {
    if (name === undefined) return this.database(Tm1EngineContainer.DEFAULT_INSTANCE, instance);
    const wanted = new Tm1Database(this, instance, name);
    if (this.isRunning()) {
      const has = this.#known();
      if (!has.some((d) => d.equals(wanted))) {
        throw new IllegalArgumentException(`No database ${instance}/${name} in this engine. It has [${has.join(", ")}]`);
      }
    } else if (!this.configured().includes(instance + "/" + name)) {
      throw new IllegalArgumentException(`No database ${instance}/${name} was added (withDatabase). Added: [${this.configured().join(", ")}]`);
    }
    return wanted;
  }

  /** Creates a database in the instance `tm1` of the running engine, with the users of
   * {@link withUser}. Takes about 10 seconds. */
  createDatabase(name: string): Promise<Tm1Database>;
  /** Creates a database in a named instance of the running engine, which must exist. */
  createDatabase(instance: string, name: string): Promise<Tm1Database>;
  async createDatabase(instance: string, name?: string): Promise<Tm1Database> {
    if (name === undefined) return this.createDatabase(Tm1EngineContainer.DEFAULT_INSTANCE, instance);
    const database = qualified(instance, name);
    try {
      await this.#command(CREATE_TIMEOUT, "database", "create", database);
    } catch (e) {
      // TM1 may have made it before the command gave up: don't leave half a database. If it wasn't
      // made, or can't be dropped, the first failure is still the one to report
      await this.#command(COMMAND_TIMEOUT, "database", "drop", database).catch(() => undefined);
      await this.#readCredentials().catch(() => undefined);
      throw e;
    }
    await this.#readCredentials();
    return new Tm1Database(this, instance, name);
  }

  /** Deletes a database of the running engine, with everything in it. Takes about 7 seconds. */
  async dropDatabase(database: Tm1Database): Promise<void> {
    await this.#command(COMMAND_TIMEOUT, "database", "drop", database.getInstance() + "/" + database.getName());
    await this.#readCredentials();
  }

  /** A login to an instance, through its first application the image holds the secret of: TM1
   * shows a secret once, so an application made elsewhere is listed without.
   * @internal */
  login(instance: string, user: string): Tm1Login {
    for (const found of this.#read().instances ?? []) {
      if (found.name !== instance) continue;
      for (const application of found.applications ?? []) {
        if (typeof application.clientSecret === "string") {
          return new Tm1Login(this.getUrl() + instance + "/auth/v1/", instance, application.clientId ?? "", application.clientSecret, user);
        }
      }
    }
    throw new IllegalStateException("The instance " + instance + " has no application to log in through");
  }

  /** What's passed to the image as TM1_DATABASES: what was added, or the default.
   * @internal */
  configured(): string[] {
    return this.#databases.size === 0 ? [Tm1EngineContainer.DEFAULT_INSTANCE + "/" + Tm1EngineContainer.DEFAULT_DATABASE] : [...this.#databases];
  }

  /** Starts the datastore, then the engine, on one network, and waits until TM1 is set up. */
  override start(): Promise<void> {
    // Before anything starts: what this depends on, the datastore, is started ahead of configure()
    this.#joinNetwork();
    return super.start().catch(async (e: unknown) => {
      // The engine that couldn't start is stopped for us, not what it depends on
      await this.#datastore.stop().catch(() => undefined);
      throw e;
    });
  }

  /** @internal */
  override configure(): void {
    this.#joinNetwork();
    this.withEnv("TM1_DATABASES", this.configured().join(","));
    if (this.#users.size > 0) {
      this.withEnv("TM1_USERS", [...this.#users].map(([name, groups]) => [name, ...groups].join(":")).join(","));
    }
  }

  protected override async containerIsStarted(): Promise<void> {
    await Tm1EngineContainer.awaitSetup(
      {
        exited: () => this.exitedWhileStarting(),
        health: async () => (await this.getContainer().exec(["tm1-engine", "health"])).exitCode,
        logs: () => this.getLogs(),
      },
      STARTUP_TIMEOUT,
    );
    const databases = await this.databases();
    const version = await this.#readVersion(databases[0]);
    this.#version = version;
    this.log(`TM1 ${version.toString()} is up at ${this.getUrl()} (${this.#source.describe()}), with [${databases.join(", ")}]`);
  }

  /** Stops and removes the engine and its datastore, and forgets what it read from them. */
  override async stop(): Promise<void> {
    try {
      await super.stop();
    } finally {
      // Not a dependent: what this depends on is started for it, and stopping is left to us. Also
      // when the engine itself never came up
      this.#credentials = undefined;
      this.#version = undefined;
      await this.#datastore.stop();
    }
  }

  /** Waits until the image has created every instance, application, database and user, and every
   * database is ready: what its health check reports. Fails at once if the container has exited.
   * @internal */
  static async awaitSetup(probe: SetupProbe, timeout: number, interval = 1_000): Promise<void> {
    const deadline = Date.now() + timeout;
    for (;;) {
      const exited = await probe.exited();
      if (exited !== undefined) throw new Error(exited);
      let code: number;
      try {
        code = await probe.health();
      } catch (e) {
        // It may have exited since it was asked
        const since = await probe.exited().catch(() => undefined);
        if (since !== undefined) throw new Error(since, { cause: e });
        throw new Error("Couldn't ask the container whether TM1 is set up", { cause: e });
      }
      if (code === 0) return;
      if (Date.now() > deadline) throw new Error(`Timed out after ${duration(timeout)} waiting for TM1 to be set up. ${await probe.logs()}`);
      await sleep(interval);
    }
  }

  /** Puts the engine and its datastore on one network: the one given, or a new one. */
  #joinNetwork(): void {
    if (this.getNetwork() === undefined) this.withNetwork(Network.newNetwork());
    this.#datastore.withNetwork(this.getNetwork());
  }

  /** Runs the image's tm1-engine command, and returns what it printed. */
  async #command(timeout: number, ...args: string[]): Promise<string> {
    if (!this.isRunning()) throw new IllegalStateException("The container isn't started yet");
    let result: { exitCode: number; stdout: string; stderr: string };
    try {
      result = await this.getContainer().exec(["timeout", String(timeout), "tm1-engine", ...args]);
    } catch (e) {
      throw new Error(`Couldn't run tm1-engine ${args[0]} in the container`, { cause: e });
    }
    if (result.exitCode !== 0) {
      throw new IllegalStateException(`tm1-engine ${args.join(" ")} failed (exit ${result.exitCode}): ${(result.stderr + result.stdout).trim()}`);
    }
    return result.stdout;
  }

  /** Asks the image for the engine's addresses and credentials, and keeps them. */
  async #readCredentials(): Promise<Credentials> {
    const credentials = JSON.parse(await this.#command(COMMAND_TIMEOUT, "credentials", "--json")) as Credentials;
    this.#credentials = credentials;
    return credentials;
  }

  /** The credentials as last read. */
  #read(): Credentials {
    if (!this.#credentials) throw new IllegalStateException("The container isn't started yet");
    return this.#credentials;
  }

  /** The databases in the credentials as last read. */
  #known(): Tm1Database[] {
    return (this.#read().instances ?? []).flatMap((instance) =>
      (instance.databases ?? []).map((database) => new Tm1Database(this, instance.name ?? "", database.name ?? "")),
    );
  }

  /** Reads the version from the first database, logged in as its administrator. Plain `fetch`
   * rather than tm1-client, so a client bug can't break the harness that tests the client. */
  async #readVersion(first: Tm1Database | undefined): Promise<Tm1Version> {
    if (!first) throw new IllegalStateException("The engine has no database to read its version from");
    const request = await first.login().authorize({ signal: AbortSignal.timeout(REQUEST_TIMEOUT) });
    let response: Response;
    let body: string;
    try {
      response = await fetch(first.getRestUrl() + "Configuration/ProductVersion/$value", request);
      body = await response.text();
    } catch (e) {
      throw new Error("Couldn't read the TM1 version from " + first.getRestUrl(), { cause: e });
    }
    if (response.status !== 200) throw new IllegalStateException(`TM1 didn't give its version (HTTP ${response.status}): ${body}`);
    return Tm1Version.parse(body);
  }

  #mountSource(): void {
    const source = this.#source;
    if (source instanceof Tm1EngineSource.InstallDir) {
      this.withBind(source.dir, INSTALL_PATH, true);
    } else if (source instanceof Tm1EngineSource.InstallVolume) {
      this.withBind(source.name, INSTALL_PATH, false);
    } else if (source instanceof Tm1EngineSource.Bundle) {
      this.withBind(source.dir, "/ibm", true);
      if (source.file !== undefined) this.withEnv("TM1_ENGINE_BUNDLE", source.file);
      this.withBind(Tm1EngineContainer.CACHE_VOLUME, "/tm1/cache", false);
    } else if (source instanceof Tm1EngineSource.BundleUrl) {
      this.withEnv("TM1_ENGINE_BUNDLE_URL", source.text);
      if (source.sha256 !== undefined) this.withEnv("TM1_ENGINE_BUNDLE_SHA256", source.sha256);
      this.withBind(Tm1EngineContainer.CACHE_VOLUME, "/tm1/cache", false);
    }
    // Tm1EngineSource.PrivateImage: TM1 is already in the image
  }

  /** The image for `source`: a private image as it is; otherwise `TM1_ENGINE_IMAGE` if set;
   * otherwise {@link DEFAULT_IMAGE} tagged as `tm1-server`'s is, with this library's version
   * (`edge` for a development build).
   * @internal */
  static defaultImage(source: Tm1EngineSource): string {
    return source instanceof Tm1EngineSource.PrivateImage
      ? source.image
      : Tm1ServerContainer.imageFor(Tm1EngineContainer.ENGINE_IMAGE, Tm1EngineContainer.DEFAULT_IMAGE);
  }
}

/** `instance/name`, each checked against the image's rule for its name. */
function qualified(instance: string, name: string): string {
  return requireInstance(instance) + "/" + requireName(name, "database");
}

function requireInstance(name: string): string {
  if (typeof name !== "string" || !INSTANCE.test(name)) {
    throw new IllegalArgumentException(`A TM1 instance's name is letters, digits, '_' and '-', starting with a letter or digit: ${name}`);
  }
  return name;
}

function requireName(name: string, what: string): string {
  if (typeof name !== "string" || !NAME.test(name)) {
    throw new IllegalArgumentException(`A TM1 ${what}'s name is at least three letters, digits, '_' and '-', starting with a letter or digit: ${name}`);
  }
  return name;
}

/** Milliseconds as Java prints a `Duration` (`PT5M`, `PT1M30S`, `PT0.05S`), so a message is the
 * Java module's word for word. */
function duration(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  const seconds = (ms % 60_000) / 1_000;
  return "PT" + (minutes > 0 ? minutes + "M" : "") + (seconds > 0 || minutes === 0 ? seconds + "S" : "");
}
