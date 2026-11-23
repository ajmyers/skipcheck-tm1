// SPDX-License-Identifier: Apache-2.0

const encoder = new TextEncoder();

/** @internal Text for a request header that TM1 reads as UTF-8 (a user name in `TM1-Impersonate`, a
 * label in `TM1-SessionContext`), as the Java client's `HeaderText`: the same string if it's ASCII,
 * else one character for each of its UTF-8 bytes.
 *
 * TM1 (11.8) decodes these headers as UTF-8 and nothing else: a name sent as ISO 8859-1 bytes,
 * percent-encoded or as an RFC 2047 encoded word isn't found. `fetch` takes a header value as a
 * byte string: characters up to U+00FF, each sent as one byte, and a `TypeError` for anything
 * above. So the value handed to it stands for the bytes, and what goes out is the text in UTF-8. A
 * {@link Tm1Transport} of another kind must send each character of a header value as one byte too. */
export function headerText(text: string): string {
  // eslint-disable-next-line no-control-regex
  if (/^[\x00-\x7f]*$/.test(text)) return text;
  let out = "";
  for (const b of encoder.encode(text)) out += String.fromCharCode(b);
  return out;
}
