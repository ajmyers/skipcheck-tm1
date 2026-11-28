// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.error.Tm1ProtocolException;

/**
 * The result an OData async monitor holds once the work is done.
 *
 * <p>
 * TM1 11 wraps the result in an {@code application/http} message
 * ({@code HTTP/1.1 201 Created}, headers, a blank line and the body) and may gzip the
 * inner body ({@code Content-Encoding: gzip}). TM1 12 returns the body directly with
 * outer HTTP {@code 200} and the inner status in the {@code Asyncresult} response header,
 * keeping completed monitors cached until deleted.
 */
final class AsyncResult {

	static final String ASYNC_RESULT_HEADER = "Asyncresult";

	private final int status;

	private final byte[] body;

	private final boolean shouldDeleteMonitor;

	private AsyncResult(int status, byte[] body, boolean shouldDeleteMonitor) {
		this.status = status;
		this.body = body;
		this.shouldDeleteMonitor = shouldDeleteMonitor;
	}

	/** The status of the response the work produced. */
	int status() {
		return status;
	}

	/** Its body, possibly empty. */
	byte[] body() {
		return body.clone();
	}

	/**
	 * Whether the monitor should be deleted after reading this result: true for TM1 12
	 * responses carrying an {@code Asyncresult} header, which otherwise remain cached on
	 * the server.
	 */
	boolean shouldDeleteMonitor() {
		return shouldDeleteMonitor;
	}

	private static final byte[] BLANK_LINE = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	/**
	 * Reads a completed monitor response: TM1 11's {@code application/http} envelope, or
	 * TM1 12's direct body with an {@code Asyncresult} header.
	 * @param contentType the poll's {@code Content-Type} header, or {@code null}
	 * @param asyncResultHeader the poll's {@code Asyncresult} header, or {@code null}
	 * @param body the poll's response bytes
	 * @return the unwrapped status, body and whether the monitor needs cleanup
	 * @throws Tm1ProtocolException if the envelope or {@code Asyncresult} header is
	 * invalid
	 */
	static AsyncResult fromResponse(@Nullable String contentType, @Nullable String asyncResultHeader, byte[] body) {
		if (!isApplicationHttp(contentType) && asyncResultHeader != null) {
			return new AsyncResult(parseAsyncResultHeader(asyncResultHeader), body, true);
		}
		return parse(body);
	}

	private static boolean isApplicationHttp(@Nullable String contentType) {
		if (contentType == null) {
			return false;
		}
		int semi = contentType.indexOf(';');
		String mediaType = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
		return mediaType.equalsIgnoreCase("application/http");
	}

	private static int parseAsyncResultHeader(String value) {
		String trimmed = value.trim();
		if (trimmed.length() != 3 || trimmed.charAt(0) < '1' || trimmed.charAt(0) > '5' || trimmed.charAt(1) < '0'
				|| trimmed.charAt(1) > '9' || trimmed.charAt(2) < '0' || trimmed.charAt(2) > '9') {
			throw new Tm1ProtocolException("TM1 sent an invalid Asyncresult header: " + value, null, "GET", "_async",
					null);
		}
		return Integer.parseInt(trimmed);
	}

	/**
	 * Reads a monitor's {@code application/http} result.
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
			return new AsyncResult(status, new byte[0], false);
		}
		String headers = new String(message, 0, headersEnd, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
		byte[] body = Arrays.copyOfRange(message, headersEnd + BLANK_LINE.length, message.length);
		return new AsyncResult(status, headers.contains("\ncontent-encoding: gzip") ? gunzip(body) : body, false);
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
