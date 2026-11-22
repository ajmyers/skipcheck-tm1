// Reading TM1's JSON into the model: TM1's own property names stay, so a value read is the JSON it
// was, except where reading means something: an enum value this client doesn't know becomes the
// enum's fallback, a duration sent as bare seconds becomes ISO 8601, a lenient date TM1 fills with
// `0000-00-00T00:00Z` becomes null, and the root of a hierarchy is read as the subtype its
// `@odata.type` names.

import type { Tm1Model } from "./model.js";
import { schema } from "./generated/schema.js";

/** @internal The shape of the generated schema.ts. */
export interface Tm1Schema {
  enums: Record<string, { qualifiedName: string; members: string[]; fallback: string }>;
  types: Record<
    string,
    {
      qualifiedName: string;
      base?: string;
      typeId?: string;
      derived?: Record<string, string>;
      properties: Record<string, string>;
    }
  >;
}

/** TM1's durations: ISO 8601 (`P1DT00H00M00S` as TM1 writes it), in milliseconds and back. */
export const Tm1Duration = {
  /** `P1DT2H` → 93_600_000. */
  toMillis(iso: string): number {
    const m = /^(-)?P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(iso.trim());
    if (!m || iso.trim() === "P" || iso.trim().endsWith("T")) throw new RangeError("Not an ISO 8601 duration: " + iso);
    const n = (i: number): number => (m[i] ? Number(m[i]) : 0);
    const ms = ((((n(2) * 365 + n(3) * 30 + n(4) * 7 + n(5)) * 24 + n(6)) * 60 + n(7)) * 60 + n(8)) * 1000;
    return m[1] ? -ms : ms;
  },

  /** 93_600_000 → `P1DT2H`; 0 → `PT0S`. */
  ofMillis(ms: number): string {
    if (!Number.isFinite(ms)) throw new RangeError("Not a duration: " + ms);
    const sign = ms < 0 ? "-" : "";
    let rest = Math.abs(ms);
    const days = Math.floor(rest / 86_400_000);
    rest -= days * 86_400_000;
    const hours = Math.floor(rest / 3_600_000);
    rest -= hours * 3_600_000;
    const minutes = Math.floor(rest / 60_000);
    rest -= minutes * 60_000;
    const seconds = rest / 1000;
    let time = "";
    if (hours) time += hours + "H";
    if (minutes) time += minutes + "M";
    if (seconds) time += Number(seconds.toFixed(3)) + "S";
    if (!days && !time) return "PT0S";
    return sign + "P" + (days ? days + "D" : "") + (time ? "T" + time : "");
  },
} as const;

const NANOS = 1_000_000_000n;

/** A duration as the Java client reads it and writes it back (`Duration.toString`): TM1's
 * `P1DT02H00M03.5S`, or bare seconds, as `PT26H3.5S`; hours, minutes and seconds, never days. A
 * string Java couldn't read (years, months, weeks) stays as it is. */
function javaDuration(value: unknown): unknown {
  let nanos: bigint;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) return value;
    nanos = BigInt(Math.round(value * 1e9));
  } else if (typeof value === "string") {
    const m = /^([-+])?P(?:([-+]?\d+)D)?(?:T(?:([-+]?\d+)H)?(?:([-+]?\d+)M)?(?:([-+]?)(\d+)(?:[.,](\d{0,9}))?S)?)?$/i.exec(value.trim());
    if (!m || /T$/i.test(value.trim()) || /^[-+]?P$/i.test(value.trim())) return value;
    const n = (s: string | undefined): bigint => (s ? BigInt(s) : 0n);
    let seconds = ((n(m[2]) * 24n + n(m[3])) * 60n + n(m[4])) * 60n;
    const s = n(m[6]) * NANOS + (m[7] ? BigInt(m[7].padEnd(9, "0")) : 0n);
    seconds = seconds * NANOS + (m[5] === "-" ? -s : s);
    nanos = m[1] === "-" ? -seconds : seconds;
  } else {
    return value;
  }
  if (nanos === 0n) return "PT0S";
  // As Java's Duration: whole seconds (rounded down) and a positive fraction
  let seconds = nanos / NANOS;
  let fraction = nanos % NANOS;
  if (fraction < 0n) {
    fraction += NANOS;
    seconds -= 1n;
  }
  const total = seconds < 0n && fraction > 0n ? seconds + 1n : seconds;
  const hours = total / 3600n;
  const minutes = (total % 3600n) / 60n;
  const secs = total % 60n;
  let out = "PT";
  if (hours !== 0n) out += hours + "H";
  if (minutes !== 0n) out += minutes + "M";
  if (secs === 0n && fraction === 0n && out.length > 2) return out;
  out += seconds < 0n && fraction > 0n && secs === 0n ? "-0" : String(secs);
  if (fraction > 0n) out += "." + String(seconds < 0n ? 2n * NANOS - fraction : fraction + NANOS).slice(1).replace(/0+$/, "");
  return out + "S";
}

/** A date-time as the Java client reads it and writes it back: its instant in UTC, with the
 * fraction of a second as given but for trailing zeros (`2026-01-02T03:04:00.120+05:30` →
 * `2026-01-01T21:34:00.12Z`). A string that isn't one stays as it is. */
function javaDateTime(value: unknown): unknown {
  if (typeof value !== "string") return value;
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d))?(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)$/i.exec(value);
  if (!m) return value;
  const offset = m[8]!.toUpperCase() === "Z" ? 0 : (m[8]![0] === "-" ? -1 : 1) * (Number(m[8]!.slice(1, 3)) * 60 + Number(m[8]!.slice(4, 6)));
  const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6] ?? 0)) - offset * 60_000;
  if (Number.isNaN(ms)) return value;
  const fraction = (m[7] ?? "").replace(/0+$/, "");
  return new Date(ms).toISOString().slice(0, 19) + (fraction ? "." + fraction : "") + "Z";
}

function readEnum(name: string, value: unknown): unknown {
  const e = schema.enums[name];
  if (!e || typeof value !== "string") return value;
  return e.members.includes(value) ? value : e.fallback;
}

function readValue(kind: string, value: unknown): unknown {
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.map((v) => readValue(kind, v));
  if (kind === "d") return typeof value === "number" ? Tm1Duration.ofMillis(value * 1000) : value;
  if (kind === "l") return typeof value === "string" && !Number.isNaN(Date.parse(value)) && !value.startsWith("0000") ? value : null;
  if (kind.startsWith("e:")) return readEnum(kind.slice(2), value);
  if (kind.startsWith("t:")) return readType(kind.slice(2), value);
  return value;
}

/** The type `value` is read as: the one its `@odata.type` names, if it's in `name`'s hierarchy. */
function chosen(name: string, value: Record<string, unknown>): string {
  let root = name;
  while (schema.types[root]?.base) root = schema.types[root]!.base!;
  const odataType = value["@odata.type"];
  const derived = schema.types[root]?.derived;
  if (typeof odataType === "string" && derived) {
    const d = derived[odataType.replace(/^#/, "")];
    if (d) return d;
  }
  return name;
}

function readType(name: string, value: unknown): unknown {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return value;
  const record = value as Record<string, unknown>;
  for (let t: string | undefined = chosen(name, record); t; t = schema.types[t]?.base) {
    const info = schema.types[t];
    if (!info) break;
    for (const [property, kind] of Object.entries(info.properties)) {
      if (property in record) record[property] = readValue(kind, record[property]);
    }
  }
  return record;
}

/** Reads parsed JSON as `type` (changing it in place, and returning it). With no type, or
 * {@link Json}, it's returned as is. */
export function readAs<T>(type: Tm1Model<T> | undefined, value: unknown): T {
  if (!type || type.$type === "") return value as T;
  if (type.$type.startsWith("$")) {
    // A primitive answer comes wrapped, as {"@odata.context": …, "value": 12.5}
    const v = value !== null && typeof value === "object" && "value" in value ? (value as { value: unknown }).value : value;
    return v as T;
  }
  return readType(type.$type, value) as T;
}

function javaValue(kind: string, value: unknown): unknown {
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.map((v) => javaValue(kind, v));
  if (kind === "d") return javaDuration(value);
  if (kind === "dt" || kind === "l") return javaDateTime(value);
  if (kind.startsWith("t:")) return javaForm(kind.slice(2), value);
  return value;
}

/** @internal A model read as `name`, with its durations and date-times written as the Java client
 * writes the values it read: for comparing what this client read with what Java did. Reading
 * itself keeps TM1's text. Returns a copy. */
export function javaForm(name: string, value: unknown): unknown {
  if (Array.isArray(value)) return value.map((v) => javaForm(name, v));
  if (typeof value !== "object" || value === null) return value;
  const record = { ...(value as Record<string, unknown>) };
  for (let t: string | undefined = chosen(name, record); t; t = schema.types[t]?.base) {
    const info = schema.types[t];
    if (!info) break;
    for (const [property, kind] of Object.entries(info.properties)) {
      if (property in record) record[property] = javaValue(kind, record[property]);
    }
  }
  return record;
}

/** The subtype `type` names a value as, by its `@odata.type`: for checks such as
 * `typeOf(View, v) === "NativeView"`. */
export function typeOf<T>(type: Tm1Model<T>, value: T): string {
  return typeof value === "object" && value !== null ? chosen(type.$type, value as Record<string, unknown>) : type.$type;
}

/** @internal JSON for a request body: TM1's names, patches as set. */
export function writeJson(body: unknown): string {
  try {
    return JSON.stringify(body);
  } catch (e) {
    // The caller's mistake, not a failure talking to TM1
    throw new TypeError("The request body can't be written as JSON: " + (e as Error).message);
  }
}
