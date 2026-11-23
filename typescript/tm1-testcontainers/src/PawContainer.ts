// SPDX-License-Identifier: Apache-2.0

import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { fileURLToPath } from "node:url";
import { GenericContainer as TcGenericContainer, log, Wait } from "testcontainers";
import { DockerHost } from "./docker.js";
import type { GenericContainer, Startable } from "./GenericContainer.js";
import { Messages } from "./messages.js";
import { PawSession } from "./PawSession.js";
import { PawSource } from "./PawSource.js";
import { IllegalArgumentException, IllegalStateException, Settings } from "./settings.js";
import { Tm1AdminServerContainer } from "./Tm1AdminServerContainer.js";
import { Tm1ServerContainer } from "./Tm1ServerContainer.js";
import { Tm1WebContainer } from "./Tm1WebContainer.js";

const COMPOSE_PROJECT = "paw";
const SCRIPT = "paw-local.sh";
const RUNNER_BASE = "docker:cli";
const DEFAULT_STARTUP_TIMEOUT = 20 * 60_000;
const READY_TIMEOUT = 10 * 60_000;

let shared: Promise<PawContainer> | undefined;
let sharedFailure: string | undefined;

/** IBM Planning Analytics Workspace (PAW) Local in Docker, for tests, started from the user's own
 * PAW Local zip against a {@link Tm1ServerContainer}, as the Java `PawContainer`.
 *
 * ```ts
 * const tm1 = new Tm1ServerContainer(tm1Source);
 * await using paw = new PawContainer(pawSource).withTm1Server(tm1).acceptLicense();
 * await paw.start();           // starts tm1 first
 * const url = paw.getUrl();    // http://localhost:54321/
 * ```
 *
 * IBM ships PAW Local as about 30 Docker images, with compose files and a `paw.sh` that starts
 * them. This uses those as they are: it runs `docker/paw/paw-local.sh` (shipped in this package),
 * which unpacks the zip, loads the images when any is missing, writes PAW's configuration and runs
 * IBM's `paw.sh`, inside a small `docker:cli` "runner" container that talks to the Docker daemon.
 * The unpacked zip and PAW's secrets live in the Docker volume {@link PawContainer.HOME_VOLUME}.
 * Users log on as users of the TM1 server (`PAAuthMode=tm1`), and every PAW container is
 * connected to TM1's network.
 *
 * - **The license:** starting fails unless IBM's PAW license is accepted, with
 *   {@link acceptLicense} or `PAW_ACCEPT_LICENSE=true`.
 * - **One PAW per Docker daemon:** IBM's compose files give every container a fixed name.
 * - **Heavy:** about 15 GB of memory; the first start loads about 8 GB of images.
 * - **Cleanup:** {@link stop} removes the PAW containers and keeps PAW's data. IBM's containers
 *   carry no Testcontainers labels, so if the process dies they keep running, and the next start
 *   reuses them. */
export class PawContainer implements Startable {
  /** Accepts IBM's license for PAW when `true`. */
  static readonly ACCEPT_LICENSE = "PAW_ACCEPT_LICENSE";

  /** The Docker volume holding the unpacked PAW Local zip and PAW's secrets, shared by every PAW
   * started this way. */
  static readonly HOME_VOLUME = "tm1-paw-home";

  /** The network IBM's compose files put PAW on.
   * @internal */
  static readonly PAW_NETWORK = "paw_default";

  /** The Admin Server's REST port in a {@link Tm1ServerContainer}.
   * @internal */
  static readonly ADMIN_PORT = 5895;

  readonly #source: PawSource;
  readonly #settings = new Map<string, string>();
  #tm1: Tm1ServerContainer | undefined;
  #adminServer: Tm1AdminServerContainer | undefined;
  #licenseAccepted = PawContainer.licenseAcceptedBySetting();
  #startupTimeout = DEFAULT_STARTUP_TIMEOUT;
  #httpPort = 0;
  #host = "localhost";
  #running = false;
  #starting: Promise<void> | undefined;

  /** A PAW started from `source`'s zip. */
  constructor(source: PawSource) {
    if (!(source instanceof PawSource)) throw new TypeError("source must be a PawSource");
    this.#source = source;
  }

  /** The PAW most tests share: one per test run, started on first use with the source
   * {@link PawSource.discover} finds, against {@link Tm1ServerContainer.shared}. If starting it
   * fails, later calls in the same run fail at once with the same error, rather than each waiting
   * for PAW again.
   * @throws IllegalStateException if no PAW or TM1 source is configured, or the license isn't
   * accepted */
  static shared(): Promise<PawContainer> {
    if (sharedFailure !== undefined) return Promise.reject(new IllegalStateException("The shared PAW failed to start earlier in this run: " + sharedFailure));
    shared ??= (async () => {
      const source = PawSource.discover();
      if (!source) throw new IllegalStateException(Messages.PAW_NO_SOURCE);
      const paw = new PawContainer(source).withTm1Server(await Tm1ServerContainer.shared());
      await paw.start();
      return paw;
    })().catch((e: unknown) => {
      sharedFailure = e instanceof Error ? e.message : String(e);
      throw e;
    });
    return shared;
  }

  /** Logs PAW users on to `tm1`, and lists servers from its Admin Server. Starts `tm1` first if
   * it isn't running. */
  withTm1Server(tm1: Tm1ServerContainer): this {
    if (!(tm1 instanceof Tm1ServerContainer)) throw new TypeError("tm1 must be a Tm1ServerContainer");
    this.#tm1 = tm1;
    return this;
  }

  /** Lists servers from a separate Admin Server instead of the TM1 server's own. Users still log
   * on through that TM1 server, which must be registered with this Admin Server and on its
   * network. */
  withAdminServer(admin: Tm1AdminServerContainer): this {
    if (!(admin instanceof Tm1AdminServerContainer)) throw new TypeError("admin must be a Tm1AdminServerContainer");
    this.#adminServer = admin;
    return this;
  }

  /** Accepts IBM's license for Planning Analytics Workspace, which is in the PAW Local zip as
   * `licenses/LA_en`. */
  acceptLicense(): this {
    this.#licenseAccepted = true;
    return this;
  }

  /** Whether {@link acceptLicense} or `PAW_ACCEPT_LICENSE=true` accepted IBM's license.
   * @internal */
  licenseAccepted(): boolean {
    return this.#licenseAccepted;
  }

  /** Sets one of `paw-local.sh`'s `PAW_*` settings, such as `PAW_JVM_ARGS` or `PAW_TM1WEB_URL`.
   * The TM1 server, ports, network and paths are set by this class.
   * @throws IllegalArgumentException if `name` doesn't start with `PAW_` */
  withSetting(name: string, value: string): this {
    if (!name.startsWith("PAW_")) throw new IllegalArgumentException("Not a paw-local.sh setting: " + name);
    if (typeof value !== "string") throw new TypeError("value must be a string");
    this.#settings.set(name, value);
    return this;
  }

  /** How long {@link start} may take to load IBM's images and start PAW, in milliseconds.
   * Default: 20 minutes. */
  withStartupTimeout(timeout: number): this {
    this.#startupTimeout = timeout;
    return this;
  }

  /** Where PAW comes from. */
  getSource(): PawSource {
    return this.#source;
  }

  /** The TM1 server set with {@link withTm1Server}, or `undefined`. */
  getTm1Server(): Tm1ServerContainer | undefined {
    return this.#tm1;
  }

  /** Whether PAW is started: `true` between {@link start} and {@link stop}. */
  isRunning(): boolean {
    return this.#running;
  }

  /** PAW's address as seen from the host, e.g. `http://localhost:54321/`.
   * @throws IllegalStateException if PAW isn't started */
  getUrl(): string {
    if (!this.#running) throw new IllegalStateException("PAW isn't started");
    return "http://" + this.#host + ":" + this.#httpPort + "/";
  }

  /** Starts the TM1 server if needed, then PAW, and waits until a TM1 call through PAW works.
   * @throws IllegalStateException if the license isn't accepted or no TM1 server is set */
  start(): Promise<void> {
    const run = (this.#starting ?? Promise.resolve()).catch(() => undefined).then(() => this.#start());
    this.#starting = run;
    return run;
  }

  async #start(): Promise<void> {
    if (this.#running) return;
    if (!this.#licenseAccepted) throw new IllegalStateException(Messages.PAW_NO_LICENSE + " Or call acceptLicense().");
    const server = this.#tm1;
    if (!server) throw new IllegalStateException("No TM1 server: set one with withTm1Server");
    if (!server.isRunning()) await server.start();
    const tm1Network = networkOf(server);
    let adminAddress = tm1Network.address;
    const admin = this.#adminServer;
    if (admin) {
      if (!admin.isRunning()) await admin.start();
      const adminNetwork = networkOf(admin);
      if (adminNetwork.id !== tm1Network.id) throw new IllegalStateException("PAW needs the Admin Server and the TM1 server on one network");
      adminAddress = adminNetwork.address;
    }
    this.#httpPort = await freePort();
    const env: Record<string, string> = {
      ...Object.fromEntries(this.#settings),
      PAW_TM1_ADMIN_URL: "http://" + adminAddress + ":" + PawContainer.ADMIN_PORT,
      PAW_TM1_LOGIN_URL: "http://" + tm1Network.address + ":" + Tm1ServerContainer.REST_PORT,
      PAW_AUTH_MODE: "tm1",
      PAW_HTTP_PORT: String(this.#httpPort),
      PAW_HTTPS_PORT: String(await freePort()),
      PAW_NETWORK: PawContainer.PAW_NETWORK,
      PAW_WAIT: "0",
    };
    log.info(`Starting PAW from ${this.#source.describe()} against the TM1 server at ${tm1Network.address} (the first start loads IBM's images)`);
    await this.#runScript("up", env, this.#startupTimeout);
    await connectToNetwork(tm1Network.id);
    this.#host = await DockerHost.hostAddress();
    this.#running = true;
    await this.#awaitReady(server);
    log.info(`PAW is up at ${this.getUrl()}, logging users on to the TM1 server at ${tm1Network.address}`);
  }

  /** Removes the PAW containers, keeping PAW's data. */
  async stop(): Promise<void> {
    if (!this.#running) return;
    this.#running = false;
    try {
      await this.#runScript("down", {}, 5 * 60_000);
    } catch (e) {
      log.warn("Couldn't stop PAW; its containers may still be running: " + String(e));
    }
  }

  /** Stops it, for `await using`: the same as {@link stop}. */
  async [Symbol.asyncDispose](): Promise<void> {
    await this.stop();
  }

  /** Whether IBM's PAW license is accepted by the `PAW_ACCEPT_LICENSE` setting.
   * @internal */
  static licenseAcceptedBySetting(): boolean {
    return (Settings.get(PawContainer.ACCEPT_LICENSE) ?? "").trim().toLowerCase() === "true";
  }

  /** `paw-local.sh`, as this package ships it (or, in this repository, `docker/paw`'s).
   * @internal */
  static script(): Buffer {
    for (const path of [new URL("../" + SCRIPT, import.meta.url), new URL("../../../docker/paw/" + SCRIPT, import.meta.url)]) {
      const file = fileURLToPath(path);
      if (existsSync(file)) return readFileSync(file);
    }
    throw new IllegalStateException("Missing " + SCRIPT + " in @skipcheck/tm1-testcontainers");
  }

  /** Runs `paw-local.sh <command>` in the runner container, until it exits. */
  async #runScript(command: string, env: Record<string, string>, timeout: number): Promise<void> {
    const docker = (await DockerHost.client()).container.dockerode;
    const home = await homeMountpoint(docker);
    const zipName = basename(this.#source.zip);
    const socket = await DockerHost.socketPath();
    let output = "";
    const runner = new TcGenericContainer(await runnerImage())
      .withCommand([SCRIPT, command])
      .withEnvironment({ ...env, PAW_ACCEPT_LICENSE: "true", PAW_ZIP: "/zip/" + zipName, PAW_HOME: home })
      // The Docker socket, as Testcontainers' Ryuk mounts it, and PAW's home volume at its path
      // on the Docker host: IBM's compose files bind-mount files from it by that path, which the
      // daemon resolves
      .withBindMounts([
        { source: this.#source.zip, target: "/zip/" + zipName, mode: "ro" },
        { source: socket, target: "/var/run/docker.sock", mode: "rw" },
        { source: PawContainer.HOME_VOLUME, target: home, mode: "rw" },
      ])
      .withLogConsumer((stream) => {
        stream.on("data", (line: Buffer | string) => {
          output += line.toString();
          log.debug("paw-local: " + line.toString().trimEnd());
        });
      })
      .withWaitStrategy(Wait.forOneShotStartup())
      .withStartupTimeout(timeout);
    try {
      const started = await runner.start();
      await started.stop().catch(() => undefined);
    } catch (e) {
      throw new IllegalStateException(`paw-local.sh ${command} failed:\n${output}`, { cause: e });
    }
  }

  /** Polls until a logon and a TM1 call through PAW work. */
  async #awaitReady(server: Tm1ServerContainer): Promise<void> {
    const deadline = Date.now() + READY_TIMEOUT;
    let last = "no answer yet";
    while (Date.now() < deadline) {
      try {
        const session = await new PawSession(this.getUrl()).login(server.getUsername(), server.getPassword());
        const servers = await session.servers();
        if (servers.length > 0) {
          const version = await session.tm1Get(servers[0]!, "Configuration/ProductVersion/$value");
          if (version.status === 200) return;
          last = `a TM1 call through PAW answered ${version.status}: ${version.body}`;
        } else {
          last = "PAW lists no TM1 servers yet";
        }
      } catch (e) {
        last = e instanceof Error ? e.message : String(e);
      }
      await new Promise((r) => setTimeout(r, 5_000));
    }
    throw new IllegalStateException(`PAW wasn't ready within ${READY_TIMEOUT / 60_000} minutes: ${last}`);
  }

  /** Where PAW comes from, and its address once it runs, for logs. */
  toString(): string {
    return "PawContainer[" + this.#source.describe() + (this.#running ? ", " + this.getUrl() : "") + "]";
  }
}

type Dockerode = Awaited<ReturnType<typeof DockerHost.client>>["container"]["dockerode"];

/** The home volume's path on the Docker host, creating the volume if needed. */
async function homeMountpoint(docker: Dockerode): Promise<string> {
  try {
    return ((await docker.getVolume(PawContainer.HOME_VOLUME).inspect()) as { Mountpoint: string }).Mountpoint;
  } catch (e) {
    if ((e as { statusCode?: number }).statusCode !== 404) throw e;
    await docker.createVolume({ Name: PawContainer.HOME_VOLUME });
    return ((await docker.getVolume(PawContainer.HOME_VOLUME).inspect()) as { Mountpoint: string }).Mountpoint;
  }
}

/** Connects every PAW container to the TM1 server's network. PAW calls TM1 at the address the
 * Admin Server advertises, which is on that network, but IBM's services find each other by
 * container name, which only works on their own network. */
async function connectToNetwork(networkId: string): Promise<void> {
  const docker = (await DockerHost.client()).container.dockerode;
  const containers = await docker.listContainers({ filters: { label: ["com.docker.compose.project=" + COMPOSE_PROJECT] } });
  for (const container of containers) {
    const attached = Object.values(container.NetworkSettings?.Networks ?? {}).some((n) => n.NetworkID === networkId);
    if (!attached) await docker.getNetwork(networkId).connect({ Container: container.Id });
  }
}

/** The TM1 server's network: its {@link Network}, or Docker's default bridge network. */
function networkOf(container: GenericContainer): { id: string; address: string } {
  const address = Tm1WebContainer.addressOn(container, "PAW");
  const name = container.getNetwork()?.getName() ?? "bridge";
  return { id: container.getContainer().getNetworkId(name), address };
}

/** The runner image: `docker:cli` with bash, curl and `paw-local.sh`, tagged by the script's hash
 * so a changed script builds a new one. */
async function runnerImage(): Promise<string> {
  const script = PawContainer.script();
  const name = "tm1-paw-runner:" + createHash("sha256").update(script).digest("hex").slice(0, 12);
  const context = mkdtempSync(join(tmpdir(), "tm1-paw-runner-"));
  try {
    writeFileSync(join(context, SCRIPT), script);
    writeFileSync(
      join(context, "Dockerfile"),
      `FROM ${RUNNER_BASE}\nRUN apk add --no-cache bash curl\nCOPY ${SCRIPT} /usr/local/bin/${SCRIPT}\nRUN chmod 755 /usr/local/bin/${SCRIPT}\n`,
    );
    await TcGenericContainer.fromDockerfile(context).build(name, { deleteOnExit: false });
    return name;
  } finally {
    rmSync(context, { recursive: true, force: true });
  }
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, () => {
      const address = server.address();
      server.close(() => (typeof address === "object" && address ? resolve(address.port) : reject(new Error("Couldn't find a free port for PAW"))));
    });
  });
}
