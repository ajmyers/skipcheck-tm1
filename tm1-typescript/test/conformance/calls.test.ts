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

/** An exchange, or a group of them whose requests may come in any order. */
type Entry = Exchange | { unordered: Exchange[] };

interface Case {
  id: string;
  service: string;
  method: string;
  params: string[];
  args: any[];
  take?: number;
  exchanges: Entry[];
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
        // Each entry a list of exchanges: one, or an unordered group's, answered by the equal request
        const entries = c.exchanges.map((x) => ("unordered" in x ? [...x.unordered] : [x]));
        const answered: [sent: Record<string, unknown>, expected: Record<string, unknown>][] = [];
        tm1.respond = (request) => {
          while (entries.length > 0 && entries[0]!.length === 0) entries.shift();
          const group = entries[0];
          if (!group) return FakeTm1.reply(599, "unscripted request");
          const sent = codec.requestOf(request.method, request.target, request.headers, request.body);
          const exchange = group.find((x) => codec.comparable(x.request) === codec.comparable(sent)) ?? group[0]!;
          group.splice(group.indexOf(exchange), 1);
          answered.push([sent, exchange.request]);
          const r = exchange.response;
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
          if (value && c.take !== undefined && typeof value[Symbol.asyncIterator] === "function") {
            // A stream that doesn't end by itself: its first items, then left
            const items: unknown[] = [];
            if (c.take > 0) {
              for await (const item of value as AsyncIterable<unknown>) {
                items.push(item);
                if (items.length >= c.take) break;
              }
            }
            value = items;
          } else if (value && typeof value.toArray === "function") value = await value.toArray();
          else value = await value;
          // A stream of bytes, recorded as its bytes however they're split
          if (c.result && typeof c.result === "object" && "$bytes" in c.result && Array.isArray(value)) value = Buffer.concat(value);
          outcome = { result: codec.result(value) };
        } catch (e) {
          if (e instanceof codec.Unknown) throw e;
          outcome = { error: codec.error(e) };
        }

        // Each request against the recorded one it was answered with
        answered.forEach(([sent, expected], i) => expect(codec.comparable(sent), `request ${i + 1}`).toBe(codec.comparable(expected)));
        const recorded = c.exchanges.reduce((n, x) => n + ("unordered" in x ? x.unordered.length : 1), 0);
        expect(tm1.requests.length, "requests sent").toBe(recorded);

        if (c.error) {
          expect("error" in outcome ? outcome.error : outcome, "the error").toSatisfy(
            (got: any) => got && typeof got.category === "string" && codec.sameError(c.error!, got),
          );
        } else {
          expect("result" in outcome, "a result, not " + JSON.stringify(outcome)).toBe(true);
          expect(codec.normal(codec.javaResult(codec.fallbacksAsNull((outcome as { result: any }).result, c.result), c.result), true)).toEqual(codec.normal(codec.expectedResult(c.result), true));
        }
      });
    }
  }
});
