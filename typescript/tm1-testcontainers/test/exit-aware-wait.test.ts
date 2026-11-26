// SPDX-License-Identifier: Apache-2.0

import { Readable } from "node:stream";
import type { WaitStrategy } from "testcontainers";
import { describe, expect, it, vi } from "vitest";
import { ExitAwareWait } from "../src/GenericContainer.js";

type Container = Parameters<WaitStrategy["waitUntilReady"]>[0];

/** Docker's side of reading a container's log: the lines there are to read, and what was asked for. */
const docker = vi.hoisted(() => ({ log: [] as string[], asked: [] as unknown[] }));

// The log is read through Testcontainers' Docker client: this one answers with the end of
// docker.log, as Docker does for "tail", so no Docker is needed
vi.mock("testcontainers", async (original) => ({
  ...(await original<typeof import("testcontainers")>()),
  getContainerRuntimeClient: async () => ({
    container: {
      logs: async (_container: unknown, options: { tail: number }) => {
        docker.asked.push(options);
        return Readable.from([docker.log.slice(-options.tail).join("\n")]);
      },
    },
  }),
}));

/** A wait that never ends by itself, as an HTTP wait on a container that has died. */
const forever: WaitStrategy = {
  waitUntilReady: () => new Promise<void>(() => {}),
  withStartupTimeout() {
    return this;
  },
  isStartupTimeoutSet: () => false,
  getStartupTimeout: () => 300_000,
};

function container(running: boolean, exitCode: number): Container {
  return { inspect: async () => ({ State: { Running: running, ExitCode: exitCode } }) } as unknown as Container;
}

/** The wait every container uses, without Docker: it stops once the container has exited,
 * as Java's HttpOrExitWaitStrategyTest checks. Java's wait does the HTTP itself; here it's
 * Testcontainers' HTTP wait, which needs Docker, wrapped in ExitAwareWait, so these give it a wait
 * that answers as the HTTP one would: when the path does, and with its failure when it never does. */
describe("ExitAwareWait", () => {
  it("fails at once with the exit code and the end of the log when the container has exited", async () => {
    docker.log = Array.from({ length: 40 }, (_, i) => `line ${i + 1}`);
    docker.asked = [];
    const started = Date.now();
    const failure: Error = await new ExitAwareWait(forever).waitUntilReady(container(false, 3), undefined as never).then(
      () => expect.fail("the wait ended without an error"),
      (e: Error) => e,
    );
    expect(failure.message).toContain("The container exited while starting, with code 3. The end of its log:");
    expect(failure.message.startsWith("The container exited while starting, with code 3. The end of its log:\nline 11\n")).toBe(true);
    expect(failure.message.endsWith("line 40")).toBe(true);
    expect(failure.message).not.toContain("line 10\n");
    // Read for the failure, and again when the wait that lost the race is given it
    expect(docker.asked.length).toBeGreaterThan(0);
    for (const asked of docker.asked) expect(asked).toEqual({ tail: 30 });
    expect(Date.now() - started).toBeLessThan(10_000);
  });

  it("returns when the wait it wraps does", async () => {
    const ready: WaitStrategy = { ...forever, waitUntilReady: async () => {} };
    await new ExitAwareWait(ready).waitUntilReady(container(true, 0), undefined as never);
  });

  it("passes on a failure while the container still runs", async () => {
    const failing: WaitStrategy = { ...forever, waitUntilReady: async () => Promise.reject(new Error("timed out")) };
    await expect(new ExitAwareWait(failing).waitUntilReady(container(true, 0), undefined as never)).rejects.toThrow("timed out");
  });
});
