/** Replays the Java client's recorded service calls (spec/conformance/calls): the same call, with
 * the same arguments, must send exactly the requests the Java client sent and, given the answers
 * it got, return the same result or fail the same way. */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FakeTm1 } from "../support/fake-tm1.js";
import { Repo } from "../support/repo.js";
import * as codec from "./codec.js";

const CALLS = join(Repo.root, "spec", "conformance", "calls");
const UNSUPPORTED = join(Repo.root, "tm1-typescript", "conformance", "unsupported.txt");

interface Exchange {
  request: Record<string, unknown>;
  response: { status: number; headers?: Record<string, string>; json?: unknown; text?: string; bytes?: string };
}

interface Case {
  id: string;
  service: string;
  method: string;
  params: string[];
  args: any[];
  exchanges: Exchange[];
  result?: any;
  error?: codec.EncodedError;
}

interface Fixture {
  specVersion: number;
  session: { productVersion: string };
  cases: Case[];
}

const fixtures: [string, Fixture][] = readdirSync(CALLS)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => [f, JSON.parse(readFileSync(join(CALLS, f), "utf8")) as Fixture]);

/** Cases this client can't express, each with its reason: `id  reason`. */
const unsupported = new Map<string, string>(
  existsSync(UNSUPPORTED)
    ? readFileSync(UNSUPPORTED, "utf8")
        .split("\n")
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith("#"))
        .map((l) => [l.split(/\s+/)[0]!, l.slice(l.split(/\s+/)[0]!.length).trim()])
    : [],
);

const utf8 = new TextEncoder();

describe("conformance with the Java client's recorded calls", () => {
  it("has fixtures, of a known version, and lists only real cases as unsupported", () => {
    expect(fixtures.length).toBeGreaterThan(0);
    for (const [file, f] of fixtures) expect(f.specVersion, file).toBe(codec.SPEC_VERSION);
    const ids = new Set(fixtures.flatMap(([, f]) => f.cases.map((c) => c.id)));
    expect([...unsupported.keys()].filter((id) => !ids.has(id))).toEqual([]);
  });

  for (const [, fixture] of fixtures) {
    for (const c of fixture.cases) {
      const reason = unsupported.get(c.id);
      (reason ? it.skip : it)(c.id, async () => {
        const tm1 = new FakeTm1();
        tm1.productVersion = fixture.session.productVersion;
        const answers = c.exchanges.map((x) => x.response);
        tm1.respond = () => {
          const r = answers.shift();
          if (!r) return FakeTm1.reply(599, "unscripted request");
          const body = r.json !== undefined ? utf8.encode(JSON.stringify(r.json)) : r.text !== undefined ? r.text : r.bytes !== undefined ? Uint8Array.from(Buffer.from(r.bytes, "base64")) : undefined;
          return FakeTm1.reply(r.status, body, r.headers ?? {});
        };
        const client = tm1.client() as unknown as Record<string, Record<string, (...args: unknown[]) => unknown>>;
        const service = client[c.service];
        if (!service) throw new codec.Unknown("Tm1Client has no " + c.service);
        const method = service[c.method];
        if (typeof method !== "function") throw new codec.Unknown(`${c.service} has no ${c.method}`);
        const args = c.args.map(codec.decode);

        let outcome: { result: unknown } | { error: codec.EncodedError };
        try {
          let value: any = method.apply(service, args);
          if (value && typeof value.toArray === "function") value = await value.toArray();
          else value = await value;
          outcome = { result: codec.result(value) };
        } catch (e) {
          if (e instanceof codec.Unknown) throw e;
          outcome = { error: codec.error(e) };
        }

        const sent = tm1.requests.map((r) => codec.requestOf(r.method, r.target, r.headers, r.body));
        const expected = c.exchanges.map((x) => x.request);
        for (let i = 0; i < Math.min(sent.length, expected.length); i++) {
          expect(codec.comparable(sent[i]!), `request ${i + 1}`).toBe(codec.comparable(expected[i]!));
        }
        expect(sent.length, "requests sent").toBe(expected.length);

        if (c.error) {
          expect("error" in outcome ? outcome.error : outcome, "the error").toSatisfy(
            (got: any) => got && typeof got.category === "string" && codec.sameError(c.error!, got),
          );
        } else {
          expect("result" in outcome, "a result, not " + JSON.stringify(outcome)).toBe(true);
          expect(codec.normal((outcome as { result: any }).result, true)).toEqual(codec.normal(codec.expectedResult(c.result), true));
        }
      });
    }
  }
});
