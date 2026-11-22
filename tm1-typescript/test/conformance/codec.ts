/** The language-neutral encoding of the conformance fixtures (spec/conformance/README.md): arguments
 * decoded into this client's values, results and errors encoded to compare with what the Java
 * client recorded, and the request comparison every runner makes. */
import { Filter, ODataPath, ODataQuery } from "../../src/index.js";
import * as Errors from "../../src/errors/index.js";
import * as Model from "../../src/generated/model.js";
import * as Patches from "../../src/generated/patches.js";
import * as Services from "../../src/services/index.js";

export const SPEC_VERSION = 1;

/** The request headers fixtures record: as the Java recorder's, plus TM1-* but the session's own. */
const HEADERS = new Set(["accept", "content-type", "if-match", "if-none-match", "odata-version", "odata-maxversion", "prefer"]);

/** A tag or type this runner can't decode: a failure, never a skip. */
export class Unknown extends Error {}

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

const pascal = (javaConstant: string): string =>
  javaConstant
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");

/** An argument, as this client takes it. */
export function decode(value: Json): unknown {
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(decode);
  const tag = Object.keys(value)[0];
  const v = value as Record<string, any>;
  switch (tag) {
    case "$double":
      return Number(v.$double);
    case "$query":
      return query(v.$query);
    case "$path":
      return ODataPath.link(v.$path);
    case "$map":
      return Object.fromEntries(Object.entries(v.$map as Record<string, Json>).map(([k, x]) => [k, decode(x)]));
    case "$patch": {
      const type = (Patches as Record<string, any>)[v.$patch];
      if (typeof type !== "function") throw new Unknown("no patch " + v.$patch);
      return new type(new Map(Object.entries(v.json)));
    }
    case "$model":
      return v.json;
    case "$enum": {
      const values = (Services as Record<string, any>)[v.$enum] ?? (Model as Record<string, any>)[v.$enum];
      if (values === undefined) throw new Unknown("no enum " + v.$enum);
      const member = values[pascal(v.name)];
      return typeof member === "string" ? member : v.json;
    }
    case "$duration":
      return durationMillis(v.$duration);
    case "$dateTime":
    case "$date":
    case "$time":
      return v[tag];
    case "$bytes":
      return Uint8Array.from(Buffer.from(v.$bytes, "base64"));
    case "$type": {
      const type = (Services as Record<string, any>)[v.$type];
      if (type === undefined || typeof type[v.factory] !== "function") throw new Unknown(`no factory ${v.$type}.${v.factory}`);
      return type[v.factory](...(v.args as Json[]).map(decode));
    }
    default:
      throw new Unknown("unknown tag " + tag);
  }
}

/** A query rebuilt from its parts, with this client's own query builder. */
export function query(parts: Record<string, any>): ODataQuery {
  const known = new Set(["select", "expand", "filter", "orderBy", "top", "skip", "count", "options", "aliases"]);
  const unknown = Object.keys(parts).filter((k) => !known.has(k));
  if (unknown.length > 0) throw new Unknown("unknown query parts " + unknown.join(", "));
  let q = ODataQuery.none();
  if (parts.select) q = q.select(...(parts.select as string[]));
  for (const [name, nested] of Object.entries((parts.expand ?? {}) as Record<string, Record<string, any>>)) {
    q = Object.keys(nested).length > 0 ? q.expand(name, () => query(nested)) : q.expand(name);
  }
  if (parts.filter !== undefined) q = q.filter(Filter.raw(parts.filter));
  for (const key of (parts.orderBy ?? []) as string[]) {
    q = key.endsWith(" desc") ? q.orderByDescending(key.slice(0, -" desc".length)) : q.orderBy(key);
  }
  if (parts.top !== undefined) q = q.top(parts.top);
  if (parts.skip !== undefined) q = q.skip(parts.skip);
  if (parts.count) q = q.withCount();
  for (const [name, value] of Object.entries((parts.options ?? {}) as Record<string, string>)) q = q.option(name, value);
  for (const [name, value] of Object.entries((parts.aliases ?? {}) as Record<string, string>)) q = q.alias(new ODataPath.Alias(name), value);
  return q;
}

/** Java's Duration.toString (PT1H2M3.5S) in milliseconds, as this client's durations are. */
function durationMillis(iso: string): number {
  const m = /^(-)?P(?:(\d+)D)?(?:T(?:(-?\d+)H)?(?:(-?\d+)M)?(?:(-?\d+(?:\.\d+)?)S)?)?$/.exec(iso);
  if (!m) throw new Unknown("not a duration: " + iso);
  const [, neg, d, h, min, s] = m;
  const ms = ((Number(d ?? 0) * 24 + Number(h ?? 0)) * 60 + Number(min ?? 0)) * 60_000 + Math.round(Number(s ?? 0) * 1000);
  return neg ? -ms : ms;
}

/** A result, encoded to compare with the Java recorder's: models are TM1's JSON here, so a
 * recorded {"$model", "json"} compares by its JSON. */
export function result(value: unknown): Json {
  if (value === undefined || value === null) return null;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value;
  if (Array.isArray(value)) return value.map(result);
  if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString("base64") };
  if (typeof value === "object") return JSON.parse(JSON.stringify(value)) as Json;
  throw new Unknown("can't encode a result of type " + typeof value);
}

/** Recorded results without what only Java knows: a model's type name. */
export function expectedResult(value: Json): Json {
  if (Array.isArray(value)) return value.map(expectedResult);
  if (value !== null && typeof value === "object" && "$model" in value) return (value as Record<string, Json>).json ?? null;
  return value;
}

export interface EncodedError {
  exception: string;
  category: string;
  kind?: string;
  status?: number;
  code?: string;
}

/** How a call failed, as the Java recorder writes it (kinds in Java's constant case). */
export function error(e: unknown): EncodedError {
  const exception = e instanceof Error ? e.constructor.name : typeof e;
  if (e instanceof Errors.Tm1ApiException) {
    const out: EncodedError = { exception, category: "api", kind: constantCase(e.kind), status: e.status };
    if (e.error?.code) out.code = e.error.code;
    return out;
  }
  if (e instanceof Errors.Tm1UnsupportedOperationException) return { exception, category: "unsupported" };
  if (e instanceof Errors.Tm1ProtocolException) return { exception, category: "protocol" };
  if (e instanceof Errors.Tm1TransportException) return { exception, category: "transport" };
  if (e instanceof RangeError || e instanceof TypeError) return { exception, category: "argument" };
  if (e instanceof Error && e.constructor === Error) return { exception, category: "state" };
  throw e;
}

const constantCase = (pascalCase: string): string => pascalCase.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();

/** Equal but for the exception's name, which is each language's own. */
export function sameError(expected: EncodedError, got: EncodedError): boolean {
  const pick = (x: EncodedError) => JSON.stringify([x.category, x.kind ?? null, x.status ?? null, x.code ?? null]);
  return pick(expected) === pick(got);
}

/** For comparing: with dropNulls, null members left out. (JSON numbers have one type here.) */
export function normal(value: Json, dropNulls = false): Json {
  if (Array.isArray(value)) return value.map((v) => normal(v, dropNulls));
  if (value !== null && typeof value === "object") {
    const out: Record<string, Json> = {};
    for (const [k, v] of Object.entries(value)) if (!(dropNulls && v === null)) out[k] = normal(v, dropNulls);
    return out;
  }
  return value;
}

/** JSON keeping its members' order, for comparing bodies exactly. */
export function ordered(value: Json): unknown {
  if (Array.isArray(value)) return value.map(ordered);
  if (value !== null && typeof value === "object") return Object.entries(value).map(([k, v]) => [k, ordered(v)]);
  return value;
}

/** A request as a fixture records it. */
export function requestOf(method: string, target: string, headers: Record<string, string>, body: Uint8Array): Record<string, unknown> {
  const kept: Record<string, string> = {};
  for (const k of Object.keys(headers).sort()) {
    if (HEADERS.has(k) || (k.startsWith("tm1-") && k !== "tm1-sessioncontext")) kept[k] = headers[k]!;
  }
  const out: Record<string, unknown> = { method, target, headers: kept };
  if (body.length > 0) {
    const type = (headers["content-type"] ?? "").toLowerCase();
    const text = new TextDecoder().decode(body);
    if (type.startsWith("application/json")) out.json = JSON.parse(text);
    else if (type.startsWith("text/")) out.text = text;
    else out.bytes = Buffer.from(body).toString("base64");
  }
  return out;
}

export function comparable(request: Record<string, unknown>): string {
  const out: Record<string, unknown> = {};
  for (const k of ["method", "target", "headers", "json", "text", "bytes"]) {
    if (k in request) out[k] = k === "json" ? ordered(request[k] as Json) : request[k];
  }
  return JSON.stringify(out);
}
