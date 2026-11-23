// SPDX-License-Identifier: Apache-2.0

const UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
const utf8 = new TextEncoder();

function encode(text: string, safe: string): string {
  let out = "";
  for (const b of utf8.encode(text)) {
    const c = String.fromCharCode(b);
    out += b < 128 && safe.includes(c) ? c : "%" + b.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}

/** Percent-encoding, as the Java client's `ODataEncoding`: only unreserved characters stay, plus
 * what each place allows. */
export const ODataEncoding = {
  /** For a path segment: only unreserved characters and `'` stay. */
  pathValue(text: string): string {
    return encode(text, UNRESERVED + "'");
  },

  /** For a query option's value, keeping OData's own punctuation. */
  queryValue(text: string): string {
    return encode(text, UNRESERVED + "$(),;':@/!*");
  },

  /** @internal A segment of a path TM1 sent. */
  linkSegment(segment: string): string {
    return encode(segment, UNRESERVED + "'!()");
  },
} as const;
