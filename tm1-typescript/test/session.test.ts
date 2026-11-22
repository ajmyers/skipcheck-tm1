import { gzipSync } from "node:zlib";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  Cube,
  ODataPath,
  ODataQuery,
  Tm1AuthenticationException,
  Tm1Capabilities,
  Tm1Client,
  Tm1NotFoundException,
  Tm1Request,
  Tm1Session,
  Tm1UnsupportedOperationException,
  type Tm1HttpResponse,
  type Tm1Transport,
} from "../src/index.js";
import { FakeTm1 } from "./support/fake-tm1.js";
import { Repo } from "./support/repo.js";

/** The session against a fake TM1: login and cookie, re-login, views, batches, async operations and
 * capability checks, as the Java, Python and .NET clients' session tests. */
describe("Tm1Session", () => {
  it("logs in once, then sends only the cookie", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection();
    const client = tm1.client();
    await client.cubes.list().toArray();
    await client.cubes.list().toArray();
    expect(tm1.logins).toBe(1);
    for (const r of tm1.requests) {
      expect(r.headers["authorization"]).toBeUndefined();
      expect(r.headers["cookie"]).toBe("TM1SessionId=s1");
      expect(r.headers["odata-version"]).toBe("4.0");
    }
  });

  it("logs in again when the session expired", async () => {
    const tm1 = new FakeTm1();
    const answers: unknown[] = [FakeTm1.reply(401), FakeTm1.collection({ Name: "c" })];
    tm1.respond = () => answers.shift();
    const cubes = await tm1.client().cubes.list().toArray();
    expect(cubes.map((c) => c.Name)).toEqual(["c"]);
    expect(tm1.logins).toBe(2);
    expect(tm1.last.headers["cookie"]).toBe("TM1SessionId=s2");
  });

  it("follows next links across pages", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = (r) => (r.target === "Cubes?$select=Name" ? { value: [{ Name: "a" }], "@odata.nextLink": "Cubes?$select=Name&$skiptoken=2" } : FakeTm1.collection({ Name: "b" }));
    const names = await tm1
      .client()
      .cubes.list(ODataQuery.none().select("Name"))
      .map((c) => c.Name)
      .toArray();
    expect(names).toEqual(["a", "b"]);
    expect(tm1.last.target).toBe("Cubes?$select=Name&$skiptoken=2");
  });

  it("adds TM1's options for sandbox and change set views", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    await client.withSandbox("What if").withChangeSet("3").cubes.get("c");
    expect(tm1.last.target).toBe("Cubes('c')?!sandbox=What%20if&!ChangeSet=3");
    await client.cubes.get("c");
    expect(tm1.last.target).toBe("Cubes('c')");
    expect(tm1.logins).toBe(1);
  });

  it("sends a batch's calls together, each with its own result", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = (r) => {
      expect(r.target).toBe("$batch");
      expect(r.json.requests.map((q: { url: string }) => q.url)).toEqual(["Cubes('a')?!sandbox=s", "Cubes('b')?!sandbox=s"]);
      return {
        responses: [
          { id: "1", status: 200, body: { Name: "a" } },
          { id: "2", status: 404, body: { error: { code: "1", message: "'b' can not be found" } } },
        ],
      };
    };
    const batch = tm1.client().withSandbox("s").batch();
    const a = batch.client.cubes.get("a");
    const b = batch.client.cubes.get("b");
    expect(batch.size()).toBe(2);
    let settled = false;
    void a.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);
    await batch.run();
    expect((await a).Name).toBe("a");
    await expect(b).rejects.toBeInstanceOf(Tm1NotFoundException);
    expect(tm1.requests).toHaveLength(1);
    await expect(batch.client.cubes.get("c")).rejects.toThrow(/already run/);
  });

  it("puts a batched collection in the batch at once, read once it has run", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ responses: [{ id: "1", status: 200, body: { value: [{ Name: "Region" }] } }] });
    const batch = tm1.client().batch();
    const dimensions = batch.client.cubes.dimensions("Sales");
    expect(batch.size()).toBe(1);
    await batch.run();
    expect((await dimensions.toArray()).map((d) => d.Name)).toEqual(["Region"]);
    expect(tm1.last.json.requests[0].url).toBe("Cubes('Sales')/Dimensions");
  });

  it("polls an async view's monitor and reads the result", async () => {
    const tm1 = new FakeTm1();
    let polls = 0;
    tm1.respond = (r) => {
      if (r.method === "POST") {
        expect(r.headers["prefer"]).toBe("respond-async");
        return FakeTm1.reply(202, undefined, { location: "../_async('7')" });
      }
      expect(r.target).toBe("_async('7')");
      return ++polls === 1 ? FakeTm1.reply(202) : FakeTm1.reply(200, message(200, '{"Name":"c"}'));
    };
    // The Location is relative to the request's URL
    const path = ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn");
    const result = await tm1.client().withAsync(1).session.execute(Tm1Request.post(path, null, Cube));
    expect(result.Name).toBe("c");
    expect(polls).toBe(2);
  });

  it("deletes an async operation's monitor when it's cancelled", async () => {
    const tm1 = new FakeTm1();
    const cancel = new AbortController();
    tm1.respond = (r) => {
      if (r.method === "POST") return FakeTm1.reply(202, undefined, { location: "../_async('9')" });
      if (r.method === "GET") {
        cancel.abort();
        return FakeTm1.reply(202);
      }
      return null;
    };
    await expect(tm1.client().withAsync(1).cubes.load("c", { signal: cancel.signal })).rejects.toThrow();
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "_async('9')"]);
  });

  it("refuses an operation the server doesn't have", async () => {
    const tm1 = new FakeTm1();
    // Capabilities are cached per server and version for each builder: this is another version anyway
    tm1.productVersion = "11.8.02000.1";
    tm1.metadata = new TextEncoder().encode(`<?xml version="1.0" encoding="utf-8"?>
<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>
  <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
    <Annotation Term="Core.SchemaVersion"><String>11.8.20</String></Annotation>
    <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String"/></EntityType>
    <EntityContainer Name="API"><EntitySet Name="Cubes" EntityType="tm1.Cube"/></EntityContainer>
  </Schema></edmx:DataServices></edmx:Edmx>`);
    const e = await tm1
      .client()
      .cubes.reserve("c")
      .then(
        () => undefined,
        (x: unknown) => x,
      );
    expect(e).toBeInstanceOf(Tm1UnsupportedOperationException);
    expect((e as Error).message).toBe(
      "Cube.Reserve isn't defined by the TM1 server at http://tm1:8010/api/v1/: it was added in schema 11.8.34, and the server reports schema 11.8.20, product version 11.8.02000.1",
    );
    expect(tm1.requests).toHaveLength(0);
  });

  it("reads the snapshot into the spec's inventory", () => {
    const caps = Tm1Capabilities.parse(readFileSync(Repo.snapshot, "utf8"));
    expect(caps.schemaVersion).toBe("11.8.35");
    const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as { endpoints: { id: string }[] };
    expect([...caps.endpoints].sort()).toEqual(spec.endpoints.map((e) => e.id).sort());
  });

  it("remembers a refused login", async () => {
    let attempts = 0;
    const refusing: Tm1Transport = {
      send(): Promise<Tm1HttpResponse> {
        attempts++;
        return Promise.resolve({ status: 401, headers: {}, body: new Uint8Array() });
      },
    };
    const client = Tm1Client.of(Tm1Session.builder().baseUrl(FakeTm1.BASE).basicAuth("admin", "wrong").transport(refusing).build());
    await expect(client.cubes.get("c")).rejects.toBeInstanceOf(Tm1AuthenticationException);
    const again = await client.cubes.get("c").catch((e: unknown) => e);
    expect((again as Tm1AuthenticationException).kind).toBe("CredentialRejected");
    expect(attempts).toBe(1);
  });

  it("closes the session on TM1 once, and refuses requests after", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    await client.connect();
    await client.close();
    await client.close();
    expect(tm1.requests.map((r) => `${r.method} ${r.target}`)).toEqual(["POST ActiveSession/tm1.Close"]);
    await expect(client.cubes.get("c")).rejects.toThrow(/closed/);
  });

  it("disposes with await using", async () => {
    const tm1 = new FakeTm1();
    {
      await using client = tm1.client();
      await client.connect();
    }
    expect(tm1.last.target).toBe("ActiveSession/tm1.Close");
  });

  it("leaves cookies to a transport that keeps them, as a browser's", async () => {
    const tm1 = new FakeTm1();
    const browser: Tm1Transport = { keepsCookies: true, send: (r) => tm1.send(r) };
    tm1.respond = () => FakeTm1.collection();
    const client = Tm1Client.of(Tm1Session.builder().baseUrl(FakeTm1.BASE).basicAuth("admin", "apple").transport(browser).build());
    await client.cubes.list().toArray();
    expect(tm1.last.headers["cookie"]).toBeUndefined();
  });
});

function message(status: number, json: string): Uint8Array {
  const head = new TextEncoder().encode(`HTTP/1.1 ${status} OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\n\r\n`);
  const body = gzipSync(json);
  const out = new Uint8Array(head.length + body.length);
  out.set(head);
  out.set(body, head.length);
  return out;
}
