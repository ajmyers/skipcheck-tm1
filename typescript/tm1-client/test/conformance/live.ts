// SPDX-License-Identifier: Apache-2.0

/** Runs a live conformance scenario (spec/conformance/live/<name>.json), checks each step's
 * expectation, and writes what the client sent to build/conformance-live/typescript/<name>.jsonl
 * for scripts/compare-live-traffic.py. */
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Tm1Version, type Tm1HttpRequest, type Tm1HttpResponse, type Tm1Transport } from "../../src/index.js";
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
  /** Fields of the result to keep for the steps after it, by the name they're kept as:
   * `{"note": "ID"}` makes the result's `ID` `${note}`. */
  save?: Record<string, string>;
  /** A name to keep the result by, as read, for a `$fromRead` argument after it. */
  keep?: string;
  /** What the step needs of the server: endpoints, and product versions (see {@link applies}). */
  requires?: string[];
  tm1From?: string;
  tm1Before?: string;
}

export interface Scenario {
  specVersion: number;
  name: string;
  names: string[];
  steps: Step[];
  finally?: Step[];
  /** What the scenario needs of the server, as a step's. */
  requires?: string[];
  tm1From?: string;
  tm1Before?: string;
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

export type Outcome = { result: any } | { error: codec.EncodedError };

/** Equal JSON at any depth: numbers by their value, and a null as a property left out, as clients
 * differ in whether a model's JSON keeps a null. */
export function same(expected: unknown, got: unknown): boolean {
  if (expected === null || expected === undefined || got === null || got === undefined)
    return (expected === null || expected === undefined) && (got === null || got === undefined);
  if (Array.isArray(expected) || Array.isArray(got))
    return Array.isArray(expected) && Array.isArray(got) && expected.length === got.length && expected.every((e, i) => same(e, got[i]));
  if (typeof expected === "object" && typeof got === "object") {
    const e = expected as Record<string, unknown>;
    const g = got as Record<string, unknown>;
    return [...new Set([...Object.keys(e), ...Object.keys(g)])].every((k) => same(e[k], g[k]));
  }
  return expected === got;
}

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
  if (expect.fields && (result === null || typeof result !== "object" || Object.entries(expect.fields).some(([k, v]) => !same(v, result[k])))) {
    return `expected the fields ${JSON.stringify(expect.fields)}, got ${JSON.stringify(result)}`;
  }
  if (expect.items) {
    for (const [field, values] of Object.entries(expect.items as Record<string, unknown[]>)) {
      const got = (Array.isArray(result) ? result : []).map((i: any) => i?.[field] ?? null);
      if (!same(values, got)) return `expected the items' ${field} ${JSON.stringify(values)}, got ${JSON.stringify(got)}`;
    }
  }
  return undefined;
}

async function outcome(client: any, step: Step, names: Record<string, string>, kept: Map<string, unknown>): Promise<Outcome> {
  const service = client[step.service];
  if (!service || typeof service[step.method] !== "function") throw new codec.Unknown(`no ${step.service}.${step.method}`);
  const args = step.args.map((a) => {
    const v = substitute(a, names);
    return v !== null && typeof v === "object" && !Array.isArray(v) && "$fromRead" in v ? codec.fromRead(v, kept) : codec.decode(v);
  });
  try {
    let value: any = service[step.method](...args);
    value = value && typeof value.toArray === "function" ? await value.toArray() : await value;
    // A stream of bytes, such as a document's content, as its bytes however they're split
    if (Array.isArray(value) && value.length > 0 && value.every((v) => v instanceof Uint8Array)) value = Buffer.concat(value);
    // A step's result, as read, kept for a $fromRead after it
    if (step.keep !== undefined && value !== undefined && value !== null) kept.set(step.keep, value);
    return { result: codec.result(value) };
  } catch (e) {
    if (e instanceof codec.Unknown) throw e;
    return { error: codec.error(e) };
  }
}

/** Keeps what a step's `save` names from its result, `{"note": "ID"}`: the result's `ID` as the
 * name `note`, for the steps after it, such as an ID TM1 made up. Why it can't, or undefined. */
export function save(step: Step, outcome: Outcome, names: Record<string, string>): string | undefined {
  for (const [name, field] of Object.entries(step.save ?? {})) {
    const result = "result" in outcome ? outcome.result : undefined;
    const json = result !== null && typeof result === "object" && "$model" in result ? result.json : result;
    const value = json !== null && typeof json === "object" && !Array.isArray(json) ? json[field] : undefined;
    if (value === undefined || value === null || typeof value === "object") return `no ${field} to save as ${name} in ${JSON.stringify(result)}`;
    names[name] = String(value);
  }
  return undefined;
}

/** Whether a scenario or a step applies to a server, as Java's `ConformanceScenarios.applies`:
 * every endpoint its `requires` names is in the server's `$metadata`, and the server's product
 * version is at least its `tm1From` and below its `tm1Before`. */
export function applies(node: { requires?: string[]; tm1From?: string; tm1Before?: string }, endpoints: ReadonlySet<string>, version: Tm1Version): boolean {
  if ((node.requires ?? []).some((e) => !endpoints.has(e))) return false;
  if (node.tm1From !== undefined && version.compareTo(Tm1Version.parse(node.tm1From)) < 0) return false;
  return node.tm1Before === undefined || version.compareTo(Tm1Version.parse(node.tm1Before)) < 0;
}

/** Runs the scenario; the steps whose expectation failed. Its `finally` steps always run. What a
 * step saves joins `names`, so the traffic written after it has TM1's made-up values as their
 * placeholders too. Only the steps `applies` accepts run: one it refuses sends nothing
 * and isn't checked. */
export async function run(
  client: unknown,
  traffic: Traffic,
  scenario: Scenario,
  names: Record<string, string>,
  applies: (step: Step) => boolean = () => true,
): Promise<string[]> {
  const failures: string[] = [];
  const kept = new Map<string, unknown>();
  try {
    for (const [i, step] of scenario.steps.entries()) {
      if (!applies(step)) continue;
      traffic.step = `steps[${i}]`;
      const got = await outcome(client, step, names, kept);
      const why = check(substitute(step.expect ?? {}, names), got) ?? save(step, got, names);
      if (why) {
        failures.push(`step ${i} ${step.service}.${step.method}: ${why}`);
        break;
      }
    }
  } finally {
    for (const [i, step] of (scenario.finally ?? []).entries()) {
      traffic.step = `finally[${i}]`;
      if (!applies(step)) continue;
      // A step setting back what a step that didn't run would have read has nothing to send
      if (step.args.some((a) => a !== null && typeof a === "object" && !Array.isArray(a) && "$fromRead" in a && !kept.has(a.read))) continue;
      await outcome(client, step, names, kept).catch(() => undefined);
    }
  }
  return failures;
}
