package net.ajmyers.tm1.client.odata;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Makes a response's bytes valid UTF-8 before they're read as JSON: each byte that isn't
 * part of a character becomes U+FFFD, the replacement character, and everything else is
 * kept as it is.
 *
 * <p>
 * TM1 (11.8) writes some values into its JSON as the bytes it holds rather than as text.
 * The transaction log is where it shows: setting a user's password logs the change of the
 * {@code PASSWORD} cell of {@code }ClientProperties} with the password's hash as its new
 * value, bytes that aren't UTF-8. A JSON parser refuses the whole response for them, so
 * one such entry would make the log unreadable.
 *
 * <p>
 * An instance repairs one body given in pieces, keeping a character split across two
 * pieces whole; {@link #whole} repairs a body held in one array. Bytes that are all
 * ASCII, as most of TM1's are, are returned as given, without copying.
 */
public final class Utf8Repair {

	private static final byte[] NONE = new byte[0];

	private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
		.onMalformedInput(CodingErrorAction.REPLACE)
		.onUnmappableCharacter(CodingErrorAction.REPLACE);

	private byte[] carry = NONE;

	/** A repairer for one body. */
	public Utf8Repair() {
	}

	/**
	 * Repairs a whole body.
	 * @param bytes the body
	 * @return the body as valid UTF-8; the same array if it was all ASCII
	 */
	public static byte[] whole(byte[] bytes) {
		return ascii(bytes) ? bytes : new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Repairs the next piece of the body.
	 * @param bytes the piece
	 * @return what can be read so far, as valid UTF-8: the piece without the start of a
	 * character it ends in the middle of, which comes with the next piece
	 */
	public byte[] feed(byte[] bytes) {
		return carry.length == 0 && ascii(bytes) ? bytes : decode(bytes, false);
	}

	/**
	 * Ends the body.
	 * @return what's left: a replacement character for a character the body ended in the
	 * middle of, or nothing
	 */
	public byte[] end() {
		return carry.length == 0 ? NONE : decode(NONE, true);
	}

	private byte[] decode(byte[] bytes, boolean end) {
		ByteBuffer in = ByteBuffer.allocate(carry.length + bytes.length);
		in.put(carry).put(bytes).flip();
		// A character takes at least as many bytes as chars, and a malformed byte one
		CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
		decoder.decode(in, out, end);
		if (end) {
			decoder.flush(out);
		}
		carry = new byte[in.remaining()];
		in.get(carry);
		out.flip();
		ByteBuffer encoded = StandardCharsets.UTF_8.encode(out);
		byte[] repaired = new byte[encoded.remaining()];
		encoded.get(repaired);
		return repaired;
	}

	private static boolean ascii(byte[] bytes) {
		for (byte b : bytes) {
			if (b < 0) {
				return false;
			}
		}
		return true;
	}

}
