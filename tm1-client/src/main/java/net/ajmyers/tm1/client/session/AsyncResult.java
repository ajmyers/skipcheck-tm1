package net.ajmyers.tm1.client.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import net.ajmyers.tm1.client.error.Tm1ProtocolException;

/**
 * The result an OData async monitor holds once the work is done: an
 * {@code application/http} message, {@code HTTP/1.1 201 Created}, headers, a blank line
 * and the body. Only the status and body matter here; the body's type is the request's.
 *
 * <p>
 * TM1 (11.8) compresses the inner body when the request accepted gzip
 * ({@code Content-Encoding: gzip} among the inner headers), though the monitor's own
 * response isn't compressed; the body is decompressed here.
 */
final class AsyncResult {

	private final int status;

	private final byte[] body;

	private AsyncResult(int status, byte[] body) {
		this.status = status;
		this.body = body;
	}

	/** The status of the response the work produced. */
	int status() {
		return status;
	}

	/** Its body, possibly empty. */
	byte[] body() {
		return body.clone();
	}

	private static final byte[] BLANK_LINE = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	/**
	 * Reads a monitor's result.
	 * @param message the {@code application/http} message
	 * @return its status and body
	 * @throws Tm1ProtocolException if it doesn't start with a status line
	 */
	static AsyncResult parse(byte[] message) {
		int lineEnd = indexOf(message, new byte[] { '\r', '\n' }, 0);
		String statusLine = new String(message, 0, lineEnd < 0 ? message.length : lineEnd, StandardCharsets.US_ASCII);
		String[] parts = statusLine.split(" ", 3);
		if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
			throw new Tm1ProtocolException("TM1's async result isn't an HTTP message: " + statusLine, null, "GET",
					"_async", null);
		}
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException e) {
			throw new Tm1ProtocolException("TM1's async result has no status: " + statusLine, null, "GET", "_async", e);
		}
		int headersEnd = indexOf(message, BLANK_LINE, 0);
		if (headersEnd < 0) {
			return new AsyncResult(status, new byte[0]);
		}
		String headers = new String(message, 0, headersEnd, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
		byte[] body = Arrays.copyOfRange(message, headersEnd + BLANK_LINE.length, message.length);
		return new AsyncResult(status, headers.contains("\ncontent-encoding: gzip") ? gunzip(body) : body);
	}

	private static byte[] gunzip(byte[] body) {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new Tm1ProtocolException("TM1's async result says gzip but isn't", null, "GET", "_async", e);
		}
	}

	private static int indexOf(byte[] data, byte[] target, int from) {
		outer: for (int i = from; i <= data.length - target.length; i++) {
			for (int j = 0; j < target.length; j++) {
				if (data[i + j] != target[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	@Override
	public String toString() {
		return "AsyncResult[status=" + status + ", body=" + body.length + " bytes]";
	}

}
