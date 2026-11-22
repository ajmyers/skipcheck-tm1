/** The language-neutral encoding of the conformance fixtures (spec/conformance/README.md): arguments
 * decoded into this client's values, results and errors encoded to compare with what the Java
 * client recorded, and the request comparison every runner makes. */
import { Filter, ODataPath, ODataQuery } from "../../src/index.js";
import * as Errors from "../../src/errors/index.js";
import { javaForm } from "../../src/json.js";
import * as Model from "../../src/generated/model.js";
import * as Patches from "../../src/generated/patches.js";
import * as Services from "../../src/services/index.js";
import { schema } from "../../src/generated/schema.js";

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
    case "$handler": {
      const handler = HANDLERS[v.$handler as string];
      if (!handler) throw new Unknown("no handler " + v.$handler);
      return handler;
    }
    case "$type": {
      const type = (Services as Record<string, any>)[v.$type];
      const args = (v.args as Json[]).map(decode);
      if (type !== undefined && v.factory === "new") return new type(...args);
      if (type !== undefined && typeof type[v.factory] === "function") return type[v.factory](...args);
      // An instance method, such as ContentPath.child, on the first argument
      const [receiver, ...rest] = args as any[];
      if (type !== undefined && receiver instanceof type && typeof receiver[v.factory] === "function") return receiver[v.factory](...rest);
      throw new Unknown(`no factory ${v.$type}.${v.factory}`);
    }
    default:
      throw new Unknown("unknown tag " + tag);
  }
}

/** The handlers every runner knows by name (Java's ConformanceHandler). */
const HANDLERS: Record<string, unknown> = {
  // A cellset stream's cells
  cells: (_cellset: unknown, cells: unknown) => cells,
};

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

/** A result with each enum property that holds its fallback (a value this client doesn't know)
 * as null, as Java writes its fallback constant: by the model types the recorded result names. */
export function fallbacksAsNull(value: Json, recorded: Json): Json {
  if (Array.isArray(value) && Array.isArray(recorded)) return value.map((v, i) => fallbacksAsNull(v, recorded[i] ?? null));
  if (recorded !== null && typeof recorded === "object" && !Array.isArray(recorded) && typeof recorded.$model === "string") {
    return ofType(recorded.$model, value);
  }
  return value;
}

function ofType(type: string, value: Json): Json {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return value;
  const out: Record<string, Json> = { ...value };
  let root = type;
  while (schema.types[root]?.base) root = schema.types[root]!.base!;
  const odataType = typeof value["@odata.type"] === "string" ? value["@odata.type"].replace(/^#/, "") : "";
  for (let t: string | undefined = schema.types[root]?.derived?.[odataType] ?? type; t; t = schema.types[t]?.base) {
    for (const [property, kind] of Object.entries(schema.types[t]?.properties ?? {})) {
      if (property in out) out[property] = ofKind(kind, out[property]!);
    }
  }
  return out;
}

function ofKind(kind: string, value: Json): Json {
  if (Array.isArray(value)) return value.map((v) => ofKind(kind, v));
  if (kind.startsWith("e:")) return value === schema.enums[kind.slice(2)]?.fallback ? null : value;
  if (kind.startsWith("t:")) return ofType(kind.slice(2), value);
  return value;
}

/** This client's result, where Java's is a model, as Java writes the model: durations and date-times
 * as Java writes the values it read (reading here keeps TM1's text), and no `@odata.type`, which the
 * recorded `$model` stands for. */
export function javaResult(value: Json, recorded: Json): Json {
  if (Array.isArray(value) && Array.isArray(recorded)) return value.map((v, i) => javaResult(v, recorded[i] ?? null));
  if (recorded !== null && typeof recorded === "object" && !Array.isArray(recorded) && typeof recorded.$model === "string") {
    return withoutTypes(javaForm(recorded.$model, value) as Json);
  }
  return withoutTypes(value);
}

/** Recorded results without what only Java knows, a model's type name (`$model`): a model here is
 * the JSON TM1 sent. `got` is unused, kept for the callers' signature. */
export function expectedResult(value: Json, _got?: unknown): Json {
  if (Array.isArray(value)) return value.map((v) => expectedResult(v));
  if (value !== null && typeof value === "object" && "$model" in value) return (value as Record<string, Json>).json ?? null;
  return value;
}

/** JSON without `@odata.type`, at any depth: a recorded result leaves it out, its `$model` being its type. */
export function withoutTypes(value: unknown): Json {
  if (Array.isArray(value)) return value.map(withoutTypes);
  if (value !== null && typeof value === "object" && !(value instanceof Uint8Array)) {
    return Object.fromEntries(Object.entries(value).filter(([k]) => k !== "@odata.type").map(([k, v]) => [k, withoutTypes(v)]));
  }
  return value as Json;
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

const DATE_TIME = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}(?::?\d{2})?)$/i;
const DURATION = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d+))?S)?)?$/;

/** A date-time or a duration in a result as Java writes it (`2026-01-01T02:00:00Z`, `PT24H`),
 * however TM1 wrote it (`2026-01-01T02:00Z`, `P1DT00H00M00S`); any other text as it is. For
 * comparing results, not requests. */
export function asJavaText(text: string): string {
  const d = DATE_TIME.exec(text);
  if (d) {
    const [, date, minutes, seconds, fraction, zone] = d;
    const trimmed = (fraction ?? "").replace(/0+$/, "");
    let offset = zone!.toUpperCase();
    if (offset !== "Z") {
      const digits = offset.slice(1).replace(":", "").padEnd(4, "0");
      offset = Number(digits) === 0 ? "Z" : `${offset[0]}${digits.slice(0, 2)}:${digits.slice(2)}`;
    }
    return `${date}T${minutes}:${seconds ?? "00"}${trimmed ? "." + trimmed : ""}${offset}`;
  }
  const p = DURATION.exec(text);
  if (p && text !== "P" && text !== "PT" && !text.endsWith("T")) {
    const [, days, hours, minutes, seconds, fraction] = p;
    const total = ((Number(days ?? 0) * 24 + Number(hours ?? 0)) * 60 + Number(minutes ?? 0)) * 60 + Number(seconds ?? 0);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const nanos = (fraction ?? "").replace(/0+$/, "");
    let out = "PT" + (h ? `${h}H` : "") + (m ? `${m}M` : "");
    if (s || nanos) out += `${s}${nanos ? "." + nanos : ""}S`;
    return out === "PT" ? "PT0S" : out;
  }
  return text;
}

/** For comparing: with dropNulls (results), null members left out, and date-times and durations
 * as Java writes them. (JSON numbers have one type here.) */
export function normal(value: Json, dropNulls = false): Json {
  if (dropNulls && typeof value === "string") return asJavaText(value);
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
