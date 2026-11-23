import { afterAll, beforeAll, describe, expect } from "vitest";
import { ProcessPatch, Tm1ServerException, type Thread, type Tm1Client, type Tm1ClientBuilder } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** A client beside `Live.client()`, of the same TM1 with the same settings, as another session. */
async function otherClient(configure?: (b: Tm1ClientBuilder) => Tm1ClientBuilder): Promise<Tm1Client> {
  const builder = await Live.builder();
  return (configure?.(builder) ?? builder).build();
}

/** SessionService and ThreadService against a real TM1, with a second client to look at, close and
 * interrupt: the Java client's `SessionAndThreadTm1Test`. */
describe("SessionService and ThreadService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("reads sessions, their users and threads, and closes another", async () => {
    const label = Live.name();
    const other = await otherClient((b) => b.sessionContext(label));
    try {
      await other.connect();
      const id = (await other.sessions.activeSession()).ID!;
      expect((await tm1.sessions.activeSession()).ID, "each client its own").not.toBe(id);
      expect((await tm1.sessions.list().toArray()).map((s) => s.ID)).toContain(id);
      expect((await tm1.sessions.get(id)).Context).toBe(label);
      const user = (await Live.target()).user;
      expect((await tm1.sessions.user(id)).Name?.toLowerCase()).toBe(user.toLowerCase());
      expect(await tm1.sessions.threads(id).toArray()).toBeInstanceOf(Array);
      expect((await tm1.users.sessions(user).toArray()).map((s) => s.ID)).toContain(id);

      await tm1.sessions.close(id);
      expect((await other.sessions.activeSession()).ID, "the other client logs in again, as a new session").not.toBe(id);
    } finally {
      await other.close();
    }
  });

  tm1Test("cancels a running process through its thread", { timeout: 180_000 }, async () => {
    const process = Live.name();
    await tm1.processes.create(ProcessPatch.builder().name(process).prologProcedure("i = 0;\nWhile(i < 100000000);\n  i = i + 1;\nEnd;\n").build());
    const runner = await otherClient();
    const run = runner.processes.executeWithReturn(process);
    const ran = run.then(
      () => undefined,
      (e: unknown) => e,
    );
    try {
      let running: Thread | undefined;
      for (let i = 0; i < 100 && !running; i++) {
        const threads = await tm1.threads.list().toArray();
        running = threads.find((t) => t.Function?.includes(`Processes('${process}')/tm1.ExecuteWithReturn`));
        if (!running) await new Promise((r) => setTimeout(r, 100));
      }
      expect(running, "the process's thread").toBeDefined();
      expect(running!.ObjectName, "a process run through REST shows as its request").toBe("");
      expect((await tm1.threads.get(running!.ID!)).Function).toContain(process);
      expect((await tm1.threads.session(running!.ID!)).ID).toBeDefined();
      await tm1.threads.cancel(running!.ID!);
      const e = await ran;
      expect(e, "the run's request fails, rather than reporting Aborted").toBeInstanceOf(Tm1ServerException);
      expect((e as Error).message).toContain("Cancel");
    } finally {
      await ran;
      await runner.close().catch(() => undefined);
      await tm1.processes.delete(process);
    }
  });
});
