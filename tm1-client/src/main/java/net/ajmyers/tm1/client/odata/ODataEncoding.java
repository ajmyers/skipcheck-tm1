package net.ajmyers.tm1.client.odata;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding for OData URLs, applied exactly once to each piece (OData Part 2 §2.1).
 *
 * <p>TM1 object names contain almost anything: {@code }Clients}, {@code Year 2026},
 * {@code O'Neil}, {@code a/b}, {@code 50%}, {@code {x}}, {@code &=+#}. Two rules, one per place
 * a value can go, keep them intact:
 *
 * <ul>
 *   <li>{@link #pathValue}: inside a path segment, such as a key literal. Only unreserved
 *       characters and {@code '} are left as is; {@code /} becomes {@code %2F}.
 *   <li>{@link #queryValue}: a query option's value. The OData punctuation used inside values
 *       ({@code $ ( ) , ; ' : @ / ! *}) is kept readable; {@code & = + #} and everything else
 *       is encoded, so a value can't split or end the query. A space is {@code %20}, never
 *       {@code +}.
 * </ul>
 */
public final class ODataEncoding {

	private static final boolean[] PATH_SAFE = safe("'");
	private static final boolean[] QUERY_SAFE = safe("$(),;':@/!*");
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private ODataEncoding() {
	}

	/** Encodes text for use inside a path segment, e.g. the inside of a key literal. */
	public static String pathValue(String text) {
		return encode(text, PATH_SAFE);
	}

	/** Encodes text for use as (part of) a query option's value. */
	public static String queryValue(String text) {
		return encode(text, QUERY_SAFE);
	}

	private static String encode(String text, boolean[] safe) {
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if (c < 128 && safe[c]) {
				out.append((char) c);
			}
			else {
				out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
			}
		}
		return out.toString();
	}

	private static boolean[] safe(String extra) {
		boolean[] safe = new boolean[128];
		for (char c = 'A'; c <= 'Z'; c++) {
			safe[c] = true;
		}
		for (char c = 'a'; c <= 'z'; c++) {
			safe[c] = true;
		}
		for (char c = '0'; c <= '9'; c++) {
			safe[c] = true;
		}
		for (char c : "-._~".toCharArray()) {
			safe[c] = true;
		}
		for (char c : extra.toCharArray()) {
			safe[c] = true;
		}
		return safe;
	}
}
