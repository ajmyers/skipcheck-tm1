import { randomUUID } from "node:crypto";
import { Network as TcNetwork, type StartedNetwork } from "testcontainers";

/** A Docker network for containers that work together, as Testcontainers for Java's `Network`:
 * made without Docker (so wiring can be set up and checked anywhere), created when the first
 * container on it starts, and removed after the run (by Testcontainers' Ryuk) or by
 * {@link Network.close}. */
export class Network implements AsyncDisposable {
  readonly #name: string;
  readonly #create: () => Promise<StartedNetwork>;
  #started: Promise<StartedNetwork> | undefined;
  readonly #owned: boolean;

  private constructor(name: string, create: () => Promise<StartedNetwork>, owned: boolean) {
    this.#name = name;
    this.#create = create;
    this.#owned = owned;
  }

  /** A new network, with a unique name. */
  static newNetwork(): Network {
    const name = "tm1-testcontainers-" + randomUUID().replace(/-/g, "").slice(0, 12);
    return new Network(name, () => new TcNetwork({ nextUuid: () => name }).start(), true);
  }

  /** A network started with Testcontainers for Node, to share with containers made elsewhere. */
  static of(network: StartedNetwork): Network {
    return new Network(network.getName(), () => Promise.resolve(network), false);
  }

  /** Its Docker name. */
  getName(): string {
    return this.#name;
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

  /** Removes it, if this library created it. */
  async close(): Promise<void> {
    const started = this.#started;
    if (!started || !this.#owned) return;
    this.#started = undefined;
    await (await started).stop();
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }

  toString(): string {
    return "Network[" + this.#name + "]";
  }
}
