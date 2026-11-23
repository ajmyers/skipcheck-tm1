// SPDX-License-Identifier: Apache-2.0

import { entriesOf, type Tm1Map } from "../../util.js";

/** @internal `body` with `Parameters`, as TM1's name-value pairs (`[{"Name": …, "Value": …}]`) in
 * order, if there are any: what the Java client's process services send. */
export function withParameters(body: Record<string, unknown>, parameters: Tm1Map<unknown> | null | undefined): Record<string, unknown> {
  const pairs = entriesOf(parameters);
  return pairs.length === 0 ? { ...body } : { ...body, Parameters: pairs.map(([Name, Value]) => ({ Name, Value: Value === undefined ? null : Value })) };
}

const OFFSET_DATE_TIME = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?(Z|[+-]\d{2}(?::?\d{2})?)$/i;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIME = /^(\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?$/;

/** @internal An ISO 8601 date and time, as Java's `OffsetDateTime.toString` writes it (seconds and
 * fractions only when not zero, the fraction in groups of three digits, `Z` for UTC); as it is if
 * it isn't one. */
export function javaOffsetDateTime(text: string): string {
  const m = OFFSET_DATE_TIME.exec(text);
  if (!m) return text;
  const [, date, hour, minute, second, fraction, zone] = m;
  let offset = zone!.toUpperCase();
  if (offset !== "Z") {
    const digits = offset.slice(1).replace(":", "").padEnd(4, "0");
    offset = /^0+$/.test(digits) ? "Z" : `${offset[0]}${digits.slice(0, 2)}:${digits.slice(2, 4)}`;
  }
  return `${date}T${javaTime(hour!, minute!, second, fraction)}${offset}`;
}

/** @internal A date, `2026-01-02`, as Java's `LocalDate.toString` writes it. */
export function javaLocalDate(text: string, name: string): string {
  if (typeof text !== "string" || !DATE.test(text)) throw new RangeError(`${name} is a date, yyyy-MM-dd: '${String(text)}'`);
  return text;
}

/** @internal A time of day, `HH:mm[:ss[.fff]]`, as Java's `LocalTime.toString` writes it: `02:00`,
 * `02:00:30`, `02:00:30.500`. */
export function javaLocalTime(text: string, name: string): string {
  const m = typeof text === "string" ? TIME.exec(text) : null;
  if (!m) throw new RangeError(`${name} is a time of day, HH:mm[:ss[.fff]]: '${String(text)}'`);
  return javaTime(m[1]!, m[2]!, m[3], m[4]);
}

function javaTime(hour: string, minute: string, second: string | undefined, fraction: string | undefined): string {
  const nanos = (fraction ?? "").padEnd(9, "0");
  const secs = second ?? "00";
  const text = `${hour}:${minute}`;
  if (secs === "00" && /^0+$/.test(nanos)) return text;
  if (/^0+$/.test(nanos)) return `${text}:${secs}`;
  return `${text}:${secs}.` + (nanos.endsWith("000000") ? nanos.slice(0, 3) : nanos.endsWith("000") ? nanos.slice(0, 6) : nanos);
}
