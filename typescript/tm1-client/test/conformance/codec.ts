// SPDX-License-Identifier: Apache-2.0

/** The language-neutral encoding of the conformance fixtures (spec/conformance/README.md): arguments
 * decoded into this client's values, results and errors encoded to compare with what the Java
 * client recorded, and the request comparison every runner makes. */
import { readFileSync } from "node:fs";
import { Filter, ODataPath, ODataQuery, Tm1PatchBuilder } from "../../src/index.js";
import * as Errors from "../../src/errors/index.js";
import * as Model from "../../src/generated/model.js";
import * as Patches from "../../src/generated/patches.js";
import * as Services from "../../src/services/index.js";
import { schema } from "../../src/generated/schema.js";
import { Repo } from "../support/repo.js";

export const SPEC_VERSION = 1;

/** The request headers fixtures record: as the Java recorder's, plus TM1-* but the session's own. */
const HEADERS = new Set(["accept", "content-type", "if-match", "if-none-match", "odata-version", "odata-maxversion", "prefer"]);

const BIND = "@odata.bind";
type Builder = Record<string, (...args: unknown[]) => unknown> & { build(): unknown };
const settersOf = new Map<unknown, Map<string, string>>();

const spec = JSON.parse(readFileSync(Repo.spec, "utf8")) as {
  builders: Record<string, { odataType: string | null; setters: { json: string; collection: boolean; value: string | null }[] }>;
  types: {
    name: string;
    qualifiedName: string;
    baseType: string | null;
    properties?: { name: string; type: string }[];
    navigationProperties?: { name: string; type: string }[];
  }[];
  enums: { qualifiedName: string; name: string; flags: boolean; members: { name: string }[] }[];
};
const types = new Map(spec.types.map((t) => [t.qualifiedName, t]));
const enums = new Map(spec.enums.map((e) => [e.qualifiedName, e]));

/** A write as the generated builder makes it, as the Java runner's PatchBuilders: each key by the
 * setter that writes it, a nested write type's value by its own builder (a root's by the subtype its
 * `@odata.type` names), a binding by the bind setter from the path as written (already encoded, as
 * TM1 gets it), an open type's undeclared key by `property`. Refused, as a caller couldn't send
 * them: a key no setter writes (a declared one included, on an open type too), an enum value TM1
 * doesn't have, a binding of the wrong shape, an `@odata.type` the write type doesn't send. */
export function build(type: any, values: Json, read?: unknown): unknown {
  if (values === null || typeof values !== "object" || Array.isArray(values)) throw new Error(`A ${type.name} is an object, not ${JSON.stringify(values)}`);
  // With what was read, from builder(read), as a caller changes it ($fromRead); a root's hands
  // off to its subtype's, so the subtype's write type is named instead
  if (read !== undefined && (spec.builders[type.name] as { dispatch?: unknown } | undefined)?.dispatch)
    throw new Error(`${type.name} is a root: build from a read with a subtype's`);
  const write = read === undefined ? subtype(type, values) : type;
  const b = (read === undefined ? write.builder() : write.builder(read)) as Builder;
  const setters = settersFor(write);
  const plan = spec.builders[write.name]!;
  const byJson = new Map(plan.setters.map((s) => [s.json, s]));
  const declared = declaredOf(write.name);
  for (const [key, value] of Object.entries(values)) {
    if (key === "@odata.type") {
      // The builder sends its own, which must be this one
      if (typeof value !== "string" || plan.odataType !== value) throw new Error(`${write.name} doesn't send @odata.type ${String(value)}`);
      continue;
    }
    const setter = setters.get(key);
    if (setter !== undefined) {
      if (key.endsWith(BIND)) {
        if (byJson.get(key.slice(0, -BIND.length))!.collection) {
          if (!Array.isArray(value)) throw new Error(`${setter} binds a list of paths, not ${JSON.stringify(value)}`);
          b[setter]!(...value.map((p) => path(setter, p)));
        } else b[setter]!(path(setter, value));
      } else {
        const collection = byJson.get(key)!.collection;
        if (value !== null && collection !== Array.isArray(value))
          throw new Error(`${write.name}.${key} takes ${collection ? "a list" : "one value"}, not ${JSON.stringify(value)}`);
        check(declared.get(key), value);
        b[setter]!(nested(byJson.get(key)?.value ?? null, value));
      }
    } else if (!key.startsWith("@") && typeof b["property"] === "function" && !declared.has(key.endsWith(BIND) ? key.slice(0, -BIND.length) : key))
      b["property"](key, value);
    else throw new Error(`${write.name} has no setter for ${key}`);
  }
  return b.build();
}

/** The properties and navigations a write's type declares, its base's too, by name. */
function declaredOf(write: string): Map<string, { name: string; type: string }> {
  const name = write.replace(/(Create|Patch)$/, "");
  const out = new Map<string, { name: string; type: string }>();
  for (let level = spec.types.find((t) => t.name === name); level; level = level.baseType ? types.get(level.baseType) : undefined)
    for (const p of [...(level.properties ?? []), ...(level.navigationProperties ?? [])]) if (!out.has(p.name)) out.set(p.name, p);
  return out;
}

/** An enum's value must be one TM1 has, as the typed clients refuse what doesn't read back. */
function check(prop: { type: string } | undefined, value: Json): void {
  const e = prop && enums.get(prop.type);
  if (!e || value === null) return;
  const members = new Set(e.members.map((m) => m.name));
  for (const v of Array.isArray(value) ? value : [value]) {
    const parts = e.flags && typeof v === "string" ? v.split(",").map((s) => s.trim()) : [v];
    if (parts.some((part) => typeof part !== "string" || !members.has(part)))
      throw new Error(`${JSON.stringify(v)} doesn't read back as written: ${e.name} has no such member`);
  }
}

function subtype(type: any, values: Record<string, Json>): any {
  const id = values["@odata.type"];
  if (typeof id !== "string") return type;
  const suffix = type.name.endsWith("Create") ? "Create" : "Patch";
  const sub = (Patches as Record<string, any>)[id.slice(id.lastIndexOf(".") + 1) + suffix];
  if (typeof sub !== "function" || !(sub === type || sub.prototype instanceof type)) throw new Error(`no write type for ${id} that is a ${type.name}`);
  return sub;
}

function nested(write: string | null, value: Json): unknown {
  if (value === null || write === null) return value;
  const type = (Patches as Record<string, any>)[write];
  return Array.isArray(value) ? value.map((v) => build(type, v)) : build(type, value);
}

/** A binding's path as written, already encoded, as TM1 gets it: its segments taken as they are, so a
 * key's `%2F` stays one, and the bind setter writes it back the same. */
function path(setter: string, encoded: Json): ODataPath {
  if (typeof encoded !== "string") throw new Error(`${setter} binds a path, not ${JSON.stringify(encoded)}`);
  // Encoded as a client encodes a segment: unreserved characters, or a %XX
  for (const segment of encoded.split("/"))
    if (!/^([A-Za-z0-9\-._~'()=,@$!]|%[0-9A-F]{2})*$/.test(segment)) throw new Error(`${encoded} isn't encoded as TM1 gets it: ${segment}`);
  const make = ODataPath as unknown as new (segments: readonly string[]) => ODataPath;
  return new make(encoded.split("/"));
}

/** The builder's setters, by the JSON name each writes: each called once on a fresh builder, with its
 * `set`, `bind` and `bindAll` watched, as the builder manifest finds them. */
function settersFor(type: any): Map<string, string> {
  let out = settersOf.get(type);
  if (out) return out;
  out = new Map();
  const names = new Set<string>();
  for (let p = Object.getPrototypeOf(type.builder()) as object; p !== Tm1PatchBuilder.prototype; p = Object.getPrototypeOf(p) as object)
    for (const name of Object.getOwnPropertyNames(p))
      if (!["constructor", "build", "property"].includes(name) && typeof (p as Record<string, unknown>)[name] === "function") names.add(name);
  for (const name of names) {
    const fresh = type.builder() as Builder;
    const wrote: string[] = [];
    const watched = fresh as unknown as Record<string, (n: string, v: unknown) => unknown>;
    for (const [method, suffix] of [
      ["set", ""],
      ["bind", BIND],
      ["bindAll", BIND],
    ] as const) {
      const original = watched[method]!.bind(fresh);
      watched[method] = (n: string, v: unknown) => {
        wrote.push(n + suffix);
        return original(n, v);
      };
    }
    fresh[name]!(/^bind[A-Z]/.test(name) ? ODataPath.of("x") : null);
    if (wrote.length !== 1) throw new Error(`${type.name}.${name} writes ${wrote.join(", ")}`);
    out.set(wrote[0]!, name);
  }
  settersOf.set(type, out);
  return out;
}

/** A tag or type this runner can't decode: a failure, never a skip. */
export class Unknown extends Error {}

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

const pascal = (javaConstant: string): string =>
  javaConstant
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");

/** A `$fromRead` argument, `{"$fromRead": "CubePatch", "read": "cube", "json": {...}}`: `CubePatch.builder(read)`
 * with the changes `json` names, through the setters a `$patch` uses, from what a step before kept. */
export function fromRead(v: Record<string, any>, kept: Map<string, unknown>): unknown {
  const type = (Patches as Record<string, any>)[v.$fromRead];
  if (typeof type !== "function") throw new Unknown("no patch " + v.$fromRead);
  if (!kept.has(v.read)) throw new Error(`No read kept as ${JSON.stringify(v.read)} for ${JSON.stringify(v)}`);
  return build(type, v.json ?? {}, kept.get(v.read));
}

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
      // Through the generated setters, as a caller makes one (#354)
      return build(type, v.json as Record<string, Json>);
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

/** This client's result without `@odata.type`, which the recorded `$model` stands for. */
export function javaResult(value: Json, _recorded?: Json): Json {
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
    return Object.fromEntries(
      Object.entries(value)
        .filter(([k]) => k !== "@odata.type")
        .map(([k, v]) => [k, withoutTypes(v)]),
    );
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

/** For comparing: with dropNulls (results), null members left out. Everything else, date-times
 * and durations included, must be exactly as Java wrote it. (JSON numbers have one type here.) */
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
