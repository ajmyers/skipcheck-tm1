import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { Live, liveReason, tm1Test } from "./live.js";
import { basic, type RawBytes, type RawResponse, RawRequest, RawTm1 } from "./raw-tm1.js";

/** The Java client's `Tm1ImpersonationBehaviorTest`: how TM1 itself treats `TM1-Impersonate`, which
 * the session and pool rely on. */
describe("TM1's impersonation behaviour" + liveReason, () => {
  let tm1: RawTm1;
  let admin: string;
  let alice: string;
  let bob: string;
  const impersonate = (user: string, password: string, target: string): Promise<RawResponse> =>
    tm1.request("GET", "ActiveUser?$select=Name").withHeader("Authorization", basic(user, password)).withHeader("TM1-Impersonate", target).send();
  const createUser = async (name: string, group: string): Promise<void> => {
    await tm1
      .request("POST", "Users")
      .withCookie(admin)
      .withBody(JSON.stringify({ Name: name, Password: "p", "Groups@odata.bind": [`Groups('${group}')`] }))
      .send();
  };
  const nameOf = async (request: RawRequest): Promise<string> => RawTm1.json(await request.send())["Name"] as string;
  const closeIfSet = async (response: RawResponse): Promise<void> => {
    const cookie = RawTm1.cookieOf(response);
    if (cookie) await tm1.close(cookie);
  };

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await RawTm1.connect();
    admin = await tm1.login();
    const run = randomUUID().replace(/-/g, "").slice(0, 6);
    alice = "zz_alice_" + run;
    bob = "zz_bob_" + run;
    await tm1.createUser(admin, alice, "a");
    await tm1.createUser(admin, bob, "b");
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.deleteUser(admin, alice);
    await tm1.deleteUser(admin, bob);
    await tm1.close(admin);
  });

  /** Impersonation is decided at login, and the header on later requests is ignored. So it's a
   * session setting, and one identity means one session. */
  tm1Test("decides the identity at login and ignores the header afterwards", async () => {
    const login = await tm1.request("GET", "ActiveUser?$select=Name").withBasic().withHeader("TM1-Impersonate", alice).send();
    expect(login.status).toBe(200);
    expect(RawTm1.json(login)["Name"]).toBe(alice);
    const session = RawTm1.cookieOf(login)!;
    try {
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(session))).toBe(alice);
      // A different user on a later request
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(session).withHeader("TM1-Impersonate", bob))).toBe(alice);
      const plain = await tm1.login();
      expect((await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(plain).withHeader("TM1-Impersonate", alice))).toLowerCase()).toBe("admin");
      await tm1.close(plain);
    } finally {
      await tm1.close(session);
    }
  });

  /** It also works when logging in the session a 401 opened, as the client's re-login does. */
  tm1Test("impersonates on a login that reuses the 401's session", async () => {
    const seed = RawTm1.cookieOf(await tm1.request("GET", "ActiveUser?$select=Name").send())!;
    try {
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(seed).withBasic().withHeader("TM1-Impersonate", alice))).toBe(alice);
    } finally {
      await tm1.close(seed);
    }
  });

  /** Impersonating a user that doesn't exist is a 400, and a non-administrator trying is a 403, both
   * with plain-text bodies, which the client reads as the error message. */
  tm1Test("refuses unknown users and non-administrators with plain-text errors", async () => {
    const unknown = await tm1.request("GET", "ActiveUser?$select=Name").withBasic().withHeader("TM1-Impersonate", "zz_nobody").send();
    expect(unknown.status).toBe(400);
    expect(unknown.body.startsWith("Error: Bad Request")).toBe(true);
    expect(unknown.body).toContain("The user specified in the impersonation request does not exist.");
    const left = RawTm1.cookieOf(unknown)!;
    expect((await tm1.request("GET", "ActiveUser?$select=Name").withCookie(left).send()).status, "the session a refused impersonation leaves").toBe(401);
    const nonAdmin = await impersonate(alice, "a", bob);
    expect(nonAdmin.status).toBe(403);
    expect(nonAdmin.body.startsWith("Error: Forbidden")).toBe(true);
    expect(nonAdmin.body).toContain("You need to be a (data-)administrator to be able to impersonate another user.");
  });

  /** Nobody can impersonate an administrator, not even another administrator. */
  tm1Test("refuses to impersonate any administrator", async () => {
    const otherAdmin = alice + "_adm";
    const dataAdmin = alice + "_data";
    await createUser(otherAdmin, "ADMIN");
    await createUser(dataAdmin, "DataAdmin");
    try {
      for (const [user, password, target] of [
        [tm1.user, tm1.password, otherAdmin],
        [tm1.user, tm1.password, "Admin"],
        [dataAdmin, "p", otherAdmin],
        [dataAdmin, "p", "Admin"],
      ] as const) {
        const refused = await impersonate(user, password, target);
        expect(refused.status, `${user} impersonating ${target}`).toBe(403);
        expect(refused.body).toContain("You have insufficient rights to impersonate the user specified!");
      }
    } finally {
      await tm1.deleteUser(admin, otherAdmin);
      await tm1.deleteUser(admin, dataAdmin);
    }
  });

  /** Data administrators can impersonate ordinary users; security and operations administrators
   * can't, with a different 403 message. */
  tm1Test("lets data administrators impersonate, but not other administrator roles", async () => {
    const dataAdmin = alice + "_data";
    const securityAdmin = alice + "_sec";
    const operationsAdmin = alice + "_ops";
    await createUser(dataAdmin, "DataAdmin");
    await createUser(securityAdmin, "SecurityAdmin");
    await createUser(operationsAdmin, "OperationsAdmin");
    try {
      const allowed = await impersonate(dataAdmin, "p", bob);
      expect(allowed.status).toBe(200);
      await closeIfSet(allowed);
      for (const role of [securityAdmin, operationsAdmin]) {
        const refused = await impersonate(role, "p", bob);
        expect(refused.status, role).toBe(403);
        expect(refused.body).toContain("You need to be a (data-)administrator to be able to impersonate another user.");
      }
    } finally {
      await tm1.deleteUser(admin, dataAdmin);
      await tm1.deleteUser(admin, securityAdmin);
      await tm1.deleteUser(admin, operationsAdmin);
    }
  });

  /** Impersonating a disabled user is a 401 with an empty body: exactly what wrong credentials look
   * like. The client can't tell the two apart, so it reports both. */
  tm1Test("refuses a disabled user like wrong credentials", async () => {
    await tm1.request("PATCH", `Users('${bob}')`).withCookie(admin).withBody('{"Enabled":false}').send();
    try {
      const refused = await impersonate(tm1.user, tm1.password, bob);
      expect(refused.status).toBe(401);
      expect(refused.body).toBe("");
    } finally {
      await tm1.request("PATCH", `Users('${bob}')`).withCookie(admin).withBody('{"Enabled":true}').send();
    }
  });

  /** An empty `TM1-Impersonate` means no impersonation. So the client must never send a blank name. */
  tm1Test("treats an empty header as no impersonation", async () => {
    const login = await impersonate(tm1.user, tm1.password, "");
    try {
      expect(login.status).toBe(200);
      expect((RawTm1.json(login)["Name"] as string).toLowerCase()).toBe("admin");
    } finally {
      await closeIfSet(login);
    }
  });

  /** User names match ignoring case and spaces, so the pool compares identities the same way. */
  tm1Test("matches user names ignoring case and spaces", async () => {
    for (const written of [alice.toUpperCase(), alice.replace(/_/g, "_ ")]) {
      const login = await impersonate(tm1.user, tm1.password, written);
      try {
        expect(login.status, written).toBe(200);
        expect(RawTm1.json(login)["Name"]).toBe(alice);
      } finally {
        await closeIfSet(login);
      }
    }
  });

  /** A user whose name has characters outside ASCII is impersonated when the header carries the name
   * as UTF-8, and only then: as ISO 8859-1 bytes or percent-encoded, TM1 reports the user unknown.
   * Sent over a bare socket, so the bytes are exactly these. The client sends UTF-8 (`headerText`). */
  tm1Test("impersonates users with non-ASCII names only as UTF-8", async () => {
    const accented = alice + "_ë";
    const impersonateBytes = (target: Uint8Array): Promise<RawBytes> =>
      tm1.getWithHeaderBytes("ActiveUser?$select=Name", basic(tm1.user, tm1.password), "TM1-Impersonate", target);
    await tm1.createUser(admin, accented, "p");
    try {
      const impersonated = await impersonateBytes(Buffer.from(accented, "utf8"));
      if (impersonated.cookie) await tm1.close(impersonated.cookie);
      expect(impersonated.status).toBe(200);
      expect(impersonated.text).toContain(`"Name":"${accented}"`);

      const percentEncoded = Buffer.from(encodeURIComponent(accented), "latin1");
      for (const written of [Buffer.from(accented, "latin1"), percentEncoded]) {
        const refused = await impersonateBytes(written);
        expect(refused.status).toBe(400);
        expect(refused.text).toContain("The user specified in the impersonation request does not exist.");
      }
    } finally {
      await tm1.deleteUser(admin, accented);
    }
  });
});
