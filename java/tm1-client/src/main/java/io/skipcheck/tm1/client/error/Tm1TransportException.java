// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

import lombok.Getter;

/**
 * No complete response from TM1: the connection failed, was reset, or timed out.
 *
 * <p>
 * {@link #phase()} says how far the request got, which decides whether it could have
 * taken effect: a request that never connected didn't, one that timed out waiting for the
 * response may have. TM1 only answers some long operations (processes, big MDX) when they
 * finish, so a response timeout often means the work is still running.
 */
public final class Tm1TransportException extends Tm1Exception {

	private static final long serialVersionUID = 1L;

	/** How far the request got. */
	public enum Phase {

		/**
		 * Before the request was sent: connecting, TLS, or waiting for a pooled
		 * connection.
		 */
		CONNECT,
		/** After the request was sent, before the response was complete. */
		RESPONSE

	}

	/**
	 * How far the request got.
	 * @return the phase the request failed in
	 */
	@Getter
	private final Phase phase;

	/** Whether the request is safe to repeat. */
	private final boolean idempotent;

	/**
	 * Creates an exception for a request that got no complete response.
	 * @param phase how far the request got
	 * @param idempotent whether the request is safe to repeat (GET, PUT, DELETE), which
	 * makes a response-phase failure retryable
	 * @param method the request's HTTP method
	 * @param url the request's URL, without credentials
	 * @param cause the underlying failure, or {@code null}
	 */
	public Tm1TransportException(Phase phase, boolean idempotent, String method, String url,
			@Nullable Throwable cause) {
		super((phase == Phase.CONNECT ? "Couldn't send the request to TM1" : "No complete response from TM1")
				+ describe(method, url)
				+ (cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage()), method, url, cause);
		this.phase = phase;
		this.idempotent = idempotent;
	}

	/**
	 * Connect failures are always retryable; response failures only for idempotent
	 * requests.
	 */
	@Override
	public boolean isRetryable() {
		return phase == Phase.CONNECT || idempotent;
	}

	/** False only if the request was never sent. */
	@Override
	public boolean mayHaveApplied() {
		return phase == Phase.RESPONSE;
	}

}
