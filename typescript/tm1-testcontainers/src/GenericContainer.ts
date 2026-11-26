// SPDX-License-Identifier: Apache-2.0

import { existsSync, statSync } from "node:fs";
import { text } from "node:stream/consumers";
import { getContainerRuntimeClient, GenericContainer as TcGenericContainer, log, Wait, type StartedTestContainer, type WaitStrategy } from "testcontainers";
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
  readonly #labels = new Map<string, string>();
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

  /** The labels set with {@link withLabel}. */
  getLabels(): ReadonlyMap<string, string> {
    return this.#labels;
  }

  /** Whether it asks to be kept for later runs ({@link withReuse}). */
  isShouldBeReused(): boolean {
    return this.#reuse;
  }

  /** The files and folders copied in with {@link withCopyFileToContainer}: the path in the
   * container to the path on the host.
   * @internal */
  getCopiedFiles(): ReadonlyMap<string, string> {
    return new Map(this.#copies.map((c) => [c.target, c.source]));
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
  /** Sets several environment variables at once. */
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

  /** Adds a Docker label. */
  withLabel(key: string, value: string): this {
    this.#labels.set(key, value);
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

  /** Stops it, for `await using`: the same as {@link stop}. */
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

  /** The platform to run the image for. TM1 and IBM's other servers only exist for x86-64, so on
   * Apple Silicon they run under Rosetta; a container whose image is built for the machine says
   * `undefined`, as the Java module sets no platform for it. */
  protected platform(): string | undefined {
    return "linux/amd64";
  }

  /** Runs once what it depends on is running, before {@link configure}. */
  protected async dependenciesStarted(): Promise<void> {}

  /** Runs once it's ready. */
  protected async containerIsStarted(): Promise<void> {}

  /** Logs `message`, as Testcontainers logs its own (`DEBUG=testcontainers*`). */
  protected log(message: string): void {
    log.info(message, this.#container ? { containerId: this.#container.getId() } : undefined);
  }

  async #build(): Promise<TcGenericContainer> {
    let c = new TcGenericContainer(this.#image).withEnvironment(Object.fromEntries(this.#env)).withLabels({
      ...Object.fromEntries(this.#labels),
      "io.skipcheck.tm1.testcontainers": "typescript",
    });
    const platform = this.platform();
    if (platform !== undefined) c = c.withPlatform(platform);
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
    if (w)
      c = c
        .withWaitStrategy(new ExitAwareWait(Wait.forHttp(w.path, w.port, { abortOnContainerExit: true }).forStatusCodeMatching(w.ok)))
        .withStartupTimeout(w.timeout);
    if (this.#network) {
      // Testcontainers for Node takes only a network that's been created
      c = c.withNetwork(await this.#network.create());
      if (this.#aliases.length > 0) c = c.withNetworkAliases(...this.#aliases);
    }
    return c;
  }
}

/** Stops waiting as soon as the container has exited, as Java's wait does, and says why: its
 * exit code and the end of its log. Testcontainers' own HTTP wait would keep trying until its
 * timeout (its abortOnContainerExit stops only for "exited", not "dead"), so a TM1 that can't
 * start cost the whole startup timeout before anything said so.
 * @internal */
export class ExitAwareWait implements WaitStrategy {
  /** Wraps `inner`, the wait for the container to be ready. */
  constructor(private readonly inner: WaitStrategy) {}

  /** Waits as `inner` does, until the container has exited. */
  async waitUntilReady(...[container, boundPorts, startTime]: Parameters<WaitStrategy["waitUntilReady"]>): Promise<void> {
    let done = false;
    const exited = (async (): Promise<never> => {
      for (;;) {
        await new Promise((r) => setTimeout(r, 1_000).unref());
        if (done) return new Promise<never>(() => {});
        const state = await container.inspect().then(
          (info) => info.State,
          () => undefined,
        );
        if (state && !state.Running) throw new Error(await exitMessage(container, state.ExitCode));
      }
    })();
    try {
      await Promise.race([this.inner.waitUntilReady(container, boundPorts, startTime), exited]);
    } catch (e) {
      const state = await container.inspect().then(
        (info) => info.State,
        () => undefined,
      );
      if (!state || state.Running) throw e;
      throw new Error(await exitMessage(container, state.ExitCode), { cause: e });
    } finally {
      done = true;
      exited.catch(() => {});
    }
  }

  /** Sets `inner`'s timeout. */
  withStartupTimeout(startupTimeoutMs: number): this {
    this.inner.withStartupTimeout(startupTimeoutMs);
    return this;
  }

  /** Whether `inner`'s timeout is set. */
  isStartupTimeoutSet(): boolean {
    return this.inner.isStartupTimeoutSet();
  }

  /** `inner`'s timeout. */
  getStartupTimeout(): number {
    return this.inner.getStartupTimeout();
  }
}

/** "The container exited while starting, with code …", and the last 30 lines of its log, read
 * for at most 2 s. */
async function exitMessage(container: Parameters<WaitStrategy["waitUntilReady"]>[0], code: number): Promise<string> {
  const log = await Promise.race([
    getContainerRuntimeClient()
      .then((client) => client.container.logs(container, { tail: 30 }))
      .then((stream) => text(stream))
      .catch(() => ""),
    new Promise<string>((r) => setTimeout(() => r(""), 2_000).unref()),
  ]);
  return `The container exited while starting, with code ${code}. The end of its log:\n${log.trim()}`;
}
