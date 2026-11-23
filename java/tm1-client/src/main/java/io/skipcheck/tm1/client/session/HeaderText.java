package io.skipcheck.tm1.client.session;

import java.nio.charset.StandardCharsets;

/**
 * Text for a request header that TM1 reads as UTF-8: a user name in
 * {@code TM1-Impersonate}, a label in {@code TM1-SessionContext}.
 *
 * <p>
 * TM1 (11.8) decodes these headers as UTF-8 and nothing else: a name sent as ISO 8859-1
 * bytes, percent-encoded or as an RFC 2047 encoded word isn't found. HTTP headers have no
 * agreed encoding beyond ASCII, so HTTP libraries differ on what they do with other
 * characters. Reactor Netty, the default connector, writes each {@code char} of a header
 * value as one byte, so the value handed to it is the text's UTF-8 bytes with one
 * {@code char} standing for each byte. A connector that refuses or replaces characters
 * outside ASCII, as the JDK's {@code HttpClient} does, can't send such text at all.
 */
final class HeaderText {

	private HeaderText() {
	}

	/**
	 * {@code text} as a header value that goes out as its UTF-8 bytes.
	 * @param text the text
	 * @return the same string if it's ASCII, else one {@code char} for each UTF-8 byte
	 */
	static String utf8(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) > 0x7f) {
				return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
			}
		}
		return text;
	}

}
