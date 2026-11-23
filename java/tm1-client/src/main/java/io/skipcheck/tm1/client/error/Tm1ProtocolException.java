// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

import lombok.Getter;

/**
 * TM1's response couldn't be read: malformed or truncated JSON, an unexpected shape, or
 * an error reported in the middle of a response that started as a success. OData says a
 * client must treat such a response as failed as a whole (Part 1 §9.5), so any items
 * already delivered from it should be treated as incomplete.
 */
public final class Tm1ProtocolException extends Tm1Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * The error TM1 reported in the response, if it reported one.
	 * @return the error, or {@code null}
	 */
	@Getter
	private final transient @Nullable ODataError error;

	/**
	 * Creates an exception for a response that couldn't be read.
	 * @param message what was wrong with the response; the method and URL are appended
	 * @param error the error TM1 reported in the response, or {@code null}
	 * @param method the request's HTTP method, or {@code null} if not known
	 * @param url the request's URL, without credentials, or {@code null} if not known
	 * @param cause the underlying failure, such as a parse error, or {@code null}
	 */
	public Tm1ProtocolException(String message, @Nullable ODataError error, @Nullable String method,
			@Nullable String url, @Nullable Throwable cause) {
		super(message + describe(method, url), method, url, cause);
		this.error = error;
	}

	@Override
	public boolean isRetryable() {
		return false;
	}

	/** True: the server got the request and started answering. */
	@Override
	public boolean mayHaveApplied() {
		return true;
	}

}
