// SPDX-License-Identifier: Apache-2.0

/** Runs a live conformance scenario (spec/conformance/live/<name>.json), checks each step's
 * expectation, and writes what the client sent to build/conformance-live/typescript/<name>.jsonl
 * for scripts/compare-live-traffic.py. */
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Tm1HttpRequest, Tm1HttpResponse, Tm1Transport } from "../../src/index.js";
import { Repo } from "../support/repo.js";
import * as codec from "./codec.js";

export const SCENARIOS = join(Repo.root, "spec", "conformance", "live");
export let TRAFFIC = join(Repo.root, "build", "conformance-live");

export function trafficTo(dir: string): void {
  TRAFFIC = dir;
}

/** The session's own requests, which differ by when each client logs in and caches capabilities. */
const SESSION = ["ActiveSession", "$metadata", "Server?$select=ProductVersion"];

export interface Step {
  service: string;
  method: string;
  params: string[];
  args: any[];
  expect?: Record<string, any>;
}

export interface Scenario {
  specVersion: number;
  name: string;
  names: string[];
  steps: Step[];
  finally?: Step[];
}

export function scenarios(): Scenario[] {
  return readdirSync(SCENARIOS)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => JSON.parse(readFileSync(join(SCENARIOS, f), "utf8")) as Scenario);
}

/** A transport that records what it sends, labelled with the step. */
export class Traffic implements Tm1Transport {
  step = "";
  readonly exchanges: Record<string, unknown>[] = [];

  constructor(
    private readonly inner: Tm1Transport,
    private readonly root: string,
  ) {}

  get keepsCookies(): boolean | undefined {
    return this.inner.keepsCookies;
  }

  async send(request: Tm1HttpRequest): Promise<Tm1HttpResponse> {
    const response = await this.inner.send(request);
    const root = this.root.endsWith("/") ? this.root : this.root + "/";
    const target = request.url.startsWith(root) ? request.url.slice(root.length) : request.url;
    if (!SESSION.some((s) => target.startsWith(s))) {
      const headers: Record<string, string> = {};
      for (const [k, v] of Object.entries(request.headers)) headers[k.toLowerCase()] = v;
      this.exchanges.push({
        step: this.step,
        request: codec.requestOf(request.method, target, headers, request.body ?? new Uint8Array()),
        status: response.status,
      });
    }
    return response;
  }

  write(scenario: string, names: Record<string, string>): string {
    const dir = join(TRAFFIC, "typescript");
    mkdirSync(dir, { recursive: true });
    const file = join(dir, `${scenario}.jsonl`);
    const lines = [{ scenario, language: "typescript", names }, ...this.exchanges];
    writeFileSync(file, lines.map((l) => JSON.stringify(l)).join("\n") + "\n");
    return file;
  }
}

/** A fresh name for each placeholder: letters, digits and _ only, so no client encodes them differently. */
export function namesFor(scenario: Scenario): Record<string, string> {
  const run = Math.random().toString(16).slice(2, 10).padEnd(8, "0");
  return Object.fromEntries(scenario.names.map((n) => [n, `tm1conf_${run}_${n}`]));
}

export function substitute(value: any, names: Record<string, string>): any {
  if (typeof value === "string") return Object.entries(names).reduce((s, [p, a]) => s.split("${" + p + "}").join(a), value);
  if (Array.isArray(value)) return value.map((v) => substitute(v, names));
  if (value !== null && typeof value === "object")
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [substitute(k, names), substitute(v, names)]));
  return value;
}

type Outcome = { result: any } | { error: codec.EncodedError };

/** Why the outcome doesn't meet the expectation, or undefined if it does. */
export function check(expect: Record<string, any>, outcome: Outcome): string | undefined {
  if (expect.error) {
    const got = "error" in outcome ? (outcome.error as unknown as Record<string, unknown>) : undefined;
    if (!got || Object.entries(expect.error).some(([k, v]) => got[k] !== v))
      return `expected the error ${JSON.stringify(expect.error)}, got ${JSON.stringify(outcome)}`;
    return undefined;
  }
  if ("error" in outcome) return "failed with " + JSON.stringify(outcome.error);
  const result = outcome.result;
  if (expect.none && result !== null) return "expected nothing, got " + JSON.stringify(result);
  if (expect.count !== undefined && (!Array.isArray(result) || result.length !== expect.count))
    return `expected ${expect.count} items, got ${JSON.stringify(result)}`;
  if (expect.fields && (result === null || typeof result !== "object" || Object.entries(expect.fields).some(([k, v]) => result[k] !== v))) {
    return `expected the fields ${JSON.stringify(expect.fields)}, got ${JSON.stringify(result)}`;
  }
  if (expect.items) {
    for (const [field, values] of Object.entries(expect.items as Record<string, unknown[]>)) {
      const got = (Array.isArray(result) ? result : []).map((i: any) => i?.[field] ?? null);
      if (JSON.stringify(got) !== JSON.stringify(values)) return `expected the items' ${field} ${JSON.stringify(values)}, got ${JSON.stringify(got)}`;
    }
  }
  return undefined;
}

async function outcome(client: any, step: Step, names: Record<string, string>): Promise<Outcome> {
  const service = client[step.service];
  if (!service || typeof service[step.method] !== "function") throw new codec.Unknown(`no ${step.service}.${step.method}`);
  const args = step.args.map((a) => codec.decode(substitute(a, names)));
  try {
    let value: any = service[step.method](...args);
    value = value && typeof value.toArray === "function" ? await value.toArray() : await value;
    // A stream of bytes, such as a document's content, as its bytes however they're split
    if (Array.isArray(value) && value.length > 0 && value.every((v) => v instanceof Uint8Array)) value = Buffer.concat(value);
    return { result: codec.result(value) };
  } catch (e) {
    if (e instanceof codec.Unknown) throw e;
    return { error: codec.error(e) };
  }
}

/** Runs the scenario; the steps whose expectation failed. Its `finally` steps always run. */
export async function run(client: unknown, traffic: Traffic, scenario: Scenario, names: Record<string, string>): Promise<string[]> {
  const failures: string[] = [];
  try {
    for (const [i, step] of scenario.steps.entries()) {
      traffic.step = `steps[${i}]`;
      const why = check(substitute(step.expect ?? {}, names), await outcome(client, step, names));
      if (why) {
        failures.push(`step ${i} ${step.service}.${step.method}: ${why}`);
        break;
      }
    }
  } finally {
    for (const [i, step] of (scenario.finally ?? []).entries()) {
      traffic.step = `finally[${i}]`;
      await outcome(client, step, names).catch(() => undefined);
    }
  }
  return failures;
}
