// SPDX-License-Identifier: Apache-2.0

import type { WaitStrategy } from "testcontainers";
import { describe, expect, it } from "vitest";
import { ExitAwareWait } from "../src/GenericContainer.js";

type Container = Parameters<WaitStrategy["waitUntilReady"]>[0];

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
 * as Java's HttpOrExitWaitStrategyTest checks. */
describe("ExitAwareWait", () => {
  it("fails at once with the exit code when the container has exited", async () => {
    const started = Date.now();
    await expect(new ExitAwareWait(forever).waitUntilReady(container(false, 3), undefined as never)).rejects.toThrow(
      "The container exited while starting, with code 3. The end of its log:",
    );
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
