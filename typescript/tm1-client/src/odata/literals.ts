// SPDX-License-Identifier: Apache-2.0

/** Something that writes itself as an OData literal. */
export interface ODataLiteral {
  /** The literal, exactly as it should appear before percent-encoding. */
  odataLiteral(): string;
}

function isLiteral(value: unknown): value is ODataLiteral {
  return typeof value === "object" && value !== null && typeof (value as ODataLiteral).odataLiteral === "function";
}

/** A number as a plain decimal, as Java's `BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString()`. */
function plain(d: number): string {
  const text = String(d);
  const e = text.indexOf("e");
  if (e < 0) return text;
  const negative = text.startsWith("-");
  const mantissa = text.slice(negative ? 1 : 0, e);
  const exponent = Number(text.slice(e + 1));
  const [whole, fraction = ""] = mantissa.split(".") as [string, string?];
  const digits = whole + fraction;
  const point = whole.length + exponent;
  let out: string;
  if (point <= 0) out = "0." + "0".repeat(-point) + digits;
  else if (point >= digits.length) out = digits + "0".repeat(point - digits.length);
  else out = digits.slice(0, point) + "." + digits.slice(point);
  return (negative ? "-" : "") + out;
}

/** OData literals, as the Java client's `ODataLiterals`, for keys, function parameters and filters. */
export namespace ODataLiterals {
  /** `O'Neil` → `'O''Neil'`. */
  export function string(value: string): string {
    if (typeof value !== "string") throw new TypeError("a string literal needs a string");
    return "'" + value.replace(/'/g, "''") + "'";
  }

  /** `ibm.tm1.api.v1.ElementType'Numeric'`. The generated enums make these: `ElementType.literal("Numeric")`. */
  export function enumMember(qualifiedType: string, member: string): string {
    return qualifiedType + string(member);
  }

  /** An ISO 8601 duration as a literal: `duration'P1D'`. */
  export function duration(iso: string): ODataLiterals.Raw {
    return new Raw("duration" + string(iso));
  }

  /** A value as an OData literal: `null`, quoted strings, `true`/`false`, numbers as plain decimals
   * (`NaN`, `INF`), bigints, dates in ISO 8601, and anything that writes itself
   * ({@link ODataLiterals.Raw}, a generated enum's `literal(…)`). */
  export function of(value: unknown): string {
    if (value === null || value === undefined) return "null";
    if (isLiteral(value)) return value.odataLiteral();
    switch (typeof value) {
      case "string":
        return string(value);
      case "boolean":
        return value ? "true" : "false";
      case "bigint":
        return value.toString();
      case "number":
        if (Number.isNaN(value)) return "NaN";
        if (!Number.isFinite(value)) return value > 0 ? "INF" : "-INF";
        return plain(value);
    }
    if (value instanceof Date) return value.toISOString().replace(".000Z", "Z");
    throw new TypeError(`No OData literal for ${Object.prototype.toString.call(value)}; pass ODataLiterals.Raw for a preformed literal`);
  }

  /** A preformed literal, sent as is. */
  export class Raw implements ODataLiteral {
    constructor(readonly literal: string) {
      if (typeof literal !== "string") throw new TypeError("a raw literal is a string");
    }

    odataLiteral(): string {
      return this.literal;
    }

    toString(): string {
      return this.literal;
    }
  }
}
