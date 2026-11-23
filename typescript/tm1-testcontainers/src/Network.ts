// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { Network as TcNetwork, StartedNetwork } from "testcontainers";
import { DockerHost } from "./docker.js";

/** A Docker network for containers that work together, as Testcontainers for Java's `Network`:
 * made without Docker (so wiring can be set up and checked anywhere), created when the first
 * container on it starts, and removed after the run (by Testcontainers' Ryuk) or by
 * {@link Network.close}. */
export class Network implements AsyncDisposable {
  readonly #name: () => string;
  readonly #create: () => Promise<StartedNetwork>;
  #started: Promise<StartedNetwork> | undefined;
  readonly #owned: boolean;

  private constructor(name: () => string, create: () => Promise<StartedNetwork>, owned: boolean) {
    this.#name = name;
    this.#create = create;
    this.#owned = owned;
  }

  /** A new network, with a unique name. */
  static newNetwork(): Network {
    const name = "tm1-testcontainers-" + randomUUID().replace(/-/g, "").slice(0, 12);
    return new Network(
      () => name,
      () => new TcNetwork({ nextUuid: () => name }).start(),
      true,
    );
  }

  /** A network started with Testcontainers for Node, to share with containers made elsewhere. */
  static of(network: StartedNetwork): Network {
    return new Network(
      () => network.getName(),
      () => Promise.resolve(network),
      false,
    );
  }

  /** A reused environment's network, kept between test runs: named `name()` (asked for when it's
   * first needed), found by that name if it's there and created with `labels` if not. It carries
   * none of Testcontainers' session labels, so Ryuk doesn't remove it after the run, and
   * {@link close} keeps it for the next.
   * @internal */
  static reusable(name: () => string, labels: () => Record<string, string>): Network {
    let fixed: string | undefined;
    const named = (): string => (fixed ??= name());
    return new Network(named, () => findOrCreate(named(), labels()), false);
  }

  /** Its Docker name. */
  getName(): string {
    return this.#name();
  }

  /** Its Docker ID, once a container on it has started. */
  async getId(): Promise<string> {
    return (await this.create()).getId();
  }

  /** Whether it's been created in Docker. */
  isCreated(): boolean {
    return this.#started !== undefined;
  }

  /** Creates it in Docker, once.
   * @internal */
  create(): Promise<StartedNetwork> {
    this.#started ??= this.#create().catch((e: unknown) => {
      this.#started = undefined;
      throw e;
    });
    return this.#started;
  }

  /** Removes it, if this library created it (a reused environment's network is kept). */
  async close(): Promise<void> {
    const started = this.#started;
    if (!started || !this.#owned) return;
    this.#started = undefined;
    await (await started).stop();
  }

  /** Removes the network, for `await using`: the same as {@link close}. */
  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }

  /** The network's name, for logs. */
  toString(): string {
    return "Network[" + this.getName() + "]";
  }
}

/** The network named `name`, created if it isn't there. */
async function findOrCreate(name: string, labels: Record<string, string>): Promise<StartedNetwork> {
  const client = await DockerHost.client();
  const docker = client.container.dockerode;
  const found = await find(docker, name);
  if (found !== undefined) return new StartedNetwork(client, name, docker.getNetwork(found));
  try {
    const created = await client.network.create({ Name: name, CheckDuplicate: true, Driver: "bridge", Labels: labels });
    return new StartedNetwork(client, name, created);
  } catch (e) {
    // Another test run created it first
    const again = (e as { statusCode?: number }).statusCode === 409 ? await find(docker, name) : undefined;
    if (again === undefined) throw e;
    return new StartedNetwork(client, name, docker.getNetwork(again));
  }
}

type Dockerode = Awaited<ReturnType<typeof DockerHost.client>>["container"]["dockerode"];

/** The ID of the network named exactly `name`: Docker's name filter matches part of a name. */
async function find(docker: Dockerode, name: string): Promise<string | undefined> {
  const networks = await docker.listNetworks({ filters: { name: [name] } });
  return networks.find((n) => n.Name === name)?.Id;
}
