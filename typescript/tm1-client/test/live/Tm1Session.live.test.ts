import { afterAll, beforeAll, beforeEach, describe, expect } from "vitest";
import { ODataPath, ODataQuery, Tm1ApiException, Tm1Exception, Tm1NotFoundException, Tm1Request, Tm1Session } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { scratch, sessions, RawTm1, uniqueContext, SERVER, failure } from "./raw-tm1.js";

/** The Java client's `Tm1SessionTm1Test`: the session against a real TM1, leaving no session behind. */
describe("Tm1Session on TM1" + liveReason, () => {
  let raw: RawTm1;
  let admin: string;
  let context: string;
  const session = async (): Promise<Tm1Session> => (await sessions()).sessionContext(context).build();

  beforeAll(async () => {
    if (!Live.configured) return;
    raw = await RawTm1.connect();
    admin = await raw.login();
  });

  afterAll(async () => {
    if (raw) await raw.close(admin);
  });

  beforeEach(() => {
    context = uniqueContext();
  });

  tm1Test("reads the server and closes its session, leaving none behind", async () => {
    const s = await session();
    const tm1 = await s.execute(SERVER);
    expect(tm1.ProductVersion).toBe(RawTm1.json(await raw.request("GET", "Server?$select=ProductVersion").withCookie(admin).send())["ProductVersion"]);
    const id = await s.sessionId();
    expect(await raw.sessionCount(admin, context)).toBe(1);
    await s.close();
    expect(await raw.awaitGone(admin, id)).toBe(true);
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("shares one login between concurrent first requests", async () => {
    const s = await session();
    const results = await Promise.all(Array.from({ length: 8 }, () => s.execute(SERVER)));
    expect(results).toHaveLength(8);
    expect(await raw.sessionCount(admin, context)).toBe(1);
    await s.close();
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("leaves no session when close races the first request", async () => {
    const s = await session();
    const first = s.execute(SERVER).catch(() => undefined);
    await s.close();
    // Either outcome is fine; what matters is what's left on TM1
    await first;
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  /** The replay the client relies on, end to end: the session is killed from another session, then a
   * POST through the client logs in again and creates the dimension exactly once, and the session
   * TM1 opened for the 401 is the one logged in, so nothing is left behind. */
  tm1Test("logs in again after its session is killed and runs the POST once", async () => {
    const s = await session();
    const name = scratch();
    try {
      const first = await s.sessionId();
      expect(await raw.kill(admin, first)).toBe(204);
      await s.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: name, Hierarchies: [{ Name: name }] }));
      expect((await raw.request("GET", `Dimensions/$count?$filter=Name%20eq%20'${name}'`).withCookie(admin).send()).body).toBe("1");
      expect(await s.sessionId()).not.toBe(first);
    } finally {
      await raw.request("DELETE", `Dimensions('${name}')`).withCookie(admin).send();
      await s.close();
    }
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("labels the session with its context", async () => {
    const s = (await sessions()).sessionContext("tm1-client test").build();
    try {
      const active = await s.execute(Tm1Request.get<{ Context: string }>(ODataPath.of("ActiveSession"), ODataQuery.none().select("Context")));
      expect(active.Context).toBe("tm1-client test");
    } finally {
      await s.close();
    }
  });

  /** TM1 reads `!sandbox` on any request: a sandbox that exists works, one that doesn't fails. */
  tm1Test("runs requests in the view's sandbox", async () => {
    const s = await session();
    const sandbox = scratch();
    try {
      await s.execute(Tm1Request.post(ODataPath.of("Sandboxes"), { Name: sandbox }));
      await s.withSandbox(sandbox).execute(SERVER);
      expect(await failure(s.withSandbox(sandbox + "_missing").execute(SERVER))).toBeInstanceOf(Tm1ApiException);
    } finally {
      await s.execute(Tm1Request.delete(ODataPath.of("Sandboxes").key(sandbox))).catch((e: unknown) => {
        if (!(e instanceof Tm1Exception)) throw e;
      });
      await s.close();
    }
  });

  tm1Test("streams a collection", async () => {
    const s = await session();
    try {
      const names = await s
        .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Dimensions"), ODataQuery.none().select("Name")))
        .map((d) => d.Name)
        .toArray();
      expect(names.length).toBeGreaterThan(0);
      for (const n of names) expect(n.trim()).not.toBe("");
    } finally {
      await s.close();
    }
  });

  tm1Test("reports a missing object as not found", async () => {
    const s = await session();
    try {
      expect(await failure(s.execute(Tm1Request.get(ODataPath.of("Cubes").key("zz_no_such_cube"))))).toBeInstanceOf(Tm1NotFoundException);
    } finally {
      await s.close();
    }
  });
});
