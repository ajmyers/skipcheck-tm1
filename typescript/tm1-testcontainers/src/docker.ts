import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { getContainerRuntimeClient, type ContainerRuntimeClient } from "testcontainers";

let available: Promise<boolean> | undefined;

/** The Docker daemon Testcontainers uses, for what its containers don't cover (PAW's compose
 * containers, volumes), as Java's `DockerClientFactory`. */
export const DockerHost = {
  /** Whether a Docker daemon answers; asked once per run. */
  available(): Promise<boolean> {
    available ??= getContainerRuntimeClient().then(
      () => true,
      () => false,
    );
    return available;
  },

  client(): Promise<ContainerRuntimeClient> {
    return getContainerRuntimeClient();
  },

  /** The Docker host's address, as Java's `dockerHostIpAddress()`. */
  async hostAddress(): Promise<string> {
    return (await getContainerRuntimeClient()).info.containerRuntime.host;
  },

  /** The daemon's socket, as a container that drives Docker mounts it (Java's
   * `getRemoteDockerUnixSocketPath()`). */
  async socketPath(): Promise<string> {
    return (await getContainerRuntimeClient()).info.containerRuntime.remoteSocketPath || "/var/run/docker.sock";
  },

  /** Whether reuse is switched on, as Testcontainers for Java reads it:
   * `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or
   * `TESTCONTAINERS_REUSE_ENABLE=true`. Testcontainers for Node reuses whenever a container asks
   * for it; this keeps Java's opt-in, so a "reused" container is otherwise an ordinary one, removed
   * after the run. */
  reuseEnabled(): boolean {
    if ((process.env["TESTCONTAINERS_REUSE_ENABLE"] ?? "").trim().toLowerCase() === "true") return true;
    const file = join(homedir(), ".testcontainers.properties");
    if (!existsSync(file)) return false;
    for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
      const [key, value] = line.split("=", 2);
      if (key?.trim() === "testcontainers.reuse.enable") return value?.trim().toLowerCase() === "true";
    }
    return false;
  },
};
