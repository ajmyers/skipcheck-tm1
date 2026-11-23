import { Agent as HttpAgent, request as httpRequest, type IncomingMessage } from "node:http";
import { Agent as HttpsAgent, request as httpsRequest } from "node:https";
import type { Socket } from "node:net";
import { beforeAll, describe, expect } from "vitest";
import { Live, liveReason, tm1Test } from "./live.js";
import { scratch, basic, RawTm1 } from "./raw-tm1.js";

const dimensionJson = (name: string): string => JSON.stringify({ Name: name, Hierarchies: [{ Name: name }] });

/** The Java client's `Tm1SessionBehaviorTest`: what TM1 itself does that the session relies on,
 * checked over plain HTTP. */
describe("TM1's session behaviour" + liveReason, () => {
  let tm1: RawTm1;

  beforeAll(async () => {
    if (Live.configured) tm1 = await RawTm1.connect();
  });

  /** The client replays a request once after a 401 on an established session, POSTs included.
   * That's only safe if TM1 refuses the request before running it. */
  tm1Test("refuses a request on a killed session without running it", async () => {
    const admin = await tm1.login();
    const victim = await tm1.login();
    const dimension = scratch();
    try {
      expect(await tm1.kill(admin, await tm1.sessionId(victim))).toBe(204);
      const refused = await tm1.request("POST", "Dimensions").withCookie(victim).withBody(dimensionJson(dimension)).send();
      expect(refused.status).toBe(401);
      expect((await tm1.request("GET", `Dimensions('${dimension}')`).withCookie(admin).send()).status, "the refused POST must not have created it").toBe(404);
      const relogged = RawTm1.cookieOf(refused)!;
      expect((await tm1.request("GET", "ActiveSession?$select=ID").withCookie(relogged).withBasic().send()).status).toBe(200);
      expect((await tm1.request("POST", "Dimensions").withCookie(relogged).withBody(dimensionJson(dimension)).send()).status).toBe(201);
      expect((await tm1.request("GET", `Dimensions/$count?$filter=Name%20eq%20'${dimension}'`).withCookie(admin).send()).body).toBe("1");
      await tm1.close(relogged);
    } finally {
      await tm1.request("DELETE", `Dimensions('${dimension}')`).withCookie(admin).send();
      await tm1.close(admin);
    }
  });

  /** Every 401 opens a TM1 session with no user, and sets its cookie. Sending that cookie with the
   * credentials logs that same session in, instead of opening another. */
  tm1Test("opens a session on every 401, and logs that session in when its cookie is sent with credentials", async () => {
    const admin = await tm1.login();
    try {
      const before = await tm1.allSessionCount(admin);
      const anonymous = await tm1.request("GET", "ActiveSession?$select=ID").send();
      expect(anonymous.status).toBe(401);
      expect(anonymous.headers.get("WWW-Authenticate")).toBe('Basic realm="TM1"');
      const cookie = RawTm1.cookieOf(anonymous)!;
      expect(await tm1.allSessionCount(admin), "sessions after one 401").toBe(before + 1);
      const login = await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).withBasic().send();
      expect(login.status).toBe(200);
      expect(RawTm1.cookieOf(login), "no new cookie: the same session was logged in").toBeUndefined();
      expect(await tm1.allSessionCount(admin)).toBe(before + 1);
      await tm1.close(cookie);
      expect(await tm1.allSessionCount(admin)).toBe(before);
    } finally {
      await tm1.close(admin);
    }
  });

  /** `Server` can be read with no credentials, but even that opens a session. So the client never
   * reads anything, the version included, outside its own session. */
  tm1Test("opens a session for an anonymous read of Server", async () => {
    const admin = await tm1.login();
    try {
      const before = await tm1.allSessionCount(admin);
      const server = await tm1.request("GET", "Server?$select=Name").send();
      expect(server.status).toBe(200);
      expect(await tm1.allSessionCount(admin)).toBe(before + 1);
      const cookie = RawTm1.cookieOf(server)!;
      await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).withBasic().send();
      await tm1.close(cookie);
    } finally {
      await tm1.close(admin);
    }
  });

  /** A closed session is refused at once, but TM1 lists it in `Sessions` for several seconds more, so
   * a check that no session is left behind has to wait for the count to settle. */
  tm1Test(
    "refuses a closed session at once but lists it for seconds more",
    async () => {
      const admin = await tm1.login();
      const cookie = await tm1.login();
      const id = await tm1.sessionId(cookie);
      try {
        const reads = await Promise.all(Array.from({ length: 8 }, () => tm1.request("GET", "Server?$select=Name").withCookie(cookie).send()));
        for (const r of reads) expect(r.status).toBe(200);
        expect((await tm1.request("POST", "ActiveSession/tm1.Close").withCookie(cookie).withBody("{}").send()).status).toBe(204);
        expect((await tm1.request("GET", `Sessions(${id})`).withCookie(admin).send()).status, "still listed just after close").toBe(200);
        expect(await tm1.awaitGone(admin, id), "gone within 30 s").toBe(true);
      } finally {
        await tm1.close(admin);
      }
    },
    60_000,
  );

  /** Closing a session expires its cookie, and later requests with that cookie get a 401. */
  tm1Test("expires the cookie on close and refuses it afterwards", async () => {
    const cookie = await tm1.login();
    const close = await tm1.request("POST", "ActiveSession/tm1.Close").withCookie(cookie).withBody("{}").send();
    expect(close.status).toBe(204);
    expect(close.cookies.some((c) => c.startsWith("TM1SessionId=") && c.includes("Max-Age=0"))).toBe(true);
    const after = await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).send();
    expect(after.status).toBe(401);
    const leftover = RawTm1.cookieOf(after)!;
    await tm1.request("GET", "ActiveSession?$select=ID").withCookie(leftover).withBasic().send();
    await tm1.close(leftover);
  });

  /** Requests on one session run in parallel: three one-second process runs finish in about one
   * second. So one shared session doesn't serialize the client's traffic. */
  tm1Test("runs concurrent requests on one session in parallel", async () => {
    const cookie = await tm1.login();
    const process = scratch();
    try {
      expect(
        (
          await tm1
            .request("POST", "Processes")
            .withCookie(cookie)
            .withBody(JSON.stringify({ Name: process, PrologProcedure: "Sleep(1000);" }))
            .send()
        ).status,
      ).toBe(201);
      const start = Date.now();
      const runs = await Promise.all(
        Array.from({ length: 3 }, () => tm1.request("POST", `Processes('${process}')/tm1.ExecuteWithReturn`).withCookie(cookie).withBody("{}").send()),
      );
      for (const r of runs) expect(RawTm1.json(r)["ProcessExecuteStatusCode"]).toBe("CompletedSuccessfully");
      expect(Date.now() - start, "three 1 s runs on one session").toBeLessThan(2_500);
    } finally {
      await tm1.request("DELETE", `Processes('${process}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** `TM1-SessionContext` labels the whole session, and the last request to send it wins. So it can't
   * be a per-request setting on views that share one session. */
  tm1Test("labels the session with the last session context sent", async () => {
    const login = await tm1.request("GET", "ActiveSession?$select=Context").withBasic().withHeader("TM1-SessionContext", "first").send();
    const cookie = RawTm1.cookieOf(login)!;
    try {
      expect(RawTm1.json(login)["Context"]).toBe("first");
      await tm1.request("GET", "Server?$select=Name").withCookie(cookie).withHeader("TM1-SessionContext", "second").send();
      expect(RawTm1.json(await tm1.request("GET", "ActiveSession?$select=Context").withCookie(cookie).send())["Context"]).toBe("second");
    } finally {
      await tm1.close(cookie);
    }
  });

  /** A create answers 201 with a relative `Location` and an `ETag`; with `Prefer: return=minimal`,
   * 204 with `Location` and `OData-EntityId`. */
  tm1Test("answers a create with its location", async () => {
    const cookie = await tm1.login();
    const full = scratch();
    const minimal = scratch();
    try {
      const created = await tm1.request("POST", "Dimensions").withCookie(cookie).withBody(dimensionJson(full)).send();
      expect(created.status).toBe(201);
      expect(created.headers.get("Location")).toBe(`Dimensions('${full}')`);
      expect(created.headers.get("ETag")).not.toBeNull();
      const bare = await tm1.request("POST", "Dimensions").withCookie(cookie).withHeader("Prefer", "return=minimal").withBody(dimensionJson(minimal)).send();
      expect(bare.status).toBe(204);
      expect(bare.headers.get("Location")).toBe(`Dimensions('${minimal}')`);
      expect(bare.headers.get("OData-EntityId")).toBe(`Dimensions('${minimal}')`);
    } finally {
      await tm1.request("DELETE", `Dimensions('${full}')`).withCookie(cookie).send();
      await tm1.request("DELETE", `Dimensions('${minimal}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** `odata.metadata=none` drops `@odata.etag` but still sends `@odata.context`. */
  tm1Test("drops the ETag but not the context with metadata none", async () => {
    const cookie = await tm1.login();
    const name = scratch();
    try {
      await tm1.request("POST", "Dimensions").withCookie(cookie).withBody(dimensionJson(name)).send();
      const minimal = RawTm1.json(await tm1.request("GET", `Dimensions('${name}')`).withCookie(cookie).send());
      const none = RawTm1.json(
        await tm1.request("GET", `Dimensions('${name}')`).withCookie(cookie).withHeader("Accept", "application/json;odata.metadata=none").send(),
      );
      expect("@odata.etag" in minimal).toBe(true);
      expect("@odata.etag" in none).toBe(false);
      expect("@odata.context" in none).toBe(true);
    } finally {
      await tm1.request("DELETE", `Dimensions('${name}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** TM1 closes a kept-alive connection idle for about 90 seconds, and a request sent on it as it
   * closes gets no response at all. Node's `fetch` (undici) keeps an idle connection for 4 seconds
   * unless the server asks for longer, far below that, so `fetchTransport` never reuses one TM1 is
   * closing. Takes up to 150 s. */
  tm1Test(
    "closes a connection idle for ninety seconds",
    async () => {
      const t = await Live.target();
      const url = new URL(t.url + "Server?$select=Name");
      const secure = url.protocol === "https:";
      const agent = secure
        ? new HttpsAgent({ keepAlive: true, maxSockets: 1, rejectUnauthorized: process.env["TM1_VERIFY"] !== "false" })
        : new HttpAgent({ keepAlive: true, maxSockets: 1 });
      try {
        const socket = await new Promise<Socket>((resolve, reject) => {
          const send = secure ? httpsRequest : httpRequest;
          const req = send(url, { agent, headers: { Authorization: basic(t.user, t.password) } }, (response: IncomingMessage) => {
            expect(response.statusCode).toBe(200);
            response.resume();
            response.on("end", () => resolve(req.socket!));
          });
          req.on("error", reject);
          req.end();
        });
        const used = Date.now();
        // Closed by TM1
        await new Promise<void>((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error("TM1 didn't close the idle connection within 150 s")), 150_000);
          socket.once("close", () => {
            clearTimeout(timer);
            resolve();
          });
        });
        const idle = (Date.now() - used) / 1000;
        expect(idle, "seconds idle before TM1 closed it").toBeGreaterThanOrEqual(80);
        expect(idle).toBeLessThanOrEqual(100);
      } finally {
        agent.destroy();
      }
    },
    200_000,
  );
});
