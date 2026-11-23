import { existsSync, statSync } from "node:fs";
import { GenericContainer as TcGenericContainer, log, Wait, type StartedTestContainer } from "testcontainers";
import { DockerHost } from "./docker.js";
import { IllegalStateException } from "./settings.js";
import type { Network } from "./Network.js";

/** Something that starts and stops: a container, PAW (several containers run by IBM's compose
 * file), or a whole {@link PlanningAnalyticsEnvironment}, as Testcontainers for Java's
 * `Startable`. `await using` stops it. */
export interface Startable extends AsyncDisposable {
  /** Starts it, and what it depends on first; nothing happens if it's running. */
  start(): Promise<void>;
  /** Stops and removes it. */
  stop(): Promise<void>;
  /** Whether it's running. */
  isRunning(): boolean;
}

/** A bind mount: a host folder or a named volume, mounted at `target`. */
interface Bind {
  source: string;
  target: string;
  readOnly: boolean;
}

interface WaitFor {
  path: string;
  port: number;
  ok: (status: number) => boolean;
  timeout: number;
}

/** A container configured as Testcontainers for Java's `GenericContainer` is: its environment,
 * network, aliases and dependencies are set and read before it starts (which is what lets
 * {@link PlanningAnalyticsEnvironment} wire containers together, and tests check the wiring
 * without Docker), and it's built with Testcontainers for Node's `GenericContainer` when it
 * starts. {@link getContainer} is the started container, for anything not wrapped here. The
 * `with…` methods return the container, so they chain. */
export abstract class GenericContainer implements Startable {
  readonly #image: string;
  readonly #env = new Map<string, string>();
  readonly #aliases: string[] = [];
  readonly #ports: number[] = [];
  readonly #dependencies: Startable[] = [];
  readonly #extraHosts = new Map<string, string>();
  readonly #copies: { source: string; target: string }[] = [];
  readonly #binds: Bind[] = [];
  #command: string[] = [];
  #hostname: string | undefined;
  #network: Network | undefined;
  #reuse = false;
  #wait: WaitFor | undefined;
  #container: StartedTestContainer | undefined;
  #starting: Promise<void> | undefined;

  protected constructor(image: string) {
    if (!image || image.trim() === "") throw new IllegalStateException("image must not be blank");
    this.#image = image;
  }

  /** The image it runs. */
  getDockerImageName(): string {
    return this.#image;
  }

  /** Its environment variables, so far. */
  getEnvMap(): ReadonlyMap<string, string> {
    return this.#env;
  }

  /** The command it runs, if not the image's. */
  getCommandParts(): readonly string[] {
    return this.#command;
  }

  /** The ports it exposes on the Docker host. */
  getExposedPorts(): readonly number[] {
    return this.#ports;
  }

  /** The network it joins, or `undefined` for Docker's default bridge. */
  getNetwork(): Network | undefined {
    return this.#network;
  }

  /** Its names on {@link getNetwork}. */
  getNetworkAliases(): readonly string[] {
    return this.#aliases;
  }

  /** What starts before it. */
  getDependencies(): readonly Startable[] {
    return this.#dependencies;
  }

  /** The bind mounts, `source:target[:ro]`, the source a host folder or a volume.
   * @internal */
  getBinds(): string[] {
    return this.#binds.map((b) => b.source + ":" + b.target + (b.readOnly ? ":ro" : ""));
  }

  /** The started container.
   * @throws IllegalStateException if it isn't started */
  getContainer(): StartedTestContainer {
    if (!this.#container) throw new IllegalStateException("The container isn't started yet");
    return this.#container;
  }

  isRunning(): boolean {
    return this.#container !== undefined;
  }

  /** The host its ports are mapped on. */
  getHost(): string {
    return this.getContainer().getHost();
  }

  /** The Docker container's ID. */
  getContainerId(): string {
    return this.getContainer().getId();
  }

  /** The host port `port` is mapped to. */
  getMappedPort(port: number): number {
    return this.getContainer().getMappedPort(port);
  }

  /** Sets an environment variable, or several. */
  withEnv(name: string, value: string): this;
  withEnv(variables: Readonly<Record<string, string>> | ReadonlyMap<string, string>): this;
  withEnv(name: string | Readonly<Record<string, string>> | ReadonlyMap<string, string>, value?: string): this {
    if (typeof name === "string") {
      if (value === undefined || value === null) throw new TypeError("value must be set for " + name);
      this.#env.set(name, value);
    } else {
      for (const [k, v] of name instanceof Map ? name : Object.entries(name)) this.#env.set(k, v);
    }
    return this;
  }

  /** Runs `commandParts` in place of the image's command. */
  withCommand(...commandParts: string[]): this {
    this.#command = [...commandParts];
    return this;
  }

  /** Exposes ports on the Docker host, each on a free port. */
  withExposedPorts(...ports: number[]): this {
    for (const p of ports) if (!this.#ports.includes(p)) this.#ports.push(p);
    return this;
  }

  /** Joins `network` (created when the first container on it starts). */
  withNetwork(network: Network | undefined): this {
    this.#network = network;
    return this;
  }

  /** Adds names it's reached by on {@link getNetwork}. */
  withNetworkAliases(...aliases: string[]): this {
    for (const a of aliases) if (!this.#aliases.includes(a)) this.#aliases.push(a);
    return this;
  }

  /** Starts `startables` before it. */
  dependsOn(...startables: Startable[]): this {
    for (const s of startables) {
      if (!s) throw new TypeError("startables must not hold null");
      if (!this.#dependencies.includes(s)) this.#dependencies.push(s);
    }
    return this;
  }

  /** Adds an `/etc/hosts` entry. */
  withExtraHost(host: string, ipAddress: string): this {
    this.#extraHosts.set(host, ipAddress);
    return this;
  }

  /** Sets the container's host name. */
  withHostname(hostname: string): this {
    this.#hostname = hostname;
    return this;
  }

  /** Keeps the container for later runs, when reuse is switched on as Testcontainers for Java
   * has it (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or
   * `TESTCONTAINERS_REUSE_ENABLE=true`); otherwise it's removed after the run. */
  withReuse(reusable: boolean): this {
    this.#reuse = reusable;
    return this;
  }

  /** Copies a host file or folder into the container before it starts. */
  withCopyFileToContainer(hostPath: string, containerPath: string): this {
    this.#copies.push({ source: hostPath, target: containerPath });
    return this;
  }

  /** How long to wait for it to be ready, in milliseconds. */
  withStartupTimeout(timeout: number): this {
    if (this.#wait) this.#wait = { ...this.#wait, timeout };
    return this;
  }

  /** Mounts a host folder or a named volume. */
  protected withBind(source: string, target: string, readOnly = false): this {
    this.#binds.push({ source, target, readOnly });
    return this;
  }

  /** Waits until `path` on `port` answers with a status `ok` accepts. */
  protected waitingForHttp(path: string, port: number, ok: (status: number) => boolean, timeout: number): void {
    this.#wait = { path, port, ok, timeout };
  }

  start(): Promise<void> {
    // One start at a time, as Java's synchronized start
    const run = (this.#starting ?? Promise.resolve()).catch(() => undefined).then(() => this.#start());
    this.#starting = run;
    return run;
  }

  async #start(): Promise<void> {
    if (this.isRunning()) return;
    // As Java's Startables.deepStart: what it depends on starts first
    await Promise.all(this.#dependencies.map((d) => d.start()));
    await this.dependenciesStarted();
    this.configure();
    const started = await (await this.#build()).start();
    this.#container = started;
    try {
      await this.containerIsStarted();
    } catch (e) {
      this.#container = undefined;
      await started.stop().catch(() => undefined);
      throw e;
    }
  }

  async stop(): Promise<void> {
    const c = this.#container;
    if (!c) return;
    this.#container = undefined;
    await c.stop();
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.stop();
  }

  /** Its log, so far. */
  async getLogs(): Promise<string> {
    const stream = await this.getContainer().logs();
    return new Promise((resolve, reject) => {
      let text = "";
      stream.on("data", (chunk: Buffer | string) => (text += chunk.toString()));
      stream.on("error", reject);
      stream.on("end", () => resolve(text));
      setTimeout(() => {
        stream.destroy();
        resolve(text);
      }, 2_000).unref();
    });
  }

  /** Finishes the configuration as it starts, once what it depends on is running; the tests call
   * it to check the wiring.
   * @internal */
  configure(): void {}

  /** Runs once what it depends on is running, before {@link configure}. */
  protected async dependenciesStarted(): Promise<void> {}

  /** Runs once it's ready. */
  protected async containerIsStarted(): Promise<void> {}

  /** Logs `message`, as Testcontainers logs its own (`DEBUG=testcontainers*`). */
  protected log(message: string): void {
    log.info(message, this.#container ? { containerId: this.#container.getId() } : undefined);
  }

  async #build(): Promise<TcGenericContainer> {
    let c = new TcGenericContainer(this.#image)
      .withEnvironment(Object.fromEntries(this.#env))
      .withLabels({ "io.skipcheck.tm1.testcontainers": "typescript" })
      // TM1 and IBM's other servers only exist for x86-64; on Apple Silicon they run under Rosetta
      .withPlatform("linux/amd64");
    if (this.#command.length > 0) c = c.withCommand(this.#command);
    if (this.#ports.length > 0) c = c.withExposedPorts(...this.#ports);
    if (this.#binds.length > 0) c = c.withBindMounts(this.#binds.map((b) => ({ source: b.source, target: b.target, mode: b.readOnly ? "ro" : "rw" })));
    if (this.#extraHosts.size > 0) c = c.withExtraHosts([...this.#extraHosts].map(([host, ipAddress]) => ({ host, ipAddress })));
    if (this.#hostname !== undefined) c = c.withHostname(this.#hostname);
    const files = this.#copies.filter((f) => !(existsSync(f.source) && statSync(f.source).isDirectory()));
    const dirs = this.#copies.filter((f) => existsSync(f.source) && statSync(f.source).isDirectory());
    if (files.length > 0)
      c = c.withCopyFilesToContainer(
        files.map((f) => ({ source: f.source, target: f.target.endsWith("/") ? f.target + f.source.split(/[\\/]/).pop() : f.target })),
      );
    if (dirs.length > 0) c = c.withCopyDirectoriesToContainer(dirs);
    if (this.#reuse && DockerHost.reuseEnabled()) c = c.withReuse();
    const w = this.#wait;
    if (w) c = c.withWaitStrategy(Wait.forHttp(w.path, w.port).forStatusCodeMatching(w.ok)).withStartupTimeout(w.timeout);
    if (this.#network) {
      // Testcontainers for Node takes only a network that's been created
      c = c.withNetwork(await this.#network.create());
      if (this.#aliases.length > 0) c = c.withNetworkAliases(...this.#aliases);
    }
    return c;
  }
}
